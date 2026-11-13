package ch.heuscher.gentleconnect.data.mail

import ch.heuscher.gentleconnect.model.MailAttachments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Saving what came with a letter, on a real file system.
 *
 * The store deliberately takes a directory rather than a Context, which is what makes this
 * runnable in CI at all — and CI is the only place any of this app is compiled. The parts worth
 * proving here are the ones with a cost attached: a file that must never be written, a file that
 * must be deleted again once its size is known, and a name that must not be able to leave its
 * folder.
 */
class MailAttachmentStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(budgetBytes: Long = 96L * 1024 * 1024) =
        FileMailAttachmentStore(temporaryFolder.newFolder("mail-attachments-${counter++}"), budgetBytes)

    private fun bytes(size: Int) = ByteArrayInputStream(ByteArray(size) { 7 })

    @Test
    fun `a photograph her daughter attached is kept where it can be found again`() {
        val saved = store().save(
            mailId = "<a@b.example>",
            fileName = "Enkel.jpg",
            mimeType = "image/jpeg",
            isInline = false,
            content = bytes(400_000)
        )

        assertNotNull(saved)
        assertEquals("Enkel.jpg", saved!!.fileName)
        assertTrue(saved.isPicture)
        assertEquals(400_000L, File(saved.path).length())
    }

    @Test
    fun `the logo at the top of a company letter is not written to her phone`() {
        val target = store()
        val saved = target.save(
            mailId = "<news@example.com>",
            fileName = "logo.png",
            mimeType = "image/png",
            isInline = true,
            content = bytes(3_000)
        )

        assertNull(saved)
    }

    @Test
    fun `a logo that was refused leaves nothing behind on disk`() {
        val root = temporaryFolder.newFolder("leftovers")
        val target = FileMailAttachmentStore(root)
        target.save(
            mailId = "<news@example.com>",
            fileName = "logo.png",
            mimeType = "image/png",
            isInline = true,
            content = bytes(3_000)
        )

        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(emptyList<File>(), files)
    }

    @Test
    fun `a spreadsheet is declined before a single byte is read`() {
        val content = bytes(50_000)
        val saved = store().save(
            mailId = "<a@b.example>",
            fileName = "Liste.xlsx",
            mimeType = "application/vnd.ms-excel",
            isInline = false,
            content = content
        )

        assertNull(saved)
        // Nothing about it was read: this is what keeps a mailing with a forty-megabyte
        // attachment from being pulled over her connection to be thrown away.
        assertEquals(50_000, content.available())
    }

    @Test
    fun `something enormous is stopped rather than stored`() {
        val root = temporaryFolder.newFolder("enormous")
        val target = FileMailAttachmentStore(root)
        val saved = target.save(
            mailId = "<a@b.example>",
            fileName = "Ferien.jpg",
            mimeType = "image/jpeg",
            isInline = false,
            content = bytes((MailAttachments.MAX_BYTES + 5_000).toInt())
        )

        assertNull(saved)
        assertEquals(emptyList<File>(), root.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun `a file name from a stranger stays inside the letter's own folder`() {
        val root = temporaryFolder.newFolder("traversal")
        val target = FileMailAttachmentStore(root)
        val saved = target.save(
            mailId = "<a@b.example>",
            fileName = "../../databases/gentle_connection.db.jpg",
            mimeType = "image/jpeg",
            isInline = false,
            content = bytes(60_000)
        )

        assertNotNull(saved)
        val file = File(saved!!.path)
        assertEquals(root.absolutePath, file.parentFile?.parentFile?.absolutePath)
        assertFalse(saved.path.contains(".."))
    }

    @Test
    fun `the same letter fetched twice keeps one copy of its photograph`() {
        val root = temporaryFolder.newFolder("refetch")
        val target = FileMailAttachmentStore(root)
        repeat(2) {
            target.save(
                mailId = "<a@b.example>",
                fileName = "Enkel.jpg",
                mimeType = "image/jpeg",
                isInline = false,
                content = bytes(60_000)
            )
        }

        assertEquals(1, root.walkTopDown().filter { it.isFile }.count())
    }

    @Test
    fun `the oldest letters give up their files when the phone is full`() {
        val folders = listOf(
            MailAttachmentPruning.Folder(name = "oldest", lastModified = 1_000, bytes = 60),
            MailAttachmentPruning.Folder(name = "middle", lastModified = 2_000, bytes = 60),
            MailAttachmentPruning.Folder(name = "newest", lastModified = 3_000, bytes = 60)
        )

        assertEquals(
            listOf("oldest", "middle"),
            MailAttachmentPruning.foldersToDelete(folders, budgetBytes = 100)
        )
    }

    @Test
    fun `a store that fits deletes nothing at all`() {
        val folders = listOf(
            MailAttachmentPruning.Folder(name = "a", lastModified = 1, bytes = 10),
            MailAttachmentPruning.Folder(name = "b", lastModified = 2, bytes = 10)
        )

        assertEquals(emptyList<String>(), MailAttachmentPruning.foldersToDelete(folders, budgetBytes = 100))
    }

    private companion object {
        var counter = 0
    }
}
