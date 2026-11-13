package ch.heuscher.gentleconnect.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.MailAttachments

/**
 * Room entity for a mail the notification listener has seen.
 *
 * Mail reaches this app only as a notification, and a notification lives only until it is
 * dismissed or the phone restarts. Without this table her Post screen is rebuilt from whatever
 * notifications happen to be on screen at that moment — which is exactly the mail she has not
 * read yet, and nothing else. Persisting means a mail stays visible after she reads it.
 */
@Entity(tableName = "mails")
data class MailEntity(
    @PrimaryKey val id: String,
    val senderName: String,
    val senderAddress: String,
    val subject: String,
    val snippet: String,
    val timestamp: Long,
    val isRead: Boolean,
    val body: String = "",
    val messageId: String? = null,
    val uid: Long? = null,
    val uidValidity: Long? = null,
    /**
     * Kept as the column `isVisible` on purpose, though it no longer decides visibility.
     *
     * SQLite could not rename a column before 3.25, which is Android 11, and this app supports
     * back to Android 8 — so a rename here means rebuilding the table by hand and copying every
     * letter she has. That is real risk to her data in exchange for a nicer column name. The
     * Kotlin name is the one anybody reads; the string in the database is history.
     */
    @ColumnInfo(name = "isVisible")
    val isFromKnownSender: Boolean = true,
    /**
     * The pictures and the paper that came with the letter, as one line of text.
     *
     * A list in a column rather than a table of its own — see [MailAttachments.encode] for why
     * that is worth it here and would not be in general. Defaulted to empty, because every letter
     * stored before this column existed genuinely has none: they were fetched by a version that
     * threw the files away.
     */
    val attachments: String = ""
)

fun MailEntity.toMail(): Mail = Mail(
    id = id,
    senderName = senderName,
    senderAddress = senderAddress,
    subject = subject,
    snippet = snippet,
    timestamp = timestamp,
    isRead = isRead,
    body = body,
    messageId = messageId,
    uid = uid,
    uidValidity = uidValidity,
    isFromKnownSender = isFromKnownSender,
    attachments = MailAttachments.decode(attachments)
)

fun Mail.toEntity(): MailEntity = MailEntity(
    id = id,
    senderName = senderName,
    senderAddress = senderAddress,
    subject = subject,
    snippet = snippet,
    timestamp = timestamp,
    isRead = isRead,
    body = body,
    messageId = messageId,
    uid = uid,
    uidValidity = uidValidity,
    isFromKnownSender = isFromKnownSender,
    attachments = MailAttachments.encode(attachments)
)
