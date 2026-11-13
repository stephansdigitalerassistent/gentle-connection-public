package ch.heuscher.gentleconnect.data.mail

import ch.heuscher.gentleconnect.model.MailAttachment
import ch.heuscher.gentleconnect.model.MailAttachments
import java.io.File
import java.io.InputStream

/**
 * Where what came with a letter is put so it is still there tomorrow.
 *
 * An interface because [GmailApiFetcher] holds no Context and must not start holding one: every
 * JavaMail type stays inside that class, and every Android type stays outside it. This is the one
 * thing that crosses, and it crosses as bytes and a name.
 *
 * Returning null means "not kept", and it is the ordinary answer, not a fault: most of what a
 * letter carries is a logo. The caller writes down whatever comes back and never asks why.
 */
interface MailAttachmentStore {

    /**
     * Saves one part of a letter, or declines it.
     *
     * [isInline] is the sender's own claim that this belongs inside the letter rather than beside
     * it — which is what a logo says about itself. See [MailAttachments.isWorthKeeping].
     */
    fun save(
        mailId: String,
        fileName: String?,
        mimeType: String?,
        isInline: Boolean,
        content: InputStream
    ): MailAttachment?
}

/**
 * The real store: app-private files, one folder per letter.
 *
 * Takes a directory rather than a Context, so the whole thing can be exercised on a JVM in CI —
 * the same reason the rules it applies live in the model. The only Android-shaped thing about
 * saving a file is where the directory came from.
 *
 * A file is written at a path derived from the letter and the name, so a letter fetched twice —
 * which happens whenever the mailbox resets its UIDs — overwrites its own files instead of
 * growing a second copy of every photograph she has been sent.
 */
class FileMailAttachmentStore(
    private val root: File,
    /**
     * How much of her phone all of this may occupy together. Not a cache in the sense that it can
     * be refilled: a letter is never fetched again once it is stored, so a file deleted here is
     * gone for good and the letter goes back to being its words. That is why the budget is
     * generous and the oldest letters are the ones that lose theirs.
     */
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES
) : MailAttachmentStore {

    override fun save(
        mailId: String,
        fileName: String?,
        mimeType: String?,
        isInline: Boolean,
        content: InputStream
    ): MailAttachment? {
        val safeName = MailAttachments.safeFileName(fileName)
        val kind = MailAttachments.kindOf(mimeType, safeName) ?: return null

        return try {
            val folder = File(root, MailAttachments.folderNameFor(mailId))
            if (!folder.isDirectory && !folder.mkdirs()) return null

            val file = File(folder, safeName)
            // One byte over the limit is enough to know it is over it, and stopping there means a
            // forty-megabyte film is never written to her phone at all.
            val written = copyCapped(content, file, MailAttachments.MAX_BYTES + 1)

            if (!MailAttachments.isWorthKeeping(kind, isInline, written)) {
                file.delete()
                // An empty folder is left behind only if this was the letter's only part, and the
                // next sweep takes it. Deleting it here would race the part being saved next.
                return null
            }

            prune()

            MailAttachment(
                fileName = safeName,
                mimeType = mimeType?.substringBefore(';')?.trim().orEmpty(),
                path = file.absolutePath
            )
        } catch (e: Throwable) {
            // Out of space, a name the file system will not take, a stream that died halfway. All
            // of them mean the letter arrives with its words and nothing else, which is what every
            // letter did before this existed.
            null
        }
    }

    /**
     * Copies at most [cap] bytes and reports how many there were.
     *
     * The count is what the size rules are then applied to, because the size a mail server
     * declares is the size of the *encoded* part — base64 is a third larger than what it carries,
     * and a part that declares nothing at all reports -1. What is on disk is the only number that
     * is true.
     */
    private fun copyCapped(content: InputStream, file: File, cap: Long): Long {
        var written = 0L
        file.outputStream().use { out ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = content.read(buffer)
                if (read <= 0) break
                written += read
                if (written > cap) return written
                out.write(buffer, 0, read)
            }
        }
        return written
    }

    /**
     * Keeps the whole store under its budget by giving up the oldest letters' files first.
     *
     * Oldest rather than largest, because the alternative is that the one letter with a
     * photograph of her family in it is the first thing deleted for being a photograph.
     */
    private fun prune() {
        val folders = root.listFiles()?.filter { it.isDirectory } ?: return
        val measured = folders.map { folder ->
            MailAttachmentPruning.Folder(
                name = folder.name,
                lastModified = folder.lastModified(),
                bytes = folder.listFiles()?.sumOf { it.length() } ?: 0L
            )
        }
        val doomed = MailAttachmentPruning.foldersToDelete(measured, budgetBytes).toSet()
        if (doomed.isEmpty()) return
        folders.filter { it.name in doomed }.forEach { it.deleteRecursively() }
    }

    companion object {
        /** The name of the directory under `filesDir`, so one place decides it. */
        const val DIRECTORY = "mail-attachments"

        private const val DEFAULT_BUDGET_BYTES = 96L * 1024 * 1024
    }
}

/**
 * Which letters give up their files when the store is over budget.
 *
 * Pure, and separate from the store, so CI can prove the rule without a file system: the failure
 * this guards against is a phone that fills up quietly, and it is not one anybody would notice
 * from a screenshot.
 */
object MailAttachmentPruning {

    data class Folder(val name: String, val lastModified: Long, val bytes: Long)

    /**
     * The oldest folders, taken until what is left fits in [budgetBytes].
     *
     * Empty when everything already fits, which is the case on nearly every run — a phone that
     * receives a few letters a day takes years to reach the budget at all.
     */
    fun foldersToDelete(folders: List<Folder>, budgetBytes: Long): List<String> {
        var total = folders.sumOf { it.bytes }
        if (total <= budgetBytes) return emptyList()

        val doomed = mutableListOf<String>()
        for (folder in folders.sortedBy { it.lastModified }) {
            if (total <= budgetBytes) break
            doomed.add(folder.name)
            total -= folder.bytes
        }
        return doomed
    }
}
