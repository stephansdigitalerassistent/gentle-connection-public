package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.KnownGroup
import ch.heuscher.gentleconnect.model.KnownPoster
import ch.heuscher.gentleconnect.model.KnownSender
import ch.heuscher.gentleconnect.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caregiver website hears about senders and faults from the process, not from a screen.
 *
 * Nothing here builds a view model: that is the point. These lists used to be published only
 * while the home screen was alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaregiverPublisherTest {

    private class Recording(
        private val inner: RemoteSettingsRepository = RemoteSettingsRepository()
    ) : SettingsRepository by inner {
        val senders = mutableListOf<List<KnownSender>>()
        val groups = mutableListOf<List<KnownGroup>>()
        val posters = mutableListOf<List<KnownPoster>>()
        val alerts = mutableListOf<List<CaregiverEvent>>()

        override fun publishSenders(senders: List<KnownSender>) {
            this.senders.add(senders)
        }

        override fun publishGroups(groups: List<KnownGroup>) {
            this.groups.add(groups)
        }

        override fun publishStatusPosters(posters: List<KnownPoster>) {
            this.posters.add(posters)
        }

        override fun publishCaregiverAlerts(alerts: List<CaregiverEvent>) {
            this.alerts.add(alerts)
        }
    }

    /**
     * Runs [block] with a scope on the test's own scheduler, as foreground work, and cancels it
     * afterwards. [TestScope.backgroundScope] would not do: advanceUntilIdle stops as soon as only
     * background work is left, so a publisher started there would never run.
     */
    private suspend fun TestScope.withWorkScope(block: suspend (CoroutineScope) -> Unit) {
        val work = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            block(work)
        } finally {
            work.cancel()
        }
    }

    @Test
    fun `a company that writes gets a switch on the website without any screen`() = runTest {
        withWorkScope { work ->
            val store = LocalMessageStore().apply { clear() }
            val settings = Recording()
            val catalog = MessageCatalog(work, store, settings, SharingStarted.Eagerly)
            CaregiverPublisher(work, catalog, settings, MutableStateFlow(emptyList())).start()

            store.addOrUpdateMessage(
                Message("m1", "RoamingInfo", "RoamingInfo", null, "Tarif", 1000L, "com.android.mms")
            )
            advanceUntilIdle()

            assertTrue(
                "published: ${settings.senders}",
                settings.senders.last().any { it.name == "RoamingInfo" }
            )
        }
    }

    @Test
    fun `a fault in the log is sent as an alert`() = runTest {
        withWorkScope { work ->
            val now = 10_000_000L
            val settings = Recording()
            val catalog = MessageCatalog(work, LocalMessageStore(), settings, SharingStarted.Eagerly)
            val fault = CaregiverEvent(kind = CaregiverEventKind.MESSENGER_CONNECTION_FAILED, at = now - 1_000L)
            CaregiverPublisher(work, catalog, settings, MutableStateFlow(listOf(fault)), clock = { now }).start()

            advanceUntilIdle()

            assertEquals(listOf(fault), settings.alerts.last())
        }
    }

    @Test
    fun `starting twice publishes once per change`() = runTest {
        withWorkScope { work ->
            val settings = Recording()
            val catalog = MessageCatalog(work, LocalMessageStore(), settings, SharingStarted.Eagerly)
            val publisher = CaregiverPublisher(work, catalog, settings, MutableStateFlow(emptyList()))

            publisher.start()
            publisher.start()
            advanceUntilIdle()

            assertEquals(1, settings.alerts.size)
        }
    }
}
