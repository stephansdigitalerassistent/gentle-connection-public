package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.MobileNumber
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileNumberTest {

    @Test
    fun `every Swiss 07x is mobile`() {
        listOf("41751234567", "41761234567", "41771234567", "41781234567", "41791234567").forEach {
            assertTrue(it, MobileNumber.isMobile(it))
        }
    }

    @Test
    fun `Swiss landlines and service numbers are not`() {
        // Zurich, Bern, a toll-free and a premium number.
        listOf("41441234567", "41311234567", "41800123456", "41900123456").forEach {
            assertFalse(it, MobileNumber.isMobile(it))
        }
    }

    @Test
    fun `mobiles in the neighbouring countries are`() {
        listOf("4915112345678", "4917612345678", "436641234567", "33612345678", "393331234567", "423661234")
            .forEach { assertTrue(it, MobileNumber.isMobile(it)) }
    }

    @Test
    fun `landlines in the neighbouring countries are not`() {
        listOf("493012345678", "43112345678", "33112345678", "390212345678", "4232361234")
            .forEach { assertFalse(it, MobileNumber.isMobile(it)) }
    }

    @Test
    fun `a country nobody listed is not called mobile`() {
        assertFalse(MobileNumber.isMobile("447911123456"))
    }

    @Test
    fun `nothing readable is not mobile`() {
        assertFalse(MobileNumber.isMobile(null))
        assertFalse(MobileNumber.isMobile(""))
        assertFalse(MobileNumber.isMobile("4179"))
    }
}
