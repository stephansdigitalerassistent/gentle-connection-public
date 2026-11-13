package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.GroupFace
import ch.heuscher.gentleconnect.model.GroupFaces
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is drawn in a several-people conversation, and in what order.
 *
 * The order is the part that is easy to get wrong without anybody noticing: the face in front of
 * the reply is the person she is answering, and a stale one is a reply that looks addressed to
 * somebody who has not written for a week.
 */
class GroupFacesTest {

    private val family = GroupIdentity("ECHO1001", "0011223344556677")
    private val choir = GroupIdentity("ECHO1001", "8899aabbccddeeff")

    private fun message(
        id: String,
        from: String,
        senderId: String?,
        at: Long,
        group: GroupIdentity? = family,
        direction: MessageDirection = MessageDirection.INCOMING,
        groupName: String? = "Familie",
        address: String? = null
    ) = Message(
        id = id,
        senderName = from,
        senderId = senderId,
        senderAddress = address,
        text = "Hallo",
        timestamp = at,
        sourcePackage = "",
        groupIdentity = group,
        direction = direction,
        groupName = groupName
    )

    private fun face(message: Message) =
        GroupFace(GroupFaces.speakerKey(message)!!, message.senderName)

    @Test
    fun `whoever wrote last is in front`() {
        val messages = listOf(
            message("1", "Anna", "AAAA1111", at = 100),
            message("2", "Peter", "PPPP2222", at = 200),
            message("3", "Anna", "AAAA1111", at = 300)
        )

        val faces = GroupFaces.of(family, emptyList(), messages, ::face)

        assertEquals(listOf("Anna", "Peter"), faces.map { it.name })
    }

    @Test
    fun `members who never wrote follow the ones who did, by name`() {
        val members = listOf(
            GroupFace("ZZZZ9999", "Zora"),
            GroupFace("PPPP2222", "Peter"),
            GroupFace("BBBB3333", "Berta")
        )
        val messages = listOf(message("1", "Peter", "PPPP2222", at = 100))

        val faces = GroupFaces.of(family, members, messages, ::face)

        assertEquals(listOf("Peter", "Berta", "Zora"), faces.map { it.name })
    }

    @Test
    fun `somebody who wrote and has since left is not drawn among the members`() {
        val members = listOf(GroupFace("PPPP2222", "Peter"))
        val messages = listOf(
            message("1", "Peter", "PPPP2222", at = 100),
            message("2", "Gone", "GGGG0000", at = 200)
        )

        val faces = GroupFaces.of(family, members, messages, ::face)

        assertEquals(listOf("Peter"), faces.map { it.name })
    }

    @Test
    fun `her own messages and other groups do not count as speakers`() {
        val messages = listOf(
            message("1", "Familie", "ECHO1001", at = 500, direction = MessageDirection.OUTGOING),
            message("2", "Chorleiterin", "CCCC4444", at = 400, group = choir),
            message("3", "Anna", "AAAA1111", at = 100)
        )

        val faces = GroupFaces.of(family, emptyList(), messages, ::face)

        assertEquals(listOf("Anna"), faces.map { it.name })
    }

    @Test
    fun `a sender known only by the group's own chat address is keyed by name`() {
        val a = message("1", "Anna", "120363000000@g.us", at = 100)
        val b = message("2", "Bea", "120363000000@g.us", at = 200)

        assertEquals("name:anna", GroupFaces.speakerKey(a))
        assertEquals(2, GroupFaces.of(family, emptyList(), listOf(a, b), ::face).size)
    }

    @Test
    fun `a run starts where the speaker changes and nowhere else`() {
        val anna1 = message("1", "Anna", "AAAA1111", at = 100)
        val anna2 = message("2", "Anna", "AAAA1111", at = 200)
        val peter = message("3", "Peter", "PPPP2222", at = 300)
        val hers = message("4", "Familie", null, at = 400, direction = MessageDirection.OUTGOING)

        assertTrue(GroupFaces.startsRun(null, anna1))
        assertFalse(GroupFaces.startsRun(anna1, anna2))
        assertTrue(GroupFaces.startsRun(anna2, peter))
        assertTrue(GroupFaces.startsRun(hers, anna1))
        assertFalse("her own messages carry no face", GroupFaces.startsRun(peter, hers))
    }

    @Test
    fun `a private message never starts a run`() {
        val private = message("1", "Anna", "AAAA1111", at = 100, group = null, groupName = null)

        assertFalse(GroupFaces.startsRun(null, private))
    }

    @Test
    fun `a group row names who wrote, a private one does not`() {
        val inGroup = message("1", "Anna", "AAAA1111", at = 100)
        val private = message("2", "Anna", "AAAA1111", at = 100, group = null, groupName = null)
        val hers = message("3", "Familie", null, at = 100, direction = MessageDirection.OUTGOING)

        assertEquals("Anna", GroupFaces.previewSpeaker(inGroup))
        assertNull(GroupFaces.previewSpeaker(private))
        assertNull(GroupFaces.previewSpeaker(hers))
    }

    @Test
    fun `an unnamed group titled by its speaker does not name them twice`() {
        val unnamed = message("1", "Anna", "AAAA1111", at = 100, groupName = null)

        assertNull(GroupFaces.previewSpeaker(unnamed))
    }

    private val others: (Int) -> String = { "$it weitere" }

    @Test
    fun `an unnamed group is called after whoever wrote last, and how many more`() {
        val faces = listOf(
            GroupFace("A", "Monika Peter"),
            GroupFace("B", "Celine Weiss"),
            GroupFace("C", "Katrin Brunner")
        )
        assertEquals("Monika und 2 weitere", GroupFaces.unnamedTitle(faces, "und", others))
    }

    @Test
    fun `an unnamed group with one other person is just that person`() {
        assertEquals(
            "Monika",
            GroupFaces.unnamedTitle(listOf(GroupFace("A", "Monika Peter")), "und", others)
        )
    }

    @Test
    fun `an unnamed group nobody can be named in has no title of its own`() {
        assertNull(GroupFaces.unnamedTitle(emptyList(), "und", others))
        assertNull(GroupFaces.unnamedTitle(listOf(GroupFace("A", "ECHO1001")), "und", others))
    }

    @Test
    fun `a number at the front of an unnamed group is not cut at its first space`() {
        val faces = listOf(GroupFace("A", "+41 79 123 45 67"), GroupFace("B", "Celine Weiss"))
        assertEquals("+41 79 123 45 67 und 1 weitere", GroupFaces.unnamedTitle(faces, "und", others))
    }

    @Test
    fun `one person writing from two addresses is one face, with the same name and photo`() {
        val photo: (Message) -> GroupFace = { m ->
            GroupFace(GroupFaces.speakerKey(m)!!, m.senderName, photoUri = "content://katrin")
        }
        val messages = listOf(
            message("1", "Katrin Brunner", "41791234567@s.whatsapp.net", at = 100),
            message("2", "Katrin Brunner", "98765432@lid", at = 200)
        )
        assertEquals(1, GroupFaces.of(family, emptyList(), messages, photo).size)
    }

    @Test
    fun `two people with the same name but different photos stay two`() {
        val photo: (Message) -> GroupFace = { m ->
            GroupFace(GroupFaces.speakerKey(m)!!, m.senderName, photoUri = "content://${m.senderId}")
        }
        val messages = listOf(
            message("1", "Anna Muster", "AAAA1111", at = 100),
            message("2", "Anna Muster", "BBBB2222", at = 200)
        )
        assertEquals(2, GroupFaces.of(family, emptyList(), messages, photo).size)
    }

    @Test
    fun `the group's creator is one of its members`() {
        // Threema's setup message lists everybody except the person sending it, so the creator
        // arrives in no member list. Found in "Familie Hartmann" on her phone: two member rows,
        // one of them hers, and the creator writing in it — drawn as a group of one.
        val members = GroupFaces.memberIdentities(family, listOf("AAAA1111", "MMMM3333"), own = "MMMM3333")

        assertEquals(listOf("AAAA1111", "ECHO1001"), members)
    }

    @Test
    fun `a group we have no member list for stays a group we have no member list for`() {
        // Not "a group of one, its creator". A membership nobody has told us is not a membership
        // to invent — it is what keeps WhatsApp on the whoever-wrote fallback below.
        assertEquals(emptyList<String>(), GroupFaces.memberIdentities(family, emptyList(), own = "MMMM3333"))
    }

    @Test
    fun `the creator is not counted twice, whatever case they arrive in`() {
        val members = GroupFaces.memberIdentities(family, listOf("echo1001", " AAAA1111 "), own = null)

        assertEquals(listOf("ECHO1001", "AAAA1111"), members)
    }

    @Test
    fun `a sender that is only the group's own address is not a member of it`() {
        // 233 messages on her phone carried the group's address as their sender, all of them
        // keyed alike: one extra person per group, named 120363…@g.us.
        val messages = listOf(
            message("1", "Anna", "AAAA1111", at = 100),
            message("2", "120363000000@g.us", "120363000000@g.us", at = 200)
        )

        val faces = GroupFaces.of(family, emptyList(), messages, ::face)

        assertEquals(listOf("Anna"), faces.map { it.name })
    }

    @Test
    fun `somebody whose name we do not know is still one of the people`() {
        // Nameless is not the same as address-shaped: this is a voice in the conversation that
        // nobody has managed to name, and she is shown "Someone" rather than one fewer person.
        val messages = listOf(
            message("1", "Anna", "AAAA1111", at = 100),
            message("2", "", "BBBB2222", at = 200)
        )

        assertEquals(2, GroupFaces.of(family, emptyList(), messages, ::face).size)
    }

    @Test
    fun `two nameless people are not merged into one`() {
        val messages = listOf(
            message("1", "", "AAAA1111", at = 100),
            message("2", "", "BBBB2222", at = 200)
        )
        assertEquals(2, GroupFaces.of(family, emptyList(), messages, ::face).size)
    }

    @Test
    fun `a member who has written is drawn the way their messages draw them`() {
        // A WhatsApp member arrives as a telephone number and nothing else. Somebody her address
        // book has never heard of would be a row of digits in the face strip, while every message
        // of theirs on the same screen says Monika.
        val members = listOf(GroupFace("41791234567", "41791234567"))
        val messages = listOf(
            message("1", "Monika", "41791234567", at = 100).copy(senderAddress = "+41791234567")
        )

        val faces = GroupFaces.of(family, members, messages, ::face)

        assertEquals(listOf("Monika"), faces.map { it.name })
        assertEquals(listOf("41791234567"), faces.map { it.key })
    }

    @Test
    fun `a member whose messages name nobody keeps the name the member list gave`() {
        // The other direction, and the reason the rule is not simply "the message wins": a
        // message carrying an address or no name at all must not overwrite a real name.
        val members = listOf(GroupFace("41791234567", "Monika"))
        val messages = listOf(message("1", "  ", "41791234567", at = 100))

        val faces = GroupFaces.of(family, members, messages, ::face)

        assertEquals(listOf("Monika"), faces.map { it.name })
    }

    @Test
    fun `a member who has written keeps their photograph when the member list has none`() {
        val members = listOf(GroupFace("41791234567", "Monika"))
        val messages = listOf(message("1", "Monika", "41791234567", at = 100))
        val withPhoto = { message: Message ->
            GroupFace(GroupFaces.speakerKey(message)!!, message.senderName, "content://photo/1")
        }

        val faces = GroupFaces.of(family, members, messages, withPhoto)

        assertEquals(listOf("content://photo/1"), faces.map { it.photoUri })
    }

    @Test
    fun `the column beside the reply holds four faces on the open folding phone`() {
        // What the second column has left once the faces she can send and the writing line have
        // taken theirs: about three hundred points, at 64 a face with 8 between them.
        assertEquals(4, GroupFaces.cardsInColumn(available = 300f, card = 64f, gap = 8f))
    }

    @Test
    fun `the last gap is not counted, so an exact fit fits`() {
        // Four faces and three gaps are 280 exactly. Counting a gap after the last one would
        // fade a face that is entirely on the screen.
        assertEquals(4, GroupFaces.cardsInColumn(available = 280f, card = 64f, gap = 8f))
        assertEquals(3, GroupFaces.cardsInColumn(available = 279f, card = 64f, gap = 8f))
    }

    @Test
    fun `a column with no room in it holds nobody`() {
        assertEquals(0, GroupFaces.cardsInColumn(available = 0f, card = 64f, gap = 8f))
        assertEquals(0, GroupFaces.cardsInColumn(available = 40f, card = 64f, gap = 8f))
        // And nonsense asks for nothing rather than dividing by nothing.
        assertEquals(0, GroupFaces.cardsInColumn(available = 300f, card = 0f, gap = 8f))
    }
}
