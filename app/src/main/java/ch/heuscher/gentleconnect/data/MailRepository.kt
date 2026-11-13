package ch.heuscher.gentleconnect.data

import android.content.Context
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.data.db.MailDao
import ch.heuscher.gentleconnect.data.db.toEntity
import ch.heuscher.gentleconnect.data.db.toMail
import ch.heuscher.gentleconnect.data.mail.MailDeduplicator
import ch.heuscher.gentleconnect.model.Mail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Interface abstraction for fetching read-only mail items.
 */
interface MailSource {
    val mails: StateFlow<List<Mail>>
    fun addMail(mail: Mail)
    fun markAsRead(mailId: String)
    fun markAllAsRead()
}

/**
 * Local implementation of MailSource holding every mail the notification listener has seen.
 * Strictly read-only: no composition, editing, sending, or deletion is permitted.
 *
 * Once [attachPersistence] has been called the database is the source of truth, so a mail
 * survives being read, having its notification dismissed, and the phone being restarted.
 * Before that — and if the database is somehow unavailable — mail is held in memory, so a
 * notification arriving during startup is not dropped on the floor.
 */
class MailRepository : MailSource {

    private val _mails = MutableStateFlow<List<Mail>>(emptyList())
    override val mails: StateFlow<List<Mail>> = _mails.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var dao: MailDao? = null

    /**
     * Point this repository at the database. Idempotent: the notification listener and the
     * launcher UI both call it, either one may start first, and on this phone they share a
     * process and therefore share this object.
     */
    @Synchronized
    fun attachPersistence(context: Context) {
        if (dao != null) return
        val mailDao = AppDatabase.getInstance(context.applicationContext).mailDao()
        dao = mailDao

        val alreadyInMemory = _mails.value
        scope.launch {
            if (alreadyInMemory.isNotEmpty()) {
                mailDao.insertMails(alreadyInMemory.map { it.toEntity() })
            }
            mailDao.getAllMailsFlow().collect { entities ->
                _mails.value = entities.map { it.toMail() }
            }
        }
    }

    override fun addMail(mail: Mail) {
        val mailDao = dao
        if (mailDao == null) {
            _mails.update { current ->
                MailDeduplicator.deduplicate(listOf(mail) + current)
            }
            return
        }
        scope.launch { mailDao.insertMail(mail.toEntity()) }
    }

    fun upsertMail(mail: Mail) {
        val mailDao = dao
        if (mailDao == null) {
            _mails.update { current ->
                MailDeduplicator.deduplicate(listOf(mail) + current)
            }
            return
        }
        scope.launch { mailDao.replaceMail(mail.toEntity()) }
    }

    override fun markAsRead(mailId: String) {
        val mailDao = dao
        if (mailDao == null) {
            _mails.update { current ->
                current.map { m ->
                    if (m.id == mailId) m.copy(isRead = true) else m
                }
            }
            return
        }
        scope.launch { mailDao.markAsRead(mailId) }
    }

    override fun markAllAsRead() {
        val mailDao = dao
        if (mailDao == null) {
            _mails.update { current ->
                current.map { it.copy(isRead = true) }
            }
            return
        }
        scope.launch { mailDao.markAllAsRead() }
    }

    fun clearUids() {
        val mailDao = dao
        if (mailDao == null) {
            _mails.update { current ->
                current.map { it.copy(uid = null, uidValidity = null) }
            }
            return
        }
        scope.launch { mailDao.clearUids() }
    }
}
