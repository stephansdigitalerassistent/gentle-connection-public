package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.SupportChannel
import ch.heuscher.gentleconnect.model.SupportChannelPolicy
import ch.heuscher.gentleconnect.model.SupportMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportChannelPolicyTest {

    private val memberWithThreema = SupportMember(
        id = "m1",
        name = "Anna",
        phoneNumber = "+41791234567",
        threemaId = "ANNA1234",
        whatsAppNumber = "+41791234567"
    )

    private val memberWithWhatsAppOnly = SupportMember(
        id = "m2",
        name = "Ben",
        phoneNumber = "+41792345678",
        threemaId = null,
        whatsAppNumber = "+41792345678"
    )

    private val memberWithSmsOnly = SupportMember(
        id = "m3",
        name = "Clara",
        phoneNumber = "+41795550128",
        threemaId = null,
        whatsAppNumber = null
    )

    @Test
    fun `selectInitialChannel prefers Threema when member has both and internet is available`() {
        val channel = SupportChannelPolicy.selectInitialChannel(
            member = memberWithThreema,
            hasInternet = true
        )
        assertEquals(SupportChannel.THREEMA, channel)
    }

    @Test
    fun `selectInitialChannel selects WhatsApp when member has no Threema and internet is available`() {
        val channel = SupportChannelPolicy.selectInitialChannel(
            member = memberWithWhatsAppOnly,
            hasInternet = true
        )
        assertEquals(SupportChannel.WHATSAPP, channel)
    }

    @Test
    fun `selectInitialChannel selects SMS when member has no messenger configured`() {
        val channel = SupportChannelPolicy.selectInitialChannel(
            member = memberWithSmsOnly,
            hasInternet = true
        )
        assertEquals(SupportChannel.SMS, channel)
    }

    @Test
    fun `selectInitialChannel selects SMS immediately when device has no internet`() {
        val channel = SupportChannelPolicy.selectInitialChannel(
            member = memberWithThreema,
            hasInternet = false
        )
        assertEquals(SupportChannel.SMS, channel)
    }

    @Test
    fun `roaming and threemaOnlyWhenRoaming setting never suppress SMS for support alerts`() {
        // Explicit exception in spec 4.2 & 8: Roaming must never suppress SMS for help alerts!
        val channelNoInternet = SupportChannelPolicy.selectInitialChannel(
            member = memberWithThreema,
            hasInternet = false,
            isRoaming = true,
            threemaOnlyWhenRoaming = true
        )
        assertEquals(SupportChannel.SMS, channelNoInternet)

        val channelSmsOnly = SupportChannelPolicy.selectInitialChannel(
            member = memberWithSmsOnly,
            hasInternet = true,
            isRoaming = true,
            threemaOnlyWhenRoaming = true
        )
        assertEquals(SupportChannel.SMS, channelSmsOnly)
    }

    @Test
    fun `shouldFallbackToSms returns false if channel is already SMS`() {
        val fallback = SupportChannelPolicy.shouldFallbackToSms(
            channel = SupportChannel.SMS,
            confirmedSent = false,
            elapsedSinceSendMs = 120_000L
        )
        assertFalse(fallback)
    }

    @Test
    fun `shouldFallbackToSms returns false if transmission was confirmed sent`() {
        val fallback = SupportChannelPolicy.shouldFallbackToSms(
            channel = SupportChannel.THREEMA,
            confirmedSent = true,
            elapsedSinceSendMs = 120_000L
        )
        assertFalse(fallback)
    }

    @Test
    fun `shouldFallbackToSms returns false if less than 60 seconds have elapsed`() {
        val fallback = SupportChannelPolicy.shouldFallbackToSms(
            channel = SupportChannel.THREEMA,
            confirmedSent = false,
            elapsedSinceSendMs = 59_999L
        )
        assertFalse(fallback)
    }

    @Test
    fun `shouldFallbackToSms returns true when unconfirmed and 60 seconds or more have elapsed`() {
        val fallbackExact = SupportChannelPolicy.shouldFallbackToSms(
            channel = SupportChannel.THREEMA,
            confirmedSent = false,
            elapsedSinceSendMs = 60_000L
        )
        assertTrue(fallbackExact)

        val fallbackWhatsApp = SupportChannelPolicy.shouldFallbackToSms(
            channel = SupportChannel.WHATSAPP,
            confirmedSent = false,
            elapsedSinceSendMs = 65_000L
        )
        assertTrue(fallbackWhatsApp)
    }

    @Test
    fun `nextRetryIntervalMs returns 2 minutes for first 30 minutes`() {
        assertEquals(2L * 60 * 1000, SupportChannelPolicy.nextRetryIntervalMs(0L))
        assertEquals(2L * 60 * 1000, SupportChannelPolicy.nextRetryIntervalMs(29L * 60 * 1000))
    }

    @Test
    fun `nextRetryIntervalMs returns 10 minutes between 30 minutes and 2 hours`() {
        assertEquals(10L * 60 * 1000, SupportChannelPolicy.nextRetryIntervalMs(30L * 60 * 1000))
        assertEquals(10L * 60 * 1000, SupportChannelPolicy.nextRetryIntervalMs(119L * 60 * 1000))
    }

    @Test
    fun `nextRetryIntervalMs returns null after 2 hours`() {
        assertNull(SupportChannelPolicy.nextRetryIntervalMs(120L * 60 * 1000))
        assertNull(SupportChannelPolicy.nextRetryIntervalMs(150L * 60 * 1000))
    }
}
