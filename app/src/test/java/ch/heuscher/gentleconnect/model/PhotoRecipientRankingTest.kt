package ch.heuscher.gentleconnect.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoRecipientRankingTest {

    private val dayMillis = 24L * 60 * 60 * 1000L
    // Fixed reference midnight
    private val todayStart = 1700000000000L

    @Test
    fun `per-person merge across Threema and WhatsApp and phone sums all routes`() {
        val person = PhotoCandidate(
            key = "simon",
            name = "Simon",
            threemaIds = setOf("SIMONID"),
            whatsappNumbers = setOf("+41791112233"),
            phoneNumbers = setOf("+41791112233", "0791112233")
        )
        val other = PhotoCandidate(
            key = "anna",
            name = "Anna",
            threemaIds = setOf("ANNAID")
        )

        val sent = listOf(
            PhotoEvent(address = "SIMONID", timestamp = todayStart - 2 * dayMillis),
            PhotoEvent(address = "41791112233@s.whatsapp.net", timestamp = todayStart - 3 * dayMillis)
        )
        val reactions = listOf(
            PhotoEvent(address = "SIMONID", timestamp = todayStart - 4 * dayMillis)
        )
        val calls = listOf(
            PhotoEvent(address = "079 111 22 33", timestamp = todayStart - 5 * dayMillis)
        )

        val ranked = PhotoRecipientRanking.rank(
            candidates = listOf(other, person),
            sent = sent,
            reactions = reactions,
            placedCalls = calls,
            favouriteOrder = emptyList(),
            todayStart = todayStart
        )

        // Simon has 2 sent + 1 reaction + 1 call = 4 events; Anna has 0
        assertEquals(listOf("simon", "anna"), ranked)
    }

    @Test
    fun `placed calls count and lift candidates who are primarily called`() {
        val dad = PhotoCandidate(
            key = "dad",
            name = "Dad",
            phoneNumbers = setOf("+41790000001", "0790000001")
        )
        val colleague = PhotoCandidate(
            key = "colleague",
            name = "Colleague",
            threemaIds = setOf("COLLID")
        )

        val sent = listOf(
            PhotoEvent(address = "COLLID", timestamp = todayStart - 5 * dayMillis)
        )
        val calls = listOf(
            PhotoEvent(address = "+41790000001", timestamp = todayStart - 2 * dayMillis),
            PhotoEvent(address = "0790000001", timestamp = todayStart - 3 * dayMillis)
        )

        val ranked = PhotoRecipientRanking.rank(
            candidates = listOf(colleague, dad),
            sent = sent,
            reactions = emptyList(),
            placedCalls = calls,
            favouriteOrder = emptyList(),
            todayStart = todayStart
        )

        // Dad has 2 calls; Colleague has 1 sent message
        assertEquals(listOf("dad", "colleague"), ranked)
    }

    @Test
    fun `today's events are ignored so order changes at most once a day`() {
        val alice = PhotoCandidate(key = "alice", name = "Alice", threemaIds = setOf("ALICE"))
        val bob = PhotoCandidate(key = "bob", name = "Bob", threemaIds = setOf("BOB"))

        val sent = listOf(
            // Bob has past interaction
            PhotoEvent(address = "BOB", timestamp = todayStart - 10 * dayMillis),
            // Alice has heavy interaction today
            PhotoEvent(address = "ALICE", timestamp = todayStart + 1000L),
            PhotoEvent(address = "ALICE", timestamp = todayStart + 5000L)
        )

        val ranked = PhotoRecipientRanking.rank(
            candidates = listOf(alice, bob),
            sent = sent,
            reactions = emptyList(),
            placedCalls = emptyList(),
            favouriteOrder = emptyList(),
            todayStart = todayStart
        )

        // Bob has 1 past event, Alice has 0 valid window events
        assertEquals(listOf("bob", "alice"), ranked)
    }

    @Test
    fun `events older than the 60-day window are ignored`() {
        val alice = PhotoCandidate(key = "alice", name = "Alice", threemaIds = setOf("ALICE"))
        val bob = PhotoCandidate(key = "bob", name = "Bob", threemaIds = setOf("BOB"))

        val sent = listOf(
            // Alice interacted 61 days ago (outside 60d window)
            PhotoEvent(address = "ALICE", timestamp = todayStart - 61 * dayMillis),
            // Bob interacted 59 days ago (inside window)
            PhotoEvent(address = "BOB", timestamp = todayStart - 59 * dayMillis)
        )

        val ranked = PhotoRecipientRanking.rank(
            candidates = listOf(alice, bob),
            sent = sent,
            reactions = emptyList(),
            placedCalls = emptyList(),
            favouriteOrder = emptyList(),
            todayStart = todayStart,
            windowDays = 60
        )

        assertEquals(listOf("bob", "alice"), ranked)
    }

    @Test
    fun `group activity counts for the group and does not lift members`() {
        val familyGroup = GroupIdentity("CREATOR1", "fam123")
        val groupCandidate = PhotoCandidate(
            key = "group_fam",
            name = "Familie",
            group = familyGroup
        )
        val memberSon = PhotoCandidate(
            key = "son",
            name = "Sohn",
            threemaIds = setOf("SONID")
        )

        // Events in the group
        val sent = listOf(
            PhotoEvent(group = familyGroup, timestamp = todayStart - 2 * dayMillis)
        )
        val reactions = listOf(
            PhotoEvent(group = familyGroup, timestamp = todayStart - 3 * dayMillis)
        )

        val ranked = PhotoRecipientRanking.rank(
            candidates = listOf(memberSon, groupCandidate),
            sent = sent,
            reactions = reactions,
            placedCalls = emptyList(),
            favouriteOrder = emptyList(),
            todayStart = todayStart
        )

        // Group has 2 events; Son has 0 events and is not lifted by group events
        assertEquals(listOf("group_fam", "son"), ranked)
    }

    @Test
    fun `groups with score 0 are dropped while people with score 0 remain listed`() {
        val activeGroup = GroupIdentity("CREATOR1", "act123")
        val inactiveGroup = GroupIdentity("CREATOR1", "inact456")

        val group1 = PhotoCandidate(key = "g_active", name = "Aktiv", group = activeGroup)
        val group2 = PhotoCandidate(key = "g_inactive", name = "Inaktiv", group = inactiveGroup)
        val personZero = PhotoCandidate(key = "p_zero", name = "Person Ohne Interaktion")

        val sent = listOf(
            PhotoEvent(group = activeGroup, timestamp = todayStart - 5 * dayMillis)
        )

        val ranked = PhotoRecipientRanking.rank(
            candidates = listOf(group1, group2, personZero),
            sent = sent,
            reactions = emptyList(),
            placedCalls = emptyList(),
            favouriteOrder = emptyList(),
            todayStart = todayStart
        )

        // group1 has score 1 -> kept
        // personZero has score 0 -> kept (people with score 0 stay)
        // group2 has score 0 -> dropped!
        assertEquals(listOf("g_active", "p_zero"), ranked)
        assertFalse(ranked.contains("g_inactive"))
    }

    @Test
    fun `ties fall back to favourite order then alphabetical name`() {
        val c1 = PhotoCandidate(key = "clara", name = "Clara")
        val c2 = PhotoCandidate(key = "berta", name = "Berta")
        val c3 = PhotoCandidate(key = "anna", name = "Anna")

        // None has events, so all scores are 0.
        // favouriteOrder puts Clara first.
        val ranked = PhotoRecipientRanking.rank(
            candidates = listOf(c1, c2, c3),
            sent = emptyList(),
            reactions = emptyList(),
            placedCalls = emptyList(),
            favouriteOrder = listOf("clara"),
            todayStart = todayStart
        )

        // Clara is favourite -> first.
        // Berta and Anna tie on favourite index -> alphabetical: Anna, then Berta.
        assertEquals(listOf("clara", "anna", "berta"), ranked)
    }

    @Test
    fun `status broadcast group is identified and excluded at candidate filter level`() {
        val statusGroup = GroupIdentity.of("whatsapp-group", StatusBroadcast.JID)
        val normalGroup = GroupIdentity.of("whatsapp-group", "12345-67890@g.us")

        assertTrue(StatusBroadcast.isStatus(statusGroup))
        assertFalse(StatusBroadcast.isStatus(normalGroup))

        val candidates = listOfNotNull(statusGroup, normalGroup).filter { !StatusBroadcast.isStatus(it) }
        assertEquals(listOf(normalGroup), candidates)
    }

    @Test
    fun `real-phone Fold sanity numbers over 60 days`() {
        // Sanity values from a test device (last 60 days):
        // Simon 39, Dad 28 (mostly calls), Clara Meier 24, group "Hartmanns" 12,
        // Eliane 9, group "Oma und Opa" 8.
        val simon = PhotoCandidate(key = "simon", name = "Simon", threemaIds = setOf("SIMON"))
        val dad = PhotoCandidate(key = "dad", name = "Dad", phoneNumbers = setOf("+41790000000"))
        val clara = PhotoCandidate(key = "clara", name = "Clara Meier", whatsappNumbers = setOf("+41791111111"))
        val hartmannsGroup = GroupIdentity("CREATOR", "hartmanns")
        val hartmanns = PhotoCandidate(key = "hartmanns", name = "Hartmanns", group = hartmannsGroup)
        val eliane = PhotoCandidate(key = "eliane", name = "Eliane", threemaIds = setOf("ELIANE"))
        val omaGroup = GroupIdentity("CREATOR", "oma")
        val oma = PhotoCandidate(key = "oma", name = "Oma und Opa", group = omaGroup)

        val sent = mutableListOf<PhotoEvent>()
        val reactions = mutableListOf<PhotoEvent>()
        val calls = mutableListOf<PhotoEvent>()

        fun addSent(addr: String?, group: GroupIdentity?, count: Int) {
            repeat(count) { i ->
                sent += PhotoEvent(address = addr, group = group, timestamp = todayStart - ((i % 50) + 1) * dayMillis)
            }
        }
        fun addReactions(addr: String?, group: GroupIdentity?, count: Int) {
            repeat(count) { i ->
                reactions += PhotoEvent(address = addr, group = group, timestamp = todayStart - ((i % 50) + 1) * dayMillis)
            }
        }
        fun addCalls(num: String, count: Int) {
            repeat(count) { i ->
                calls += PhotoEvent(address = num, group = null, timestamp = todayStart - ((i % 50) + 1) * dayMillis)
            }
        }

        // Simon: 39 (e.g. 25 sent, 14 reactions)
        addSent("SIMON", null, 25)
        addReactions("SIMON", null, 14)

        // Dad: 28 (mostly calls: 24 calls, 4 sent)
        addCalls("+41790000000", 24)
        addSent("+41790000000", null, 4)

        // Clara Meier: 24 (15 sent, 9 reactions)
        addSent("+41791111111", null, 15)
        addReactions("+41791111111", null, 9)

        // Hartmanns: 12 (8 sent, 4 reactions)
        addSent(null, hartmannsGroup, 8)
        addReactions(null, hartmannsGroup, 4)

        // Eliane: 9 (6 sent, 3 reactions)
        addSent("ELIANE", null, 6)
        addReactions("ELIANE", null, 3)

        // Oma und Opa: 8 (5 sent, 3 reactions)
        addSent(null, omaGroup, 5)
        addReactions(null, omaGroup, 3)

        val candidates = listOf(eliane, clara, oma, dad, simon, hartmanns)
        val ranked = PhotoRecipientRanking.rank(
            candidates = candidates,
            sent = sent,
            reactions = reactions,
            placedCalls = calls,
            favouriteOrder = listOf("simon", "dad", "clara", "eliane"),
            todayStart = todayStart
        )

        assertEquals(
            listOf("simon", "dad", "clara", "hartmanns", "eliane", "oma"),
            ranked
        )

        // Verify that without calls, Dad would have only 4 events and sink below Eliane (9) and Oma (8)
        val rankedWithoutCalls = PhotoRecipientRanking.rank(
            candidates = candidates,
            sent = sent,
            reactions = reactions,
            placedCalls = emptyList(),
            favouriteOrder = listOf("simon", "dad", "clara", "eliane"),
            todayStart = todayStart
        )
        assertEquals(
            listOf("simon", "clara", "hartmanns", "eliane", "oma", "dad"),
            rankedWithoutCalls
        )
    }
}
