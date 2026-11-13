package ch.heuscher.gentleconnect.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.AppStores
import ch.heuscher.gentleconnect.data.CaregiverLogRepository
import ch.heuscher.gentleconnect.data.ContactsRepository
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.data.db.toMail
import ch.heuscher.gentleconnect.data.mail.FileMailAttachmentStore
import ch.heuscher.gentleconnect.data.mail.GmailApiFetcher
import ch.heuscher.gentleconnect.data.mail.GmailAuthorizer
import ch.heuscher.gentleconnect.data.mail.GmailIds
import ch.heuscher.gentleconnect.data.mail.GmailUnauthorizedException
import ch.heuscher.gentleconnect.data.mail.MailAccountRepository
import ch.heuscher.gentleconnect.data.mail.MailAllowlistRepository
import ch.heuscher.gentleconnect.data.mail.MailDeduplicator
import ch.heuscher.gentleconnect.data.mail.MailSenderFilter
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker that reads her Gmail inbox through the Gmail API, off the main
 * thread. Read-only: see [GmailApiFetcher].
 */
class MailSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val accountRepo = MailAccountRepository(applicationContext)
            val address = accountRepo.getGmailAddress() ?: return@withContext Result.success()

            // No grant, or it was withdrawn in her Google account. Only the consent screen on the
            // phone fixes that, and a worker cannot show it; retrying would only wake the radio.
            val token = GmailAuthorizer.silentToken(applicationContext, address)
                ?: return@withContext mailAccessLost()

            val contactsRepo = ContactsRepository(applicationContext)
            val allowlistRepo = MailAllowlistRepository(applicationContext)
            val knownContacts = contactsRepo.queryContactEmailAddresses()
            val allowlist = allowlistRepo.getAllowlist()

            val senderFilter = MailSenderFilter(
                knownAddresses = knownContacts,
                allowlist = allowlist
            )

            // The shared instance, not a new one. A fresh MailRepository would start its own
            // coroutine collecting the database on every run — a scope per sync, none of them
            // cancelled — and its `mails` StateFlow would still be empty here, because
            // attachPersistence fills it asynchronously. Deduplication below would then compare
            // every incoming mail against an empty list and never match anything, so a letter
            // re-fetched after a UID reset would survive twice.
            // The UI would look right throughout, since it reads the same database.
            val mailRepo = AppStores.mail
                .apply { attachPersistence(applicationContext) }
            val mailDao = AppDatabase.getInstance(applicationContext).mailDao()

            val storedUids = mailDao.getUids(GmailIds.UID_VALIDITY).toSet()

            val fetcher = GmailApiFetcher()
            val attachmentStore = FileMailAttachmentStore(
                File(applicationContext.filesDir, FileMailAttachmentStore.DIRECTORY)
            )
            val fetch = { accessToken: String ->
                fetcher.fetch(
                    accessToken = accessToken,
                    storedUids = storedUids,
                    senderFilter = senderFilter,
                    unknownSenderLabel = applicationContext.getString(R.string.mail_unknown_sender),
                    attachmentStore = attachmentStore
                )
            }
            val result = try {
                fetch(token)
            } catch (e: GmailUnauthorizedException) {
                // Play services can hand back a cached token that has just expired or been
                // revoked. Drop it and ask once more; a second refusal is real and ends the run.
                GmailAuthorizer.clearToken(applicationContext, token)
                val fresh = GmailAuthorizer.silentToken(applicationContext, address)
                    ?: return@withContext mailAccessLost()
                fetch(fresh)
            }

            if (result.seenUids.isNotEmpty()) {
                mailDao.markSeenByUids(GmailIds.UID_VALIDITY, result.seenUids.toList())
            }

            // Read from the database rather than the repository's StateFlow: this includes mail
            // the sender filter hid, which the flow does not, and it is populated now rather
            // than whenever the flow's collector next emits.
            val oldestFetched = result.fetchedMails.minOfOrNull { it.timestamp }
            val existingMails = if (oldestFetched == null) {
                emptyList()
            } else {
                mailDao.getMailsSince(oldestFetched - DEDUP_CANDIDATE_WINDOW_MS).map { it.toMail() }
            }
            for (fetchedMail in result.fetchedMails) {
                val matching = existingMails.firstOrNull { MailDeduplicator.isDuplicate(it, fetchedMail) }
                if (matching != null) {
                    // The Gmail id wins over whatever the stored copy carried — typically the UID
                    // of the IMAP sync this replaced. Left as it was, the letter never counts as
                    // stored under GmailIds.UID_VALIDITY: it is downloaded again on every run and
                    // its read state is never updated.
                    val merged = MailDeduplicator.mergeMails(matching, fetchedMail)
                        .copy(uid = fetchedMail.uid, uidValidity = fetchedMail.uidValidity)
                    mailRepo.upsertMail(merged)
                } else {
                    mailRepo.upsertMail(fetchedMail)
                }
            }

            Result.success()
        } catch (e: GmailUnauthorizedException) {
            // Retrying will not fix a grant that has been withdrawn, and WorkManager would keep
            // waking the radio to try again indefinitely. Stop, and say so.
            mailAccessLost()
        } catch (e: Throwable) {
            if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
                // High-latency or transient connection errors: abandon aggressive WorkManager retries
                // to protect the battery, and let the next scheduled periodic interval try cleanly.
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    /**
     * Her post has stopped and only somebody at the phone can restart it, so it goes to the
     * caregiver log, which the website publishes as an alert. The periodic sync keeps landing
     * here until it is fixed; the log collapses the repeats into one line and the website sends
     * the same fault at most once per cooldown, so this is one warning and not one per sync.
     */
    private suspend fun mailAccessLost(): Result {
        try {
            CaregiverLogRepository(AppDatabase.getInstance(applicationContext).caregiverEventDao())
                .record(
                    CaregiverEvent(
                        kind = CaregiverEventKind.MAIL_ACCESS_LOST,
                        at = System.currentTimeMillis()
                    )
                )
        } catch (e: Exception) {
            // A log that throws must not turn a known failure into a retry loop.
        }
        return Result.failure()
    }

    companion object {
        const val PERIODIC_WORK_NAME = "MailSyncWorker_Periodic"
        const val ONE_TIME_WORK_NAME = "MailSyncWorker_OneTime"

        /** Maximum consecutive retries on transient connection/timeout failure before pausing. */
        const val MAX_RETRY_ATTEMPTS = 3

        /**
         * How far back of stored mail to consider when looking for a duplicate. Generous next
         * to MailDeduplicator's few minutes of timestamp tolerance, and it keeps the candidate
         * read off the size of her whole mailbox.
         */
        private const val DEDUP_CANDIDATE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<MailSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    1,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        fun enqueueOneTimeSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<MailSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    1,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }
}
