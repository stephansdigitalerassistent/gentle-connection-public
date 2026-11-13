package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.SupportClaimPolicy
import ch.heuscher.gentleconnect.model.SupportClaimVia
import ch.heuscher.gentleconnect.model.SupportRequest
import ch.heuscher.gentleconnect.model.SupportStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportClaimPolicyTest {

    private val baseRequest = SupportRequest(
        id = "req-1",
        createdAt = 100_000L,
        screen = "Startseite",
        status = SupportStatus.OPEN
    )

    @Test
    fun `isClaimText recognizes a yes at the start, case-insensitively and trimmed`() {
        assertTrue(SupportClaimPolicy.isClaimText("ok"))
        assertTrue(SupportClaimPolicy.isClaimText("OK, bin unterwegs"))
        assertTrue(SupportClaimPolicy.isClaimText("  ja, ich übernehme"))
        assertTrue(SupportClaimPolicy.isClaimText("komme gleich"))
        assertTrue(SupportClaimPolicy.isClaimText("bin gleich da"))
        assertTrue(SupportClaimPolicy.isClaimText("Bin auf dem Weg"))
        assertTrue(SupportClaimPolicy.isClaimText("rufe sie an"))
        assertTrue(SupportClaimPolicy.isClaimText("ich kümmere mich"))
        assertTrue(SupportClaimPolicy.isClaimText("Ich schau es mir an"))
        assertTrue(SupportClaimPolicy.isClaimText("👍"))
        assertTrue(SupportClaimPolicy.isClaimText("👍🏽"))
        assertTrue(SupportClaimPolicy.isClaimText("✅ erledigt"))
        assertTrue(SupportClaimPolicy.isClaimText("On my way"))

        assertFalse(SupportClaimPolicy.isClaimText("hallo"))
        assertFalse(SupportClaimPolicy.isClaimText("wie geht es dir"))
        assertFalse(SupportClaimPolicy.isClaimText(null))
        assertFalse(SupportClaimPolicy.isClaimText("   "))
    }

    @Test
    fun `isClaimText does not take a no or a not-now for a yes`() {
        // Each of these used to claim, and a claim tells everybody else to stand down.
        assertFalse(SupportClaimPolicy.isClaimText("Ich kann gerade nicht"))
        assertFalse(SupportClaimPolicy.isClaimText("Bin im Zug, kann jemand anders?"))
        assertFalse(SupportClaimPolicy.isClaimText("Bin gleich im Meeting"))
        assertFalse(SupportClaimPolicy.isClaimText("Ich bin im Ausland"))
        assertFalse(SupportClaimPolicy.isClaimText("Ok, aber ich schaffe es nicht"))
        assertFalse(SupportClaimPolicy.isClaimText("Ja, später"))
        assertFalse(SupportClaimPolicy.isClaimText("Komme leider erst morgen"))
        assertFalse(SupportClaimPolicy.isClaimText("Johann hier"))
        assertFalse(SupportClaimPolicy.isClaimText("Januar?"))
        assertFalse(SupportClaimPolicy.isClaimText("👎"))
        assertFalse(SupportClaimPolicy.isClaimText("Can’t right now"))
    }

    @Test
    fun `only an agreeing reaction to the alert claims`() {
        assertTrue(SupportClaimPolicy.isClaimReaction("👍"))
        assertTrue(SupportClaimPolicy.isClaimReaction("👍🏻"))
        assertTrue(SupportClaimPolicy.isClaimReaction("✔️"))
        assertTrue(SupportClaimPolicy.isClaimReaction("🙏"))
        assertTrue(SupportClaimPolicy.isClaimReaction("👌"))
        // 👎 is also what an older Threema client's "decline" arrives as.
        assertFalse(SupportClaimPolicy.isClaimReaction("👎"))
        assertFalse(SupportClaimPolicy.isClaimReaction("❓"))
        assertFalse(SupportClaimPolicy.isClaimReaction("😢"))
        assertFalse(SupportClaimPolicy.isClaimReaction("❤️"))
        assertFalse(SupportClaimPolicy.isClaimReaction(null))

        // A 👍 on some other message of hers is about that message.
        assertFalse(
            SupportClaimPolicy.isClaimSignal(
                isQuotedReplyToAlert = false, isReactionToAlert = false, text = "👍", isReaction = true
            )
        )
        assertTrue(
            SupportClaimPolicy.isClaimSignal(
                isQuotedReplyToAlert = false, isReactionToAlert = true, text = "👍", isReaction = true
            )
        )
        assertFalse(
            SupportClaimPolicy.isClaimSignal(
                isQuotedReplyToAlert = false, isReactionToAlert = true, text = "👎", isReaction = true
            )
        )
    }

    @Test
    fun `a quoted reply to the alert claims unless it says no`() {
        assertTrue(SupportClaimPolicy.isClaimSignal(true, false, "Was ist los?"))
        assertTrue(SupportClaimPolicy.isClaimSignal(true, false, null))
        assertFalse(SupportClaimPolicy.isClaimSignal(true, false, "Ich kann gerade nicht"))
    }

    @Test
    fun `isCallClaim returns true only when call connected with member during valid window`() {
        val now = 105_000L

        // Valid connected call
        assertTrue(
            SupportClaimPolicy.isCallClaim(
                callConnected = true,
                isMember = true,
                request = baseRequest,
                now = now
            )
        )

        // Missed / ringing call does NOT claim
        assertFalse(
            SupportClaimPolicy.isCallClaim(
                callConnected = false,
                isMember = true,
                request = baseRequest,
                now = now
            )
        )

        // Call from non-member does not claim
        assertFalse(
            SupportClaimPolicy.isCallClaim(
                callConnected = true,
                isMember = false,
                request = baseRequest,
                now = now
            )
        )

        // Call after 2 hours expired does not claim
        assertFalse(
            SupportClaimPolicy.isCallClaim(
                callConnected = true,
                isMember = true,
                request = baseRequest,
                now = baseRequest.createdAt + 2 * 3600 * 1000L + 1L
            )
        )
    }

    @Test
    fun `isMessageClaim recognizes quoted reply or reaction or keyword text from member`() {
        val now = 105_000L

        // Quoted reply claims even without text
        assertTrue(
            SupportClaimPolicy.isMessageClaim(
                isQuotedReplyToAlert = true,
                isReactionToAlert = false,
                text = null,
                isMember = true,
                request = baseRequest,
                now = now
            )
        )

        // A 👍 reaction to the alert claims
        assertTrue(
            SupportClaimPolicy.isMessageClaim(
                isQuotedReplyToAlert = false,
                isReactionToAlert = true,
                text = "👍",
                isMember = true,
                request = baseRequest,
                now = now
            )
        )

        // Keyword text claims
        assertTrue(
            SupportClaimPolicy.isMessageClaim(
                isQuotedReplyToAlert = false,
                isReactionToAlert = false,
                text = "Ich kümmere mich",
                isMember = true,
                request = baseRequest,
                now = now
            )
        )

        // Non-keyword text does NOT claim
        assertFalse(
            SupportClaimPolicy.isMessageClaim(
                isQuotedReplyToAlert = false,
                isReactionToAlert = false,
                text = "Was gibt es zu essen?",
                isMember = true,
                request = baseRequest,
                now = now
            )
        )

        // Inbound message from non-member does NOT claim
        assertFalse(
            SupportClaimPolicy.isMessageClaim(
                isQuotedReplyToAlert = false,
                isReactionToAlert = false,
                text = "Ich kümmere mich",
                isMember = false,
                request = baseRequest,
                now = now
            )
        )
    }

    @Test
    fun `second claimant detection`() {
        val claimedRequest = baseRequest.copy(
            status = SupportStatus.CLAIMED,
            claimedBy = "m1",
            claimedVia = SupportClaimVia.REPLY,
            claimedAt = 105_000L
        )

        val now = 110_000L

        // Member m2 claiming an already claimed request is a second claim
        assertTrue(SupportClaimPolicy.isSecondClaim(claimedRequest, "m2", now))

        // Same member m1 is not a second claim
        assertFalse(SupportClaimPolicy.isSecondClaim(claimedRequest, "m1", now))

        // Expired request is not a second claim
        val expiredTime = baseRequest.createdAt + 2 * 3600 * 1000L + 100L
        assertFalse(SupportClaimPolicy.isSecondClaim(claimedRequest, "m2", expiredTime))
    }

    @Test
    fun `ring through remains active for 2 hours from request creation`() {
        val createdAt = 1_000_000L
        assertTrue(SupportClaimPolicy.isRingThroughActive(createdAt, createdAt))
        assertTrue(SupportClaimPolicy.isRingThroughActive(createdAt, createdAt + 3600 * 1000L))
        assertTrue(SupportClaimPolicy.isRingThroughActive(createdAt, createdAt + 2 * 3600 * 1000L - 1L))
        assertFalse(SupportClaimPolicy.isRingThroughActive(createdAt, createdAt + 2 * 3600 * 1000L))
        assertFalse(SupportClaimPolicy.isRingThroughActive(createdAt, createdAt - 1L))
    }

    @Test
    fun `isClaimStateActive follows 60 min after claim or 30 min after call ends rule`() {
        val createdAt = 1_000_000L
        val claimedAt = 1_100_000L

        // 59 min after claim (no call): active
        assertTrue(
            SupportClaimPolicy.isClaimStateActive(
                requestCreatedAt = createdAt,
                claimedAt = claimedAt,
                claimerCallEndedAt = null,
                now = claimedAt + 59 * 60 * 1000L
            )
        )

        // 61 min after claim (no call): expired
        assertFalse(
            SupportClaimPolicy.isClaimStateActive(
                requestCreatedAt = createdAt,
                claimedAt = claimedAt,
                claimerCallEndedAt = null,
                now = claimedAt + 61 * 60 * 1000L
            )
        )

        // Call ended 50 min after claim: extends to callEnded + 30 min = claimedAt + 80 min
        val callEndedAt = claimedAt + 50 * 60 * 1000L
        assertTrue(
            SupportClaimPolicy.isClaimStateActive(
                requestCreatedAt = createdAt,
                claimedAt = claimedAt,
                claimerCallEndedAt = callEndedAt,
                now = claimedAt + 75 * 60 * 1000L
            )
        )

        // Past callEnded + 30 min: inactive
        assertFalse(
            SupportClaimPolicy.isClaimStateActive(
                requestCreatedAt = createdAt,
                claimedAt = claimedAt,
                claimerCallEndedAt = callEndedAt,
                now = callEndedAt + 31 * 60 * 1000L
            )
        )

        // Hard cap at 2 hours from request creation
        val twoHoursAfterReq = createdAt + 2 * 3600 * 1000L
        assertFalse(
            SupportClaimPolicy.isClaimStateActive(
                requestCreatedAt = createdAt,
                claimedAt = claimedAt,
                claimerCallEndedAt = callEndedAt,
                now = twoHoursAfterReq + 1L
            )
        )
    }
}
