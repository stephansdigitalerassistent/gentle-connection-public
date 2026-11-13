package ch.heuscher.gentleconnect.data.threema

/**
 * Pure Kotlin data model representing a contact extracted from a Threema backup contacts.csv.
 */
data class ThreemaBackupContact(
    val identity: String,
    val publicKey: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val nickname: String? = null,
    val verification: String? = null,
    /**
     * Threema's own row number for this contact, from the `identity_id` column.
     *
     * Worth carrying because it is what the message files are named after: her conversation with
     * `TSTMBR09` is in `message_0008473196.csv`. Without this column every restored conversation
     * is filed under a number that matches no contact, no name and no photograph — which is what
     * happened to a real 7,301-message import before this existed.
     */
    val identityId: String? = null
) {
    /**
     * Resolves human-readable display name from first/last name, nickname, or raw Threema ID.
     */
    val displayName: String
        get() {
            val full = listOfNotNull(firstName?.takeIf { it.isNotBlank() }, lastName?.takeIf { it.isNotBlank() })
                .joinToString(" ")
            if (full.isNotBlank()) return full
            if (!nickname.isNullOrBlank()) return nickname
            return identity
        }
}

/**
 * Pure Kotlin data model representing a message extracted from a Threema backup message_<identity>.csv.
 */
data class ThreemaBackupMessage(
    val id: String,
    /**
     * The conversation this belongs to: the other person for a private chat, and the group's own
     * id for a group one. Never the sender — see [senderIdentity].
     */
    val partnerIdentity: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isRead: Boolean = true,
    val type: String? = null,
    /**
     * Who wrote it, when the file says so — which only group message files do.
     *
     * Kept apart from [partnerIdentity] because conflating the two turns one group of nine people
     * into nine private conversations, each holding one member's share of a conversation that
     * never happened. Null in a private chat, where who wrote it is answered by [isOutgoing].
     */
    val senderIdentity: String? = null
)

/**
 * A group as a Threema data backup describes it.
 *
 * Two ids, and they are not interchangeable. [id] with [creator] is the group itself — the pair
 * everything else in this app identifies a group by, see
 * [ch.heuscher.gentleconnect.model.GroupIdentity]. [groupUid] is Threema's own row number, and its
 * only job is to say which file the messages are in: `group_message_0052891174.csv`.
 */
data class ThreemaBackupGroup(
    val id: String,
    val creator: String,
    val name: String? = null,
    val memberIdentities: List<String> = emptyList(),
    val groupUid: String? = null,
    /**
     * When Threema last changed anything about this group, and when it was made.
     *
     * These are what dates [memberIdentities]. The list is a snapshot, not a standing fact: it was
     * true when the backup was written and says nothing about what the family did afterwards, so
     * whoever applies it has to know how old it is. See [membershipKnownAt].
     */
    val lastUpdate: Long = 0L,
    val createdAt: Long = 0L,
    /**
     * Whether she herself is still in the group: `0` a member, `1` removed, `2` left.
     *
     * Threema's own `user_state` column, written from `GroupModel.UserState`. Defaults to member,
     * which is both what Threema writes when it has no state to write and the only reading that
     * makes an older archive without the column behave as it did before.
     *
     * Read here and deliberately not acted on: [memberIdentities] already says the same thing,
     * because Threema puts her own identity in that list exactly when this is
     * [USER_STATE_MEMBER] — `GroupServiceImpl.isGroupMember` *is* that comparison — and it is the
     * list that GroupReplyPolicy asks. Kept so that the two can be told apart if an archive ever
     * disagrees with itself, and so that the reason a left group is not answerable is written
     * down somewhere rather than inferred from an absence.
     */
    val userState: Int = USER_STATE_MEMBER
) {

    /** She is in this group, rather than having been removed from it or having left it. */
    val isStillAMember: Boolean get() = userState == USER_STATE_MEMBER

    /**
     * When [memberIdentities] was last true, as far as this archive can say.
     *
     * The group's own `last_update`, or failing that the day it was made. An archive that dates
     * itself neither way falls back to [importedAt] — the file is being read now, so now is the
     * latest the list can possibly be from, and the alternative is a zero, which downstream reads
     * as "we were never told the membership" and would leave the group unanswerable for the sake
     * of a missing column.
     */
    fun membershipKnownAt(importedAt: Long): Long = when {
        lastUpdate > 0L -> lastUpdate
        createdAt > 0L -> createdAt
        else -> importedAt
    }

    companion object {
        const val USER_STATE_MEMBER = 0
        const val USER_STATE_KICKED = 1
        const val USER_STATE_LEFT = 2
    }
}

/**
 * Pure, column-name-driven CSV parser for Threema data-backup CSV files.
 * Contains ZERO Android framework imports for JVM unit testability and safety.
 */
object ThreemaBackupParser {

    /**
     * Parses `contacts.csv` content from a Threema data backup.
     * Expects columns such as `identity`, `publickey`, `firstname`, `lastname`, `nickname`, `verification`.
     */
    fun parseContactsCsv(csv: String): List<ThreemaBackupContact> {
        if (csv.isBlank()) return emptyList()
        val rows = parseCsvRows(csv)
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { cleanHeader(it) }
        val identityIdx = findHeaderIndex(header, "identity")
        val publicKeyIdx = findHeaderIndex(header, "publickey", "public_key")
        val firstNameIdx = findHeaderIndex(header, "firstname", "first_name")
        val lastNameIdx = findHeaderIndex(header, "lastname", "last_name")
        val nicknameIdx = findHeaderIndex(header, "nickname")
        val identityIdIdx = findHeaderIndex(header, "identityid")
        val verificationIdx = findHeaderIndex(header, "verification")

        if (identityIdx == -1) return emptyList()

        val contacts = mutableListOf<ThreemaBackupContact>()
        for (i in 1 until rows.size) {
            val row = rows[i]
            if (row.isEmpty() || row.all { it.isBlank() }) continue

            val identity = getCell(row, identityIdx)?.trim()
            if (identity.isNullOrBlank()) continue // Skip malformed rows missing identity

            contacts.add(
                ThreemaBackupContact(
                    identity = identity,
                    publicKey = getCell(row, publicKeyIdx)?.trim()?.ifEmpty { null },
                    firstName = getCell(row, firstNameIdx)?.trim()?.ifEmpty { null },
                    lastName = getCell(row, lastNameIdx)?.trim()?.ifEmpty { null },
                    nickname = getCell(row, nicknameIdx)?.trim()?.ifEmpty { null },
                    verification = getCell(row, verificationIdx)?.trim()?.ifEmpty { null },
                    identityId = getCell(row, identityIdIdx)?.trim()?.ifEmpty { null }
                )
            )
        }
        return contacts
    }

    /**
     * Writes `contacts.csv` content for a Threema data backup.
     */
    fun writeContactsCsv(contacts: List<ThreemaBackupContact>): String {
        val sb = StringBuilder()
        sb.append("identity,publickey,firstname,lastname,nickname,verification\n")
        for (c in contacts) {
            sb.append(escapeCsv(c.identity)).append(',')
            sb.append(escapeCsv(c.publicKey)).append(',')
            sb.append(escapeCsv(c.firstName)).append(',')
            sb.append(escapeCsv(c.lastName)).append(',')
            sb.append(escapeCsv(c.nickname)).append(',')
            sb.append(escapeCsv(c.verification)).append('\n')
        }
        return sb.toString()
    }

    /**
     * Writes `message_<identity>.csv` content for a Threema data backup.
     */
    fun writeMessagesCsv(messages: List<ThreemaBackupMessage>): String {
        val sb = StringBuilder()
        sb.append("uid,isoutbox,identity,body,type,posted_at,isread\n")
        for (m in messages) {
            sb.append(escapeCsv(m.id)).append(',')
            sb.append(if (m.isOutgoing) "1" else "0").append(',')
            sb.append(escapeCsv(m.partnerIdentity)).append(',')
            sb.append(escapeCsv(m.text)).append(',')
            sb.append(escapeCsv(m.type ?: "text")).append(',')
            sb.append(m.timestamp).append(',')
            sb.append(if (m.isRead) "1" else "0").append('\n')
        }
        return sb.toString()
    }

    /**
     * Standard RFC 4180 CSV field escaping.
     */
    fun escapeCsv(value: String?): String {
        if (value == null) return ""
        if (value.contains('"') || value.contains(',') || value.contains(';') || value.contains('\n') || value.contains('\r')) {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }

    /**
     * Parses `groups.csv` from a Threema data backup.
     *
     * Members arrive as one semicolon-separated cell, which is Threema's shape and not a list this
     * app invented; blanks in it are dropped rather than becoming a member with no name.
     */
    fun parseGroupsCsv(csv: String): List<ThreemaBackupGroup> {
        if (csv.isBlank()) return emptyList()
        val rows = parseCsvRows(csv)
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { cleanHeader(it) }
        val idIdx = findHeaderIndex(header, "id")
        val creatorIdx = findHeaderIndex(header, "creator")
        val nameIdx = findHeaderIndex(header, "groupname", "name")
        val membersIdx = findHeaderIndex(header, "members")
        val uidIdx = findHeaderIndex(header, "groupuid")
        val lastUpdateIdx = findHeaderIndex(header, "lastupdate")
        val createdAtIdx = findHeaderIndex(header, "createdat")
        val userStateIdx = findHeaderIndex(header, "userstate")

        if (idIdx == -1 || creatorIdx == -1) return emptyList()

        val groups = mutableListOf<ThreemaBackupGroup>()
        for (i in 1 until rows.size) {
            val row = rows[i]
            if (row.isEmpty() || row.all { it.isBlank() }) continue

            val id = getCell(row, idIdx)?.trim()
            val creator = getCell(row, creatorIdx)?.trim()
            if (id.isNullOrBlank() || creator.isNullOrBlank()) continue

            groups.add(
                ThreemaBackupGroup(
                    id = id,
                    creator = creator,
                    name = getCell(row, nameIdx)?.trim()?.ifEmpty { null },
                    memberIdentities = getCell(row, membersIdx)
                        ?.split(";")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: emptyList(),
                    groupUid = getCell(row, uidIdx)?.trim()?.ifEmpty { null },
                    lastUpdate = parseTimestamp(getCell(row, lastUpdateIdx)),
                    createdAt = parseTimestamp(getCell(row, createdAtIdx)),
                    userState = getCell(row, userStateIdx)?.trim()?.toIntOrNull()
                        ?: ThreemaBackupGroup.USER_STATE_MEMBER
                )
            )
        }
        return groups
    }

    /**
     * Parses `message_<identity>.csv` content from a Threema data backup.
     * Expects columns such as `apiid`, `uid`, `isoutbox`, `identity`, `body`, `type`, `posted_at`/`createdat`, `isread`.
     */
    fun parseMessagesCsv(csv: String, conversationIdentity: String): List<ThreemaBackupMessage> {
        if (csv.isBlank()) return emptyList()
        val rows = parseCsvRows(csv)
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { cleanHeader(it) }
        val apiIdIdx = findHeaderIndex(header, "apiid", "api_id")
        val uidIdx = findHeaderIndex(header, "uid")
        val isOutboxIdx = findHeaderIndex(header, "isoutbox", "is_outbox", "outgoing")
        val identityIdx = findHeaderIndex(header, "identity")
        val bodyIdx = findHeaderIndex(header, "body", "text")
        val typeIdx = findHeaderIndex(header, "type")
        val postedAtIdx = findHeaderIndex(header, "posted_at", "postedat", "createdat", "created_at", "date", "timestamp")
        val statusIdx = findHeaderIndex(header, "isstatusmessage", "is_status_message")
        val captionIdx = findHeaderIndex(header, "caption")

        val messages = mutableListOf<ThreemaBackupMessage>()
        for (i in 1 until rows.size) {
            val row = rows[i]
            if (row.isEmpty() || row.all { it.isBlank() }) continue

            // Not a message: "X was added to the group", a call that rang, a ballot closing.
            //
            // Two checks, because one is not enough — measured in a real export. Group events
            // carry `isstatusmessage` = 1. A telephone call does not: `VOIP_STATUS` rows are
            // written with that column set to **0**, and every one of them reached her screen as
            // `[1,{"status":4,"callId":434075763}]` in the middle of her father's conversation.
            // What they have in common is the type name.
            val rawType = getCell(row, typeIdx)?.trim()
            if (isTruthy(getCell(row, statusIdx)) || isStatusType(rawType)) continue

            val rawUid = getCell(row, uidIdx)?.trim()
            val rawApiId = getCell(row, apiIdIdx)?.trim()
            // Only when it says something the conversation does not. This app's own exporter
            // writes the partner into the identity column of a private chat, where naming the
            // sender and naming the conversation are the same fact; a group file names a member,
            // which is the case this field exists for.
            val senderIdentity = getCell(row, identityIdx)
                ?.trim()
                ?.ifEmpty { null }
                ?.takeIf { !it.equals(conversationIdentity, ignoreCase = true) }

            val msgId = messageId(rawUid, rawApiId, conversationIdentity, i)

            val rawIsOutbox = getCell(row, isOutboxIdx)?.trim()
            val isOutgoing = isTruthy(rawIsOutbox)

            val rawBody = getCell(row, bodyIdx)

            // What she wrote under the photograph, when there is one: a picture sent with
            // "Schau mal, der Garten" should read as that sentence and not as "[Foto]".
            val caption = getCell(row, captionIdx)?.trim()?.ifEmpty { null }
            val text = mapMessageText(rawType, rawBody, caption)

            val rawPostedAt = getCell(row, postedAtIdx)?.trim()
            val timestamp = parseTimestamp(rawPostedAt)

            // Every imported message is stored read, whatever the backup says. This is a rule
            // about her and not about the file: a backup restored on a new phone is her history,
            // and history does not demand attention. Honouring the column instead would greet her
            // with a home screen full of things to deal with that she dealt with years ago, and
            // she has no way to tell those apart from this morning's message.
            //
            // The exporter still writes the true value, because that column belongs to Threema's
            // format and its own app reads it. What we refuse to do is act on it coming back in.
            val isRead = true

            messages.add(
                ThreemaBackupMessage(
                    id = msgId,
                    partnerIdentity = conversationIdentity,
                    text = text,
                    timestamp = timestamp,
                    isOutgoing = isOutgoing,
                    isRead = isRead,
                    type = rawType,
                    senderIdentity = senderIdentity
                )
            )
        }
        return messages
    }

    /**
     * Quoted-field CSV tokenizer supporting quoted strings, embedded newlines/commas/semicolons,
     * and escaped double quotes (`""`).
     */
    /**
     * The id of every row in a conversation file, including the ones [parseMessagesCsv] throws
     * away.
     *
     * What an import of an archive is allowed to overwrite. Everything else here is written with
     * IGNORE-on-conflict, so that re-importing a backup cannot resurrect a message she has since
     * deleted — but that also means a row an earlier, worse importer wrote is never corrected. Her
     * father's conversation kept 82 rows reading `[1,{"status":2,"callId":2945153521}]` through
     * two imports after the parser had learned to drop them, because dropping a row and never
     * touching it again are the same thing to an insert that ignores conflicts.
     *
     * So an import is authoritative for exactly the rows the archive contains: those are deleted
     * and written again from the file, and nothing else on the phone is touched. A row the parser
     * now refuses is thereby also a row the import removes.
     */
    fun parseMessageIds(csv: String, conversationIdentity: String): List<String> {
        if (csv.isBlank()) return emptyList()
        val rows = parseCsvRows(csv)
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { cleanHeader(it) }
        val apiIdIdx = findHeaderIndex(header, "apiid", "api_id")
        val uidIdx = findHeaderIndex(header, "uid")

        val ids = mutableListOf<String>()
        for (i in 1 until rows.size) {
            val row = rows[i]
            if (row.isEmpty() || row.all { it.isBlank() }) continue
            ids.add(
                messageId(
                    rawUid = getCell(row, uidIdx)?.trim(),
                    rawApiId = getCell(row, apiIdIdx)?.trim(),
                    conversationIdentity = conversationIdentity,
                    rowNumber = i
                )
            )
        }
        return ids
    }

    /**
     * What a row is filed under: its own uid, the id it travelled under, and only then its place
     * in the file.
     *
     * Shared by the two readers above so that the ids an import deletes are the same ids it goes
     * on to write. If those two ever disagreed, an import would delete somebody's message and put
     * a second copy of it back.
     */
    private fun messageId(
        rawUid: String?,
        rawApiId: String?,
        conversationIdentity: String,
        rowNumber: Int
    ): String = when {
        !rawUid.isNullOrBlank() -> rawUid
        !rawApiId.isNullOrBlank() -> rawApiId
        else -> "${conversationIdentity}_${rowNumber}"
    }

    fun parseCsvRows(csv: String): List<List<String>> {
        if (csv.isBlank()) return emptyList()
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        var currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        val length = csv.length

        // Auto-detect delimiter from first line (comma or semicolon)
        var delimiter = ','
        val firstLineEnd = csv.indexOfAny(charArrayOf('\r', '\n')).let { if (it == -1) length else it }
        val firstLine = csv.substring(0, firstLineEnd)
        if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) {
            delimiter = ';'
        }

        while (i < length) {
            val c = csv[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && csv[i + 1] == '"') {
                        currentField.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                if (c == '"') {
                    inQuotes = true
                } else if (c == delimiter) {
                    currentRow.add(currentField.toString())
                    currentField = StringBuilder()
                } else if (c == '\r') {
                    if (i + 1 < length && csv[i + 1] == '\n') {
                        i++
                    }
                    currentRow.add(currentField.toString())
                    currentField = StringBuilder()
                    rows.add(currentRow)
                    currentRow = mutableListOf()
                } else if (c == '\n') {
                    currentRow.add(currentField.toString())
                    currentField = StringBuilder()
                    rows.add(currentRow)
                    currentRow = mutableListOf()
                } else {
                    currentField.append(c)
                }
            }
            i++
        }

        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            rows.add(currentRow)
        }

        return rows
    }

    private fun cleanHeader(header: String): String {
        return header.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private fun findHeaderIndex(headers: List<String>, vararg candidates: String): Int {
        val cleanCandidates = candidates.map { cleanHeader(it) }
        return headers.indexOfFirst { cleanCandidates.contains(it) }
    }

    private fun getCell(row: List<String>, index: Int): String? {
        if (index < 0 || index >= row.size) return null
        return row[index]
    }

    /**
     * Whether a type names something that happened rather than something somebody said.
     *
     * By suffix rather than by a list of names: Threema has `VOIP_STATUS`, `GROUP_STATUS`,
     * `GROUP_CALL_STATUS` and `FORWARD_SECURITY_STATUS` today and will have more, and every one
     * of them carries a JSON body that means nothing to her.
     */
    private fun isStatusType(type: String?): Boolean =
        type?.trim()?.uppercase()?.endsWith("_STATUS") == true

    /** Threema writes booleans as 1/0; other exporters have been seen writing words. */
    private fun isTruthy(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val v = value.trim().lowercase()
        return v == "1" || v == "true" || v == "yes" || v == "y" || v == "t"
    }

    private fun mapMessageText(rawType: String?, rawBody: String?, caption: String? = null): String {
        val normalizedType = rawType?.trim()?.lowercase()
        val hasBody = !rawBody.isNullOrBlank()

        // A caption is the message: it is what the person typed, and the "[Foto]" below is only
        // there for the ones sent with nothing said.
        if (!caption.isNullOrBlank()) return caption

        return when (normalizedType) {
            "image", "photo", "picture", "2" -> "[Foto]"
            "audio", "voice", "voice_message", "voicemessage", "sound", "3" -> "[Sprachnachricht]"
            "video", "4" -> "[Video]"
            "location", "map", "6" -> "[Standort]"
            "file", "document", "5" -> "[Datei]"
            "poll", "ballot" -> "[Umfrage]"
            "1", "0", "text", "", null -> {
                if (hasBody) rawBody!! else "[Nachricht]"
            }
            else -> {
                // An unknown type, shown only if the body reads as something a person wrote. A
                // `FILE` row's body is `["<blob id>","<key>",…]` and a status row's is
                // `[1,{"status":2}]`: machinery, and she has been shown both.
                if (hasBody && !looksLikeMachinery(rawBody!!)) rawBody else "[Anhang]"
            }
        }
    }

    /** Whether this is a structure the format uses rather than a sentence somebody typed. */
    private fun looksLikeMachinery(body: String): Boolean {
        val trimmed = body.trim()
        return trimmed.startsWith("[") || trimmed.startsWith("{")
    }

    /**
     * An undatable message falls back to 0 (the epoch), never to "now". These are years-old
     * messages; dating them to the present would sort a 2018 message above everything else
     * the moment it ever reached a time-ordered list.
     */
    private fun parseTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        value.toLongOrNull()?.let {
            return if (it < 10_000_000_000L) it * 1000L else it
        }
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            try {
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                java.time.LocalDateTime.parse(value, formatter)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (e2: Exception) {
                0L
            }
        }
    }
}
