package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.threema.ThreemaStartupPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreemaStartupPolicyTest {

    @Test
    fun `an unprovisioned phone does not start the client`() {
        // The client's connection loop registers a brand new Threema ID when it finds none.
        // Starting it here would mint an address nobody has, quietly, on her phone — and then
        // report Connected, which looks exactly like everything working.
        assertFalse(ThreemaStartupPolicy.shouldStartAutomatically(hasIdentity = false))
    }

    @Test
    fun `a provisioned phone starts the client`() {
        assertTrue(ThreemaStartupPolicy.shouldStartAutomatically(hasIdentity = true))
    }

    @Test
    fun `a phone nobody has set up may register its first identity`() {
        assertTrue(ThreemaStartupPolicy.mayRegisterNewIdentity(hasIdentity = false, provisionedBefore = false))
    }

    @Test
    fun `an identity that cannot be read is never replaced by a new one`() {
        // The Keystore failed and the encrypted store fell back to an empty file. Registering now
        // would give her an address nobody has and could lose the one her family write to.
        assertFalse(ThreemaStartupPolicy.mayRegisterNewIdentity(hasIdentity = false, provisionedBefore = true))
    }

    @Test
    fun `a phone that has an identity never registers another`() {
        assertFalse(ThreemaStartupPolicy.mayRegisterNewIdentity(hasIdentity = true, provisionedBefore = true))
        assertFalse(ThreemaStartupPolicy.mayRegisterNewIdentity(hasIdentity = true, provisionedBefore = false))
    }

    @Test
    fun `an identity that cannot be read can still be unlinked`() {
        // The registrar tells the caregiver to unlink it. If unlinking refused because nothing
        // readable is stored, the marker would never be cleared and no identity could follow.
        assertTrue(ThreemaStartupPolicy.hasSomethingToUnlink(hasIdentity = false, provisionedBefore = true))
        assertTrue(ThreemaStartupPolicy.hasSomethingToUnlink(hasIdentity = true, provisionedBefore = true))
    }

    @Test
    fun `a phone nobody has set up has nothing to unlink`() {
        assertFalse(ThreemaStartupPolicy.hasSomethingToUnlink(hasIdentity = false, provisionedBefore = false))
    }
}
