package ch.heuscher.gentleconnect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MailDao {

    /**
     * IGNORE, not REPLACE. The listener re-processes every active notification each time the
     * launcher resumes, so the same mail is offered over and over. REPLACE would write
     * `isRead = false` back over a mail she had already opened, and it would silently reappear
     * as new every time she returned to the home screen.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMail(mail: MailEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMails(mails: List<MailEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceMail(mail: MailEntity)

    @Query("UPDATE mails SET isRead = 1 WHERE id = :mailId")
    suspend fun markAsRead(mailId: String)

    @Query("UPDATE mails SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllAsRead()

    @Query("SELECT * FROM mails ORDER BY timestamp DESC")
    fun getAllMailsFlow(): Flow<List<MailEntity>>

    // There was a getVisibleMailsFlow() here — `WHERE isVisible = 1` — and it was the query that
    // hid every letter from an address her contacts did not already carry. It is gone rather than
    // left unused: the column still exists (see MailEntity), and a query that quietly drops her
    // post is not something to leave lying around for somebody to wire back up. Everything now
    // goes through getAllMailsFlow, and how a letter is drawn is decided by MailPresentation.

    @Query("UPDATE mails SET uid = NULL, uidValidity = NULL")
    suspend fun clearUids()

    @Query("SELECT MAX(uid) FROM mails WHERE uidValidity = :validity")
    suspend fun getMaxUid(validity: Long): Long?

    @Query("SELECT MIN(uid) FROM mails WHERE uidValidity = :validity")
    suspend fun getMinUid(validity: Long): Long?

    /** Every stored uid under [validity] — for Gmail, the ids already downloaded. */
    @Query("SELECT uid FROM mails WHERE uidValidity = :validity AND uid IS NOT NULL")
    suspend fun getUids(validity: Long): List<Long>

    /**
     * One-shot read of deduplication candidates, hidden mail included.
     *
     * Hidden mail has to be in here: deduplication must be able to match an incoming mail
     * against a stored one that the sender filter hid, or the hidden copy is invisible to the
     * check and the mail lands twice.
     *
     * Bounded by time rather than reading the table. Every row carries a full message body, and
     * this runs in a background worker every fifteen minutes — against a mailbox with years of
     * history that is a large allocation on a schedule, for no benefit. Deduplication only ever
     * matches on identity or on a few minutes' timestamp tolerance, so old mail can never be a
     * candidate for a letter arriving now.
     */
    @Query("SELECT * FROM mails WHERE timestamp >= :sinceTimestamp ORDER BY timestamp DESC")
    suspend fun getMailsSince(sinceTimestamp: Long): List<MailEntity>

    /** What the caregiver report counts — no sender, no subject. See `CaregiverStatsExport`. */
    @Query("SELECT timestamp, isVisible AS fromKnownSender FROM mails WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun countsSince(since: Long): List<MailCountRow>

    /**
     * Marks stored mail read because the server says it is.
     *
     * Deliberately one-directional — it only ever sets `isRead` to 1, never back to 0. Read
     * state she creates by opening a letter here is never written to the server, so the server
     * reporting "unseen" is not evidence she has not read it; treating it as such would make
     * every letter she opens turn bold again at the next sync.
     */
    @Query("UPDATE mails SET isRead = 1 WHERE uidValidity = :validity AND uid IN (:uids)")
    suspend fun markSeenByUids(validity: Long, uids: List<Long>)
}

/** One mail as the caregiver report sees it: when, and whether the sender is in her contacts. */
data class MailCountRow(
    val timestamp: Long,
    val fromKnownSender: Boolean
)
