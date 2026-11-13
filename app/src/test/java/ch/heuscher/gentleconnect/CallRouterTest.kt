package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.CallChannel
import ch.heuscher.gentleconnect.data.CallRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CallRouterTest {

    @Test
    fun `decide selects Cellular whenever there is service and a number, even with in-app Threema`() {
        val decision = CallRouter.decide(
            phoneNumber = "+41791234567",
            threemaId = "ECHO1001",
            hasCellularService = true,
            canCallThreemaInApp = true
        )
        assertNotNull(decision)
        assertEquals(CallChannel.CELLULAR, decision!!.channel)
        assertEquals("+41791234567", decision.target)
        assertEquals("cellular_service", decision.reason)
    }

    @Test
    fun `decide selects in-app Threema when there is no cellular service`() {
        val decision = CallRouter.decide(
            phoneNumber = "+41791234567",
            threemaId = "ECHO1001",
            hasCellularService = false,
            canCallThreemaInApp = true
        )
        assertNotNull(decision)
        assertEquals(CallChannel.THREEMA_IN_APP, decision!!.channel)
        assertEquals("ECHO1001", decision.target)
        assertEquals("no_cellular_service", decision.reason)
    }

    @Test
    fun `decide selects in-app Threema when the contact has no phone number, even with service`() {
        val decision = CallRouter.decide(
            phoneNumber = null,
            threemaId = "ECHO1001",
            hasCellularService = true,
            canCallThreemaInApp = true
        )
        assertNotNull(decision)
        assertEquals(CallChannel.THREEMA_IN_APP, decision!!.channel)
        assertEquals("no_phone_number", decision.reason)
    }

    @Test
    fun `decide treats a blank phone number as none`() {
        val decision = CallRouter.decide(
            phoneNumber = "   ",
            threemaId = "ECHO1001",
            hasCellularService = true,
            canCallThreemaInApp = true
        )
        assertNotNull(decision)
        assertEquals(CallChannel.THREEMA_IN_APP, decision!!.channel)
        assertEquals("no_phone_number", decision.reason)
    }

    @Test
    fun `decide falls back to cellular when there is no service and no in-app manager`() {
        val decision = CallRouter.decide(
            phoneNumber = "+41791234567",
            threemaId = "ECHO1001",
            hasCellularService = false,
            canCallThreemaInApp = false
        )
        assertNotNull(decision)
        assertEquals(CallChannel.CELLULAR, decision!!.channel)
        assertEquals("+41791234567", decision.target)
        assertEquals("cellular_without_service", decision.reason)
    }

    @Test
    fun `decide returns null when there is no number and no in-app manager`() {
        val decision = CallRouter.decide(
            phoneNumber = null,
            threemaId = "ECHO1001",
            hasCellularService = true,
            canCallThreemaInApp = false
        )
        assertNull(decision)
    }

    @Test
    fun `decide still dials the number without service when there is no Threema at all`() {
        val decision = CallRouter.decide(
            phoneNumber = "+41791234567",
            threemaId = "ECHO1001",
            hasCellularService = false,
            canCallThreemaInApp = false
        )
        assertNotNull(decision)
        assertEquals(CallChannel.CELLULAR, decision!!.channel)
        assertEquals("+41791234567", decision.target)
        assertEquals("cellular_without_service", decision.reason)
    }

    @Test
    fun `decide still dials the number without service when the contact has no Threema ID`() {
        val decision = CallRouter.decide(
            phoneNumber = "+41791234567",
            threemaId = null,
            hasCellularService = false,
            canCallThreemaInApp = true
        )
        assertNotNull(decision)
        assertEquals(CallChannel.CELLULAR, decision!!.channel)
        assertEquals("cellular_without_service", decision.reason)
    }

    @Test
    fun `decide returns null when both phone number and Threema ID are null`() {
        val decision = CallRouter.decide(
            phoneNumber = null,
            threemaId = null,
            hasCellularService = true,
            canCallThreemaInApp = true
        )
        assertNull(decision)
    }

    @Test
    fun `decide returns null when target details are blank`() {
        val decision = CallRouter.decide(
            phoneNumber = "",
            threemaId = "   ",
            hasCellularService = true,
            canCallThreemaInApp = true
        )
        assertNull(decision)
    }

    @Test
    fun `decide returns null when contact has only a Threema ID and no way to call Threema`() {
        val decision = CallRouter.decide(
            phoneNumber = null,
            threemaId = "ECHO1001",
            hasCellularService = true,
            canCallThreemaInApp = false
        )
        assertNull(decision)
    }

    @Test
    fun `decide uses in-app Threema while roaming with the switch on, even with service`() {
        val decision = CallRouter.decide(
            phoneNumber = "+41791234567",
            threemaId = "ECHO1001",
            hasCellularService = true,
            canCallThreemaInApp = true,
            isRoaming = true,
            threemaOnlyWhenRoaming = true
        )
        assertEquals(CallChannel.THREEMA_IN_APP, decision!!.channel)
        assertEquals("roaming", decision.reason)
    }

    @Test
    fun `decide returns null while roaming when the in-app call is unavailable`() {
        val decision = CallRouter.decide(
            phoneNumber = "+41791234567",
            threemaId = "ECHO1001",
            hasCellularService = true,
            canCallThreemaInApp = false,
            isRoaming = true,
            threemaOnlyWhenRoaming = true
        )
        assertNull(decision)
    }

    @Test
    fun `decide never rings the telephone while roaming with the switch on`() {
        assertNull(
            CallRouter.decide(
                phoneNumber = "+41791234567",
                threemaId = null,
                hasCellularService = true,
                canCallThreemaInApp = true,
                isRoaming = true,
                threemaOnlyWhenRoaming = true
            )
        )
        assertNull(
            CallRouter.decide(
                phoneNumber = "+41791234567",
                threemaId = "ECHO1001",
                hasCellularService = true,
                canCallThreemaInApp = false,
                isRoaming = true,
                threemaOnlyWhenRoaming = true
            )
        )
    }

    @Test
    fun `decide rings the telephone while roaming when the switch is off`() {
        val decision = CallRouter.decide(
            phoneNumber = "+41791234567",
            threemaId = "ECHO1001",
            hasCellularService = true,
            canCallThreemaInApp = true,
            isRoaming = true,
            threemaOnlyWhenRoaming = false
        )
        assertEquals(CallChannel.CELLULAR, decision!!.channel)
    }

    @Test
    fun `decide ignores the switch at home`() {
        val decision = CallRouter.decide(
            phoneNumber = "+41791234567",
            threemaId = "ECHO1001",
            hasCellularService = true,
            canCallThreemaInApp = true,
            isRoaming = false,
            threemaOnlyWhenRoaming = true
        )
        assertEquals(CallChannel.CELLULAR, decision!!.channel)
    }
}
