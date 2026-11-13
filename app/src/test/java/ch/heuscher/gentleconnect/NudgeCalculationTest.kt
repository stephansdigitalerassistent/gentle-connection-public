package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.LocalMessageStore
import ch.heuscher.gentleconnect.data.RemoteSettingsRepository
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.Nudge
import ch.heuscher.gentleconnect.ui.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NudgeCalculationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var store: LocalMessageStore
    private lateinit var remoteSettingsRepo: RemoteSettingsRepository
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        store = LocalMessageStore()
        store.clear()
        remoteSettingsRepo = RemoteSettingsRepository()
        viewModel = HomeViewModel(
            messageStore = store,
            remoteSettingsRepository = remoteSettingsRepo,
            // A clock that does not tick. The real one emits every minute for ever, and
            // advanceUntilIdle below runs virtual time forward until nothing is scheduled — with
            // an endless timer in the graph that is never, so the test would hang rather than
            // fail, and hang the build with it. Nothing here reads the value: these tests have no
            // call log, so the missed-call branch is never reached.
            nudgeClock = MutableStateFlow(0L)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `singleNudge returns null when enableNudge is false`() = runTest {
        backgroundScope.launch { viewModel.singleNudge.collect() }

        val currentSettings = remoteSettingsRepo.settings.value
        remoteSettingsRepo.updateSettings(currentSettings.copy(enableNudge = false))

        store.addOrUpdateMessage(
            Message("m1", "Lisa", null, null, "Test", 1000L, "com.whatsapp", isRead = false)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.singleNudge.value)
    }

    @Test
    fun `singleNudge selects newest unread message when nudge enabled`() = runTest {
        backgroundScope.launch { viewModel.singleNudge.collect() }

        val msg1 = Message("m1", "Lisa", "+41791112233", null, "Hallo", 1000L, "com.whatsapp", isRead = false)
        val msg2 = Message("m2", "Markus", "+41794445566", null, "Neu", 2000L, "org.threema.app", isRead = false)

        store.addOrUpdateMessage(msg1)
        store.addOrUpdateMessage(msg2)
        testDispatcher.scheduler.advanceUntilIdle()

        val nudge = viewModel.singleNudge.value
        assertNotNull(nudge)
        assertTrue(nudge is Nudge.MessageNudge)
        assertEquals("Markus", (nudge as Nudge.MessageNudge).message.senderName)
    }

    /**
     * The card at the top of her home screen is for a person, not for whoever wrote most recently.
     *
     * On the phone it said "Reinigung! wrote a message" — the dry cleaner — while the message from
     * her family sat below the fold. Companies write far more often than her children do, so
     * recency alone hands them the one card she is most likely to act on.
     *
     * Note what makes the laundry a laundry here: nothing but the absence of a subscriber address.
     * That is also why the fixtures above carry telephone numbers — a message with no address at
     * all is a service address, and this test used to pass only because the nudge did not look.
     */
    @Test
    fun `singleNudge ignores a newer message from a company`() = runTest {
        backgroundScope.launch { viewModel.singleNudge.collect() }

        val fromHerSon = Message("m1", "Markus", "+41794445566", null, "Bin um 5 da", 1000L, "com.whatsapp", isRead = false)
        val fromALaundry = Message("m2", "Reinigung!", "Reinigung", null, "Abholbereit", 2000L, "com.android.mms", isRead = false)

        store.addOrUpdateMessage(fromHerSon)
        store.addOrUpdateMessage(fromALaundry)
        testDispatcher.scheduler.advanceUntilIdle()

        val nudge = viewModel.singleNudge.value
        assertNotNull(nudge)
        assertEquals("Markus", (nudge as Nudge.MessageNudge).message.senderName)
    }

    /**
     * And when the only unread message is a company's, the top of her screen stays empty rather
     * than falling back to it. Quiet is the right answer to "nobody has written".
     */
    @Test
    fun `singleNudge is empty when only a company has written`() = runTest {
        backgroundScope.launch { viewModel.singleNudge.collect() }

        store.addOrUpdateMessage(
            Message("m1", "RoamingInfo", "RoamingInfo", null, "Tarif", 1000L, "com.android.mms", isRead = false)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.singleNudge.value)
    }
}
