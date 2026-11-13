package ch.heuscher.gentleconnect.data.threema

import android.content.Context
import android.net.Uri
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.model.BackupPassword
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.MessageDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File

/**
 * Result of a Threema backup export attempt.
 */
sealed class ThreemaExportResult {
    data class Success(
        val password: String,
        val messageCount: Int,
        val contactCount: Int
    ) : ThreemaExportResult()

    data class Error(val message: String) : ThreemaExportResult()
}

/**
 * Android-side exporter for Threema AES-encrypted data backups.
 * Reads contacts and messages from Room, writes CSVs matching Threema's own format,
 * packages and AES-encrypts into a ZIP archive with a generated [BackupPassword],
 * and writes to the destination URI.
 */
class ThreemaBackupExporter(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context)
) {

    suspend fun exportBackup(destinationUri: Uri): ThreemaExportResult = withContext(Dispatchers.IO) {
        val timeTag = System.currentTimeMillis()
        val stagingDir = File(context.cacheDir, "threema_export_staging_$timeTag")
        val cacheZipFile = File(context.cacheDir, "threema_export_$timeTag.zip")

        try {
            stagingDir.mkdirs()

            // 1. Fetch contacts from Room & write contacts.csv
            val contactEntities = database.threemaContactDao().getAllContacts()
            val backupContacts = contactEntities.map { entity ->
                ThreemaBackupContact(
                    identity = entity.identity,
                    firstName = entity.firstName,
                    lastName = entity.lastName,
                    nickname = entity.nickname
                )
            }
            val contactsCsvText = ThreemaBackupParser.writeContactsCsv(backupContacts)
            File(stagingDir, "contacts.csv").writeText(contactsCsvText, Charsets.UTF_8)

            // 2. Copy contact avatars/photos if present
            contactEntities.forEach { contact ->
                val photoPath = contact.photoPath
                if (!photoPath.isNullOrBlank()) {
                    val photoFile = File(photoPath)
                    if (photoFile.exists() && photoFile.isFile) {
                        photoFile.copyTo(File(stagingDir, "${contact.identity}.jpg"), overwrite = true)
                    }
                }
            }

            // 3. Fetch messages from Room (both imported_messages and messages tables)
            val importedEntities = database.importedMessageDao().getAllMessages()
            val storedEntities = database.messageDao().getAllMessages()

            val allBackupMessages = mutableListOf<ThreemaBackupMessage>()
            val seenMessageIds = HashSet<String>()

            // Messages table (stored messages from runtime)
            storedEntities.forEach { stored ->
                if (stored.transport == BridgeTransport.THREEMA.name ||
                    stored.transport == "THREEMA" ||
                    (stored.counterpartId != null && stored.counterpartId.length == 8 && stored.counterpartId.all { it.isLetterOrDigit() })
                ) {
                    val partnerId = stored.counterpartId?.trim()?.uppercase() ?: return@forEach
                    if (seenMessageIds.add(stored.id)) {
                        allBackupMessages.add(
                            ThreemaBackupMessage(
                                id = stored.id,
                                partnerIdentity = partnerId,
                                text = stored.text,
                                timestamp = stored.timestamp,
                                isOutgoing = stored.direction == MessageDirection.OUTGOING.name,
                                isRead = stored.isRead,
                                type = "text"
                            )
                        )
                    }
                }
            }

            // Imported messages table
            importedEntities.forEach { imported ->
                if (seenMessageIds.add(imported.id)) {
                    allBackupMessages.add(
                        ThreemaBackupMessage(
                            id = imported.id,
                            partnerIdentity = imported.partnerIdentity.trim().uppercase(),
                            text = imported.text,
                            timestamp = imported.timestamp,
                            isOutgoing = imported.isOutgoing,
                            isRead = imported.isRead,
                            type = "text"
                        )
                    )
                }
            }

            // Group messages by partner identity & write message_<identity>.csv files
            val messagesByPartner = allBackupMessages.groupBy { it.partnerIdentity }
            for ((partnerIdentity, msgs) in messagesByPartner) {
                val csvText = ThreemaBackupParser.writeMessagesCsv(msgs.sortedBy { it.timestamp })
                File(stagingDir, "message_$partnerIdentity.csv").writeText(csvText, Charsets.UTF_8)
            }

            // 4. Generate password chosen by the app
            val password = BackupPassword.generate()

            // 5. AES-encrypt into ZIP via zip4j
            val zipParameters = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                compressionMethod = CompressionMethod.DEFLATE
            }

            val zipFile = ZipFile(cacheZipFile, password.toCharArray())
            val filesToZip = stagingDir.listFiles()?.toList().orEmpty()
            for (file in filesToZip) {
                if (file.isFile) {
                    zipFile.addFile(file, zipParameters)
                } else if (file.isDirectory) {
                    zipFile.addFolder(file, zipParameters)
                }
            }

            // 6. Write out to destination SAF Uri
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                cacheZipFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext ThreemaExportResult.Error("Could not write to destination backup file")

            ThreemaExportResult.Success(
                password = password,
                messageCount = allBackupMessages.size,
                contactCount = backupContacts.size
            )
        } catch (e: Exception) {
            ThreemaExportResult.Error(e.message ?: "Export failed")
        } finally {
            // Guarantee full cleanup of unencrypted plaintext staging files and cached ZIP
            cacheZipFile.delete()
            stagingDir.deleteRecursively()
        }
    }
}
