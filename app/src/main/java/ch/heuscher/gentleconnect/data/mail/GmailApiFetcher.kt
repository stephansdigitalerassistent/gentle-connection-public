package ch.heuscher.gentleconnect.data.mail

import ch.heuscher.gentleconnect.model.Mail
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage

/** Gmail answered 401: the access token is stale or the grant was withdrawn. */
class GmailUnauthorizedException(message: String) : Exception(message)

/** Any other non-2xx answer from Gmail. */
class GmailHttpException(val code: Int, message: String) : Exception(message)

data class GmailFetchResult(
    val fetchedMails: List<Mail>,
    /**
     * Stored letters Gmail now reports as read. Never carries the opposite signal: see
     * [ch.heuscher.gentleconnect.data.db.MailDao.markSeenByUids].
     */
    val seenUids: Set<Long> = emptySet()
)

/**
 * Reads her inbox through the Gmail REST API with a `gmail.readonly` token.
 *
 * Read-only is enforced by Google, not by this class: the token cannot change, send or delete
 * anything, so nothing here can mark a letter read on the server or lose one. Every call is a GET.
 *
 * Each letter is downloaded whole (`format=raw`) and handed to the same MIME code the IMAP sync
 * used, so the words, the pictures and the sender filter behave exactly as before.
 */
class GmailApiFetcher(private val baseUrl: String = BASE_URL) {

    /** Must be called from a background thread. */
    fun fetch(
        accessToken: String,
        storedUids: Set<Long>,
        senderFilter: MailSenderFilter,
        fetchCap: Int = DEFAULT_FETCH_CAP,
        unknownSenderLabel: String = "",
        attachmentStore: MailAttachmentStore? = null
    ): GmailFetchResult {
        val inboxIds = listIds(accessToken, listOf(LABEL_INBOX), INBOX_WINDOW)
        val unreadIds = listIds(accessToken, listOf(LABEL_INBOX, LABEL_UNREAD), UNREAD_WINDOW).toSet()
        val plan = GmailSyncPlanner.plan(inboxIds, unreadIds, storedUids, fetchCap)

        val session = Session.getInstance(Properties())
        val mails = plan.toFetch.mapNotNull { id ->
            try {
                fetchOne(accessToken, id, session, senderFilter, unknownSenderLabel, attachmentStore)
            } catch (e: GmailUnauthorizedException) {
                throw e
            } catch (e: Exception) {
                // One letter that will not download or parse — deleted between the list and the
                // get, or malformed — must not cost her the rest of her post.
                null
            }
        }
        return GmailFetchResult(fetchedMails = mails, seenUids = plan.seenUids)
    }

    private fun fetchOne(
        accessToken: String,
        id: String,
        session: Session,
        senderFilter: MailSenderFilter,
        unknownSenderLabel: String,
        attachmentStore: MailAttachmentStore?
    ): Mail? {
        val uid = GmailIds.toUid(id) ?: return null
        val json = getJson(accessToken, "messages/${encode(id)}?format=raw")
        val raw = json.optString("raw").ifEmpty { return null }
        val bytes = Base64.getUrlDecoder().decode(raw)
        val mimeMsg = MimeMessage(session, ByteArrayInputStream(bytes))

        val labels = json.optJSONArray("labelIds")
        val isUnread = labels != null && (0 until labels.length()).any { labels.optString(it) == LABEL_UNREAD }
        val internalDate = json.optString("internalDate").toLongOrNull()

        return MimeMailMapper.toMail(
            mimeMsg = mimeMsg,
            fallbackId = "gmail_$id",
            uid = uid,
            uidValidity = GmailIds.UID_VALIDITY,
            isRead = !isUnread,
            fallbackTimestamp = internalDate,
            senderFilter = senderFilter,
            unknownSenderLabel = unknownSenderLabel,
            attachmentStore = attachmentStore
        )
    }

    /** Up to [limit] message ids carrying all of [labelIds], newest first. */
    private fun listIds(accessToken: String, labelIds: List<String>, limit: Int): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val query = buildString {
                append("messages?maxResults=").append(minOf(limit - ids.size, MAX_PAGE_SIZE))
                labelIds.forEach { append("&labelIds=").append(encode(it)) }
                if (pageToken != null) append("&pageToken=").append(encode(pageToken!!))
            }
            val json = getJson(accessToken, query)
            val messages = json.optJSONArray("messages")
            if (messages != null) {
                for (i in 0 until messages.length()) {
                    messages.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let(ids::add)
                }
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null && ids.size < limit)
        return ids.take(limit)
    }

    private fun getJson(accessToken: String, pathAndQuery: String): JSONObject {
        val conn = URL(baseUrl + pathAndQuery).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw GmailUnauthorizedException("Gmail rejected the access token")
            }
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300).orEmpty()
                throw GmailHttpException(code, "Gmail answered $code: $detail")
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me/"
        const val DEFAULT_FETCH_CAP = 50

        /** How many of the newest inbox letters are looked at, for new mail and for read state. */
        private const val INBOX_WINDOW = 200
        private const val UNREAD_WINDOW = 500
        private const val MAX_PAGE_SIZE = 500
        private const val TIMEOUT_MS = 20_000

        private const val LABEL_INBOX = "INBOX"
        private const val LABEL_UNREAD = "UNREAD"
    }
}
