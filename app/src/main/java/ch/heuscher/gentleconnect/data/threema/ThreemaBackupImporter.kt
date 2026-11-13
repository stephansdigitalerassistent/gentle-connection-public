package ch.heuscher.gentleconnect.data.threema

import android.content.Context
import android.net.Uri
import ch.heuscher.gentleconnect.data.ConversationKey
import ch.heuscher.gentleconnect.data.StoredMessageMapper
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.data.db.ImportedMessageEntity
import ch.heuscher.gentleconnect.data.db.ThreemaContactEntity
import ch.heuscher.gentleconnect.data.db.ThreemaGroupMemberEntity
import ch.heuscher.gentleconnect.data.db.ThreemaGroupEntity
import ch.heuscher.gentleconnect.model.Attachment
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageArchive
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.SendState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File

/**
 * Result of a Threema backup import attempt.
 */
sealed class ThreemaImportResult {
    data class Success(
        val restoredCount: Int = 0,
        val totalCount: Int = 0,
        /**
         * The Threema ID this backup also put back on the phone, when it carried one.
         *
         * A data backup contains an `identity` entry holding the same ID-backup string the real
         * Threema app exports, openable with the same password — so the file that restores her
         * conversations can restore the account that continues them. Null when the archive had no
         * such entry, or when the phone already held a *different* account: replacing that
         * silently would disconnect her from everyone.
         */
        val restoredThreemaId: String? = null
    ) : ThreemaImportResult()
    data object WrongPassword : ThreemaImportResult()
    data class Error(val message: String) : ThreemaImportResult()
}

/**
 * Android-side importer for Threema AES-encrypted data backups.
 * Decrypts, extracts, parses contacts/messages, reconciles read state and new messages
 * via [MessageArchive], persists into Room, and cleans up all unencrypted files.
 */
class ThreemaBackupImporter(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context),
    /**
     * How the account inside the archive gets put back, when there is one. Optional so that the
     * message import stays testable and usable without a bridge binary behind it.
     */
    private val accountTransfer: ThreemaAccountTransfer? = null
) {

    suspend fun importBackup(uri: Uri, password: String): ThreemaImportResult = withContext(Dispatchers.IO) {
        val timeTag = System.currentTimeMillis()
        val cacheZipFile = File(context.cacheDir, "threema_backup_$timeTag.zip")
        val extractDir = File(context.cacheDir, "threema_extract_$timeTag")
        val photosDir = File(context.filesDir, "threema_photos")

        try {
            // 1. Copy document from SAF Uri to local cache zip file
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                cacheZipFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext ThreemaImportResult.Error("Could not open backup file URI")

            // 2. AES Decryption & extraction via zip4j
            extractDir.mkdirs()
            val zipFile = ZipFile(cacheZipFile)
            if (zipFile.isEncrypted) {
                zipFile.setPassword(password.toCharArray())
            }

            try {
                zipFile.extractAll(extractDir.absolutePath)
            } catch (e: ZipException) {
                val msg = e.message ?: ""
                val isWrongPassword = e.type == ZipException.Type.WRONG_PASSWORD ||
                        msg.contains("password", ignoreCase = true) ||
                        msg.contains("checksum", ignoreCase = true) ||
                        msg.contains("crc", ignoreCase = true)
                if (isWrongPassword) {
                    return@withContext ThreemaImportResult.WrongPassword
                }
                return@withContext ThreemaImportResult.Error(msg.ifEmpty { "ZIP extraction error" })
            } catch (e: Exception) {
                return@withContext ThreemaImportResult.Error(e.message ?: "Extraction failed")
            }

            // 3. Find & parse contacts.csv, and the groups she is in
            val contactsFile = findFileByName(extractDir, "contacts.csv")
            val importedContacts = if (contactsFile != null && contactsFile.exists()) {
                ThreemaBackupParser.parseContactsCsv(contactsFile.readText(Charsets.UTF_8))
            } else {
                emptyList()
            }

            val groupsFile = findFileByName(extractDir, "groups.csv")
            val importedGroups = if (groupsFile != null && groupsFile.exists()) {
                ThreemaBackupParser.parseGroupsCsv(groupsFile.readText(Charsets.UTF_8))
            } else {
                emptyList()
            }

            // What the files are named after, and what the names on her screen come from.
            //
            // Threema names a conversation's file after its *row number* — her chat with TSTMBR09
            // is `message_0008473196.csv` — so without this map every restored conversation is
            // filed under a number that matches no contact, no name and no photograph. A real
            // import of 7,301 messages landed that way before this existed.
            val identityByRowNumber = importedContacts
                .mapNotNull { contact -> contact.identityId?.let { it to contact.identity } }
                .toMap()
            val nameByIdentity = importedContacts.associate { it.identity to it.displayName }
            val groupByRowNumber = importedGroups
                .mapNotNull { group -> group.groupUid?.let { it to group } }
                .toMap()

            // 4. Save contact photos to app storage if present in backup archive
            photosDir.mkdirs()
            val contactEntities = importedContacts.map { contact ->
                var photoPath: String? = null
                // `contact_profile_pic_<row number>` is what a real archive calls a face, with
                // no extension on it — the same row number the message files are named after,
                // not the Threema ID. Every name below it is a guess kept for other exporters,
                // and those guesses were all this looked for: her address book came back from a
                // backup full of initials.
                val candidates = listOfNotNull(
                    contact.identityId?.let { "contact_profile_pic_$it" },
                    contact.identityId?.let { "contact_avatar_$it" },
                    "${contact.identity}.jpg",
                    "${contact.identity}.png",
                    "${contact.identity}.jpeg",
                    "avatar_${contact.identity}.jpg",
                    "contact_${contact.identity}.jpg"
                )
                val photoFile = candidates.mapNotNull { findFileByName(extractDir, it) }.firstOrNull()
                if (photoFile != null && photoFile.exists()) {
                    val destPhoto = File(photosDir, "${contact.identity}.jpg")
                    photoFile.copyTo(destPhoto, overwrite = true)
                    photoPath = destPhoto.absolutePath
                }

                ThreemaContactEntity(
                    identity = contact.identity,
                    firstName = contact.firstName,
                    lastName = contact.lastName,
                    nickname = contact.nickname,
                    photoPath = photoPath
                )
            }

            // The pictures, films and voice messages, indexed by the message they belong to.
            //
            // A backup names them after the message's own uid — `message_media_<uid>`, and
            // `group_message_media_<uid>` in a group — with no extension and no other clue, so
            // this map is the only thing that connects half a gigabyte of files to the sentences
            // they were sent with. Without it her photographs stayed in the archive: 1,500 files
            // extracted, read past, and deleted again with the temporary directory.
            val mediaByMessageId = HashMap<String, File>()
            extractDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val id = when {
                    file.name.startsWith(GROUP_MEDIA_PREFIX) -> file.name.removePrefix(GROUP_MEDIA_PREFIX)
                    file.name.startsWith(MEDIA_PREFIX) -> file.name.removePrefix(MEDIA_PREFIX)
                    else -> null
                }
                if (!id.isNullOrBlank()) mediaByMessageId[id] = file
            }
            val mediaDir = File(context.filesDir, "threema_media")

            // 5. Parse the message files — and only the message files.
            //
            // This used to take every .csv in the archive that was not contacts or groups, which
            // in a real backup means `nonces.csv`, `ballot.csv`, `reaction_counts.csv` and the
            // rest: of one import's 7,301 "messages", 4,564 were nonces. A message file is named
            // after the conversation it holds and nothing else is.
            val incomingEntities = mutableListOf<ImportedMessageEntity>()
            val incomingDomainMessages = mutableListOf<Message>()
            // Every row the archive holds, whether or not it is a message — what this import is
            // allowed to overwrite, and the only thing it is allowed to remove. See
            // ThreemaBackupParser.parseMessageIds.
            val archiveMessageIds = mutableListOf<String>()

            for (msgFile in findPrivateMessageCsvFiles(extractDir)) {
                val rowNumber = msgFile.name.removePrefix("message_").removeSuffix(".csv")
                val identity = identityByRowNumber[rowNumber] ?: rowNumber
                val conversationId = ConversationKey.of(threemaId = identity) ?: continue
                val partnerName = nameByIdentity[identity] ?: identity
                val csv = msgFile.readText(Charsets.UTF_8)
                archiveMessageIds.addAll(ThreemaBackupParser.parseMessageIds(csv, identity))

                for (parsed in ThreemaBackupParser.parseMessagesCsv(csv, identity)) {
                    incomingEntities.add(parsed.toEntity(identity))
                    incomingDomainMessages.add(
                        parsed.toMessage(
                            conversationId = conversationId,
                            senderIdentity = identity,
                            senderName = partnerName,
                            media = keepMedia(mediaByMessageId[parsed.id], parsed.id, mediaDir)
                        )
                    )
                }
            }

            for (msgFile in findGroupMessageCsvFiles(extractDir)) {
                val rowNumber = msgFile.name.removePrefix("group_message_").removeSuffix(".csv")
                // A group whose own row is missing from groups.csv cannot be keyed, and a group
                // message filed under anything else is a private conversation with a stranger.
                val group = groupByRowNumber[rowNumber] ?: continue
                val groupIdentity = GroupIdentity.of(group.creator, group.id) ?: continue
                val conversationId = ConversationKey.of(groupIdentity = groupIdentity) ?: continue
                val csv = msgFile.readText(Charsets.UTF_8)
                archiveMessageIds.addAll(ThreemaBackupParser.parseMessageIds(csv, group.id))

                for (parsed in ThreemaBackupParser.parseMessagesCsv(csv, group.id)) {
                    val sender = parsed.senderIdentity
                    incomingEntities.add(parsed.toEntity(group.id))
                    incomingDomainMessages.add(
                        parsed.toMessage(
                            conversationId = conversationId,
                            senderIdentity = sender ?: group.id,
                            senderName = sender?.let { nameByIdentity[it] ?: it } ?: (group.name ?: group.id),
                            groupIdentity = groupIdentity,
                            groupName = group.name,
                            media = keepMedia(mediaByMessageId[parsed.id], parsed.id, mediaDir)
                        )
                    )
                }
            }

            // 6. Fetch existing messages from Room to reconcile via MessageArchive — after
            // clearing out what an earlier version of this importer filed as messages and never
            // were. See ImportedMessageDao.deleteByPartners.
            database.importedMessageDao().deleteByPartners(NOT_CONVERSATIONS)
            database.importedMessageDao().deleteFileNamedPartners()
            val existingImported = database.importedMessageDao().getAllMessages()
            val existingStored = database.messageDao().getAllMessages()

            val existingDomainMessages = ArrayList<Message>(existingImported.size + existingStored.size)
            existingImported.forEach {
                existingDomainMessages.add(
                    Message(
                        id = it.id,
                        senderName = it.partnerIdentity,
                        senderId = it.partnerIdentity,
                        senderAddress = it.partnerIdentity,
                        text = it.text,
                        timestamp = it.timestamp,
                        sourcePackage = "",
                        isRead = it.isRead,
                        transport = BridgeTransport.THREEMA,
                        channel = MessageChannel.ON_DEVICE,
                        direction = if (it.isOutgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                        sendState = if (it.isOutgoing) SendState.SENT else null,
                        conversationId = ConversationKey.of(threemaId = it.partnerIdentity)
                    )
                )
            }
            existingStored.forEach {
                existingDomainMessages.add(StoredMessageMapper.toMessage(it))
            }

            // Count how many messages the backup will actually add
            val wouldRestoreMessages = MessageArchive.wouldRestore(
                onThePhone = existingDomainMessages,
                fromTheBackup = incomingDomainMessages
            )

            // Merge messages to guarantee monotonic read state: what she has read stays read
            val mergedMessages = MessageArchive.merge(
                onThePhone = existingDomainMessages,
                fromTheBackup = incomingDomainMessages
            )
            val mergedById = mergedMessages.associateBy { it.id }

            val entitiesToInsert = incomingEntities.map { entity ->
                val mergedMsg = mergedById[entity.id]
                if (mergedMsg != null) {
                    entity.copy(isRead = mergedMsg.isRead)
                } else {
                    entity
                }
            }

            // 7. Persist to Room — after taking out what this same archive put here before.
            //
            // What the archive says about its own rows is the truth, and what an earlier import
            // made of them is not. Deleting them first is what lets a fixed parser reach a
            // conversation it has already been through: her father's held 82 telephone calls
            // written as `[1,{"status":2,...}]` that survived every later import, because an
            // insert that ignores conflicts cannot correct a row and a parser that drops a row
            // cannot remove one.
            //
            // Narrow on purpose. Only ids that are in the file are touched, so a message that
            // reached her some other way is left exactly where it is — see
            // ThreemaBackupParser.parseMessageIds.
            archiveMessageIds.chunked(SQLITE_VARIABLES).forEach { ids ->
                database.messageDao().deleteByIds(ids)
                database.importedMessageDao().deleteByIds(ids)
            }

            database.threemaContactDao().upsertContacts(contactEntities)
            database.importedMessageDao().insertMessages(entitiesToInsert)

            // The groups themselves, so a restored group conversation has a name on it rather
            // than a hexadecimal id.
            val groupPhotosDir = File(context.filesDir, "threema_group_photos")
            for (group in importedGroups) {
                val groupIdentity = GroupIdentity.of(group.creator, group.id) ?: continue

                // `group_avatar_<row number>`, named after groups.csv's own id like everything
                // else in the archive. A group row has never had a picture on it in this app; this
                // is the picture it should have had.
                val avatarPath = group.groupUid
                    ?.let { findFileByName(extractDir, "$GROUP_AVATAR_PREFIX$it") }
                    ?.let { avatar ->
                        keepMedia(
                            source = avatar,
                            messageId = "${groupIdentity.creatorIdentity}_${groupIdentity.groupId}",
                            mediaDir = groupPhotosDir
                        )?.takeIf { it.kind.isPicture }?.path
                    }

                database.threemaGroupDao().insertGroupIfNew(
                    ThreemaGroupEntity(
                        creatorIdentity = groupIdentity.creatorIdentity,
                        groupId = groupIdentity.groupId,
                        name = group.name,
                        photoPath = avatarPath
                    )
                )
                if (avatarPath != null) {
                    // The insert IGNOREs a group already known from live traffic; the picture has
                    // to reach that row too.
                    database.threemaGroupDao().updatePhoto(
                        groupIdentity.creatorIdentity,
                        groupIdentity.groupId,
                        avatarPath
                    )
                }
                if (group.name != null) {
                    database.threemaGroupDao().updateName(
                        groupIdentity.creatorIdentity,
                        groupIdentity.groupId,
                        group.name
                    )
                }
                // Who is in the group, and — the part that was missing — how old that knowledge
                // is.
                //
                // Whether she may answer several people at once is gated on knowing the membership
                // authoritatively: GroupReplyPolicy refuses any group whose lastSetupAt is 0,
                // because a fan-out short by one member reaches everyone but that one and looks
                // exactly like success. Live, only a setup message from the creator lifts that.
                // A restored group therefore sat with its full member list next to a timestamp of
                // 0 and was unanswerable for ever: the members were right there and the app
                // refused to believe them.
                //
                // The archive is not a guess. Threema's own BackupService writes the member list
                // with her own identity in it exactly when she is a member, and `user_state` says
                // whether she still is. What it cannot know is anything that happened after the
                // backup was written, so the date recorded here is the archive's own last_update
                // and never the time of the import, and a setup message that has arrived since
                // wins outright — restoring an old archive must not put somebody the family has
                // removed back into the fan-out of her next reply.
                val knownAt = group.membershipKnownAt(timeTag)
                val alreadyKnownAt = database.threemaGroupDao()
                    .lastSetupAt(groupIdentity.creatorIdentity, groupIdentity.groupId) ?: 0L
                if (group.memberIdentities.isNotEmpty() && knownAt > alreadyKnownAt) {
                    // Replaced rather than merged, for the same reason the live setup replaces:
                    // merging would keep a removed member in the group here for ever.
                    database.threemaGroupDao().clearMembers(
                        groupIdentity.creatorIdentity,
                        groupIdentity.groupId
                    )
                    database.threemaGroupDao().insertMembers(
                        group.memberIdentities.map { member ->
                            ThreemaGroupMemberEntity(
                                creatorIdentity = groupIdentity.creatorIdentity,
                                groupId = groupIdentity.groupId,
                                identity = member.uppercase()
                            )
                        }
                    )
                    // Dated even when she has left the group or was removed from it, because that
                    // is itself the last thing we were told about the group rather than an absence
                    // of anything. Left at 0, a group she is no longer in would be indistinguishable
                    // from one nobody ever told us about, and the caregiver's log would say "we do
                    // not know who is in this group" about a group we know exactly where she stands
                    // in.
                    //
                    // Safe because the two facts are one fact: Threema puts her own identity in the
                    // member list precisely when `user_state` is 0 — GroupServiceImpl.isGroupMember
                    // *is* that comparison — so an archive that says she has left never lists her,
                    // and GroupReplyPolicy then refuses with NO_LONGER_A_MEMBER, which is true, in
                    // place of MEMBERSHIP_UNKNOWN, which is not.
                    database.threemaGroupDao().updateLastSetupAt(
                        groupIdentity.creatorIdentity,
                        groupIdentity.groupId,
                        knownAt
                    )
                }
            }

            // Into the store her screens actually read.
            //
            // Everything above writes `imported_messages`, which nothing displays: the app reads
            // `messages`, through ConversationRepository. A backup restored before this went into
            // the database in full and appeared nowhere at all — 7,301 rows on the phone and an
            // empty Messages tab. Inserted IGNORE-on-conflict, so re-importing the same backup
            // adds nothing and cannot resurrect a message she has since deleted or re-read.
            val storedEntities = incomingDomainMessages.mapNotNull { message ->
                val conversationId = message.conversationId ?: return@mapNotNull null
                // The merge decides only whether it counts as read; where it belongs is decided
                // here, by the file it came out of.
                val isRead = mergedById[message.id]?.isRead ?: message.isRead
                StoredMessageMapper.toEntity(message.copy(isRead = isRead), conversationId)
            }
            if (storedEntities.isNotEmpty()) {
                database.messageDao().insertIfNew(storedEntities)

                // The insert IGNOREs a message that is already stored, which is what stops a
                // re-import undoing a read state — and would also have left every photograph off
                // every message imported before the pictures were. Attaching them by id afterwards
                // is the only way they reach a row that already exists.
                for (entity in storedEntities) {
                    entity.photoPath?.let { database.messageDao().attachPhoto(entity.id, it) }
                    entity.audioPath?.let { database.messageDao().attachAudio(entity.id, it) }
                    entity.videoPath?.let { database.messageDao().attachVideo(entity.id, it) }
                }
            }

            // 8. The account, if the archive carries one.
            val restoredThreemaId = restoreAccount(extractDir, password)

            ThreemaImportResult.Success(
                restoredCount = wouldRestoreMessages.size,
                totalCount = incomingEntities.size,
                restoredThreemaId = restoredThreemaId
            )
        } catch (e: Exception) {
            ThreemaImportResult.Error(e.message ?: "Import failed")
        } finally {
            // Guarantee full cleanup of unencrypted plaintext extracted files and cached ZIP copy
            cacheZipFile.delete()
            extractDir.deleteRecursively()
        }
    }

    private fun findFileByName(dir: File, fileName: String): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        return dir.walkTopDown().firstOrNull { it.isFile && it.name.equals(fileName, ignoreCase = true) }
    }

    /**
     * What one message's attachment is, and where it now lives on the phone.
     *
     * Kept out of the archive's temporary directory, which is deleted the moment the import
     * finishes — a path into it would be a photograph that exists for as long as the progress
     * spinner and no longer.
     *
     * The kind is decided by looking at the file rather than by the type column, because the
     * column says FILE for a great many things that are in fact photographs, and a picture filed
     * as a document is a picture she cannot see.
     */
    private fun keepMedia(source: File?, messageId: String, mediaDir: File): ImportedMedia? {
        if (source == null || !source.isFile || source.length() == 0L) return null
        val kind = mediaKind(source) ?: return null

        return try {
            mediaDir.mkdirs()
            val destination = File(mediaDir, "$messageId.${kind.extension}")
            source.copyTo(destination, overwrite = true)
            ImportedMedia(kind, destination.absolutePath)
        } catch (_: Exception) {
            // One unreadable attachment is not a reason to fail an import of her messages.
            null
        }
    }

    /** Read the first bytes and say what they are, or null for something nothing here can show. */
    private fun mediaKind(file: File): MediaKind? {
        val head = ByteArray(MEDIA_SNIFF_BYTES)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (_: Exception) {
            return null
        }
        if (read < 4) return null

        fun matches(vararg bytes: Int): Boolean =
            bytes.withIndex().all { (index, byte) -> index < read && head[index] == byte.toByte() }

        // "ftyp" at offset 4 is the MPEG-4 family: films, and the m4a a voice message arrives as.
        val isMp4Family = read >= 12 &&
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        val mp4Brand = if (isMp4Family) String(head, 8, 4, Charsets.US_ASCII) else ""

        return when {
            matches(0xFF, 0xD8, 0xFF) -> MediaKind.PHOTO_JPEG
            matches(0x89, 0x50, 0x4E, 0x47) -> MediaKind.PHOTO_PNG
            matches(0x47, 0x49, 0x46, 0x38) -> MediaKind.PHOTO_GIF
            matches(0x4F, 0x67, 0x67, 0x53) -> MediaKind.VOICE_OGG
            // A voice message and a film are the same container: MP4. The brand is not enough to
            // tell them apart — Threema writes plain `isom` on both — and guessing "film" for
            // everything that is not stamped `M4A` gave her a voice message with a picture to
            // watch and nothing to listen to. What actually differs is the track inside, which
            // the header names: a `vide` handler or a `soun` one.
            isMp4Family && hasHandler(head, read, VIDEO_HANDLER) -> MediaKind.FILM
            isMp4Family && hasHandler(head, read, SOUND_HANDLER) -> MediaKind.VOICE_M4A
            isMp4Family && mp4Brand.startsWith("M4A") -> MediaKind.VOICE_M4A
            isMp4Family -> MediaKind.FILM
            else -> null
        }
    }

    /**
     * Whether an MP4 header names a track of this kind, e.g. `vide` or `soun`.
     *
     * Looked for in the first few kilobytes rather than by walking the box tree: the `moov` box a
     * phone writes sits at one end of the file or the other, and when it is at the far end there
     * is nothing to find here — which is why the brand check stays behind this as a fallback.
     */
    private fun hasHandler(head: ByteArray, read: Int, handler: ByteArray): Boolean {
        if (read < handler.size) return false
        outer@ for (start in 0..read - handler.size) {
            for (offset in handler.indices) {
                if (head[start + offset] != handler[offset]) continue@outer
            }
            return true
        }
        return false
    }

    /** `message_<row number>.csv`, and nothing that merely looks like it. */
    private fun findPrivateMessageCsvFiles(dir: File): List<File> =
        findCsvFilesMatching(dir, PRIVATE_MESSAGE_FILE)

    /** `group_message_<row number>.csv`. */
    private fun findGroupMessageCsvFiles(dir: File): List<File> =
        findCsvFilesMatching(dir, GROUP_MESSAGE_FILE)

    private fun findCsvFilesMatching(dir: File, pattern: Regex): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && pattern.matches(it.name) }
            .toList()
    }

    /**
     * Put the account in the archive back on this phone.
     *
     * The `identity` entry of a data backup is the same ID-backup string Threema's own app
     * exports, encrypted with the same password as the archive — so restoring her history and
     * restoring the account that continues it are one gesture rather than two.
     *
     * Refuses quietly in every uncertain case: no entry, no bridge, a different account already
     * on the phone. None of those is a reason to fail an import of her messages, and replacing a
     * live account is never something to do as a side effect.
     */
    private suspend fun restoreAccount(extractDir: File, password: String): String? {
        val transfer = accountTransfer ?: return null
        val identityFile = findFileByName(extractDir, "identity") ?: return null
        val backupString = identityFile.readText(Charsets.UTF_8).trim()
        if (backupString.isEmpty()) return null

        return when (val outcome = transfer.import(backup = backupString, password = password)) {
            is ThreemaAccountTransfer.Import.Restored -> outcome.threemaId
            // Already hers: nothing was written and nothing needs saying.
            is ThreemaAccountTransfer.Import.Unchanged -> null
            is ThreemaAccountTransfer.Import.WouldReplace -> null
            is ThreemaAccountTransfer.Import.Failed -> null
        }
    }

    private companion object {
        /**
         * Partner names that can only have come from reading a file that was not a conversation.
         * Each is the name of a `.csv` in a Threema archive, minus the extension.
         */
        val NOT_CONVERSATIONS = listOf(
            "nonces",
            "nonces_d2d",
            "nonce_counts",
            "ballot",
            "ballot_choice",
            "ballot_vote",
            "reaction_counts",
            "contact_reactions",
            "group_reactions",
            "distribution_list"
        )

        /**
         * How many ids to name in one `DELETE ... IN (...)`.
         *
         * SQLite refuses a statement with more than 999 bound values, and a real archive holds
         * thousands of messages.
         */
        const val SQLITE_VARIABLES = 500

        const val GROUP_AVATAR_PREFIX = "group_avatar_"

        /** Enough of a file to recognise it, and to reach the track names in an MP4 header. */
        const val MEDIA_SNIFF_BYTES = 4096
        val VIDEO_HANDLER = "vide".toByteArray(Charsets.US_ASCII)
        val SOUND_HANDLER = "soun".toByteArray(Charsets.US_ASCII)
        const val MEDIA_PREFIX = "message_media_"
        const val GROUP_MEDIA_PREFIX = "group_message_media_"

        val PRIVATE_MESSAGE_FILE = Regex("""message_[^/]+\.csv""", RegexOption.IGNORE_CASE)
        val GROUP_MESSAGE_FILE = Regex("""group_message_[^/]+\.csv""", RegexOption.IGNORE_CASE)
    }
}

/**
 * One parsed row as the table of imported messages keeps it.
 *
 * [conversation] rather than "partner": for a group it is the group, and that distinction is the
 * whole of what went wrong when the two were the same field.
 */
private fun ThreemaBackupMessage.toEntity(conversation: String) = ImportedMessageEntity(
    id = id,
    partnerIdentity = conversation,
    text = text,
    timestamp = timestamp,
    isOutgoing = isOutgoing,
    isRead = isRead
)

/** The same row as her screens know a message: filed, named, and attributed to somebody. */
private fun ThreemaBackupMessage.toMessage(
    conversationId: String,
    senderIdentity: String,
    senderName: String,
    groupIdentity: GroupIdentity? = null,
    groupName: String? = null,
    media: ImportedMedia? = null
) = Message(
    id = id,
    senderName = senderName,
    senderId = senderIdentity,
    senderAddress = senderIdentity,
    // The archive calls a photograph, a film and a voice message all "[Datei]", because Threema
    // files all three under one type; what it is was only learned by reading the file's first
    // bytes, a few lines above this. Saying so here is what takes the word off the picture —
    // see Attachment.retoken.
    text = when {
        media == null -> text
        media.kind.isPicture -> Attachment.retoken(text, Attachment.PHOTO)
        media.kind.isVoice -> Attachment.retoken(text, Attachment.VOICE)
        else -> Attachment.retoken(text, Attachment.VIDEO)
    },
    timestamp = timestamp,
    sourcePackage = "",
    isRead = isRead,
    transport = BridgeTransport.THREEMA,
    channel = MessageChannel.ON_DEVICE,
    direction = if (isOutgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
    sendState = if (isOutgoing) SendState.SENT else null,
    conversationId = conversationId,
    groupIdentity = groupIdentity,
    groupName = groupName,
    photoPath = media?.path?.takeIf { media.kind.isPicture },
    audioPath = media?.path?.takeIf { media.kind.isVoice },
    videoPath = media?.path?.takeIf { media.kind == MediaKind.FILM },
    // Her own history, not something that has just happened: see ThreemaBackupParser, which
    // refuses to bring unread state back in with it.
    receivedAt = timestamp
)

/** An attachment from the archive, once it is somewhere it will still be tomorrow. */
private data class ImportedMedia(val kind: MediaKind, val path: String)

/** What an attachment turned out to be, decided by its first bytes. */
private enum class MediaKind(val extension: String) {
    PHOTO_JPEG("jpg"),
    PHOTO_PNG("png"),
    PHOTO_GIF("gif"),
    VOICE_OGG("ogg"),
    VOICE_M4A("m4a"),
    FILM("mp4");

    val isPicture: Boolean get() = this == PHOTO_JPEG || this == PHOTO_PNG || this == PHOTO_GIF
    val isVoice: Boolean get() = this == VOICE_OGG || this == VOICE_M4A
}
