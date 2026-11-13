package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.LocalMessageStore
import ch.heuscher.gentleconnect.data.RemoteSettingsRepository
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.GroupVisibility
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.Nudge
import ch.heuscher.gentleconnect.model.StatusBroadcast
import ch.heuscher.gentleconnect.ui.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins down what the screens are handed from the message flows, so that moving those flows out of
 * the view model (see docs/mainviewmodel-refactor-plan.md) can be shown to change nothing.
 *
 * Driven only through the view model's public flows and a message store, which is everything a
 * plain JVM test can build: no database, no address book, no messenger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageFlowCharacterizationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var store: LocalMessageStore
    private lateinit var settings: RemoteSettingsRepository
    private lateinit var viewModel: HomeViewModel

    private val family = GroupIdentity("ABCD1234", "0a0b0c0d")
    private val status = GroupIdentity("41791112233@s.whatsapp.net", StatusBroadcast.JID)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        store = LocalMessageStore()
        store.clear()
        settings = RemoteSettingsRepository()
        viewModel = HomeViewModel(
            messageStore = store,
            remoteSettingsRepository = settings,
            // See NudgeCalculationTest: a clock that never ticks, so advanceUntilIdle can finish.
            nudgeClock = MutableStateFlow(0L)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.watchEverything() {
        backgroundScope.launch { viewModel.visibleMessages.collect() }
        backgroundScope.launch { viewModel.unreadMessages.collect() }
        backgroundScope.launch { viewModel.messagesMarked.collect() }
        backgroundScope.launch { viewModel.statusPosts.collect() }
        backgroundScope.launch { viewModel.singleNudge.collect() }
    }

    private fun person(id: String, name: String, at: Long, read: Boolean = false) =
        Message(id, name, "+4179111${id.hashCode().mod(10000).toString().padStart(4, '0')}", null, "Hallo", at, "com.whatsapp", isRead = read)

    @Test
    fun `what arrives is listed newest first and marks the message button`() = runTest {
        watchEverything()

        store.addOrUpdateMessage(person("m1", "Lisa", 1000L))
        store.addOrUpdateMessage(person("m2", "Markus", 2000L))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("m2", "m1"), viewModel.visibleMessages.value.map { it.id })
        assertEquals(listOf("m2", "m1"), viewModel.unreadMessages.value.map { it.id })
        assertTrue(viewModel.messagesMarked.value)
    }

    @Test
    fun `nothing unread leaves the message button unmarked and the card empty`() = runTest {
        watchEverything()

        store.addOrUpdateMessage(person("m1", "Lisa", 1000L, read = true))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("m1"), viewModel.visibleMessages.value.map { it.id })
        assertFalse(viewModel.messagesMarked.value)
        assertNull(viewModel.singleNudge.value)
    }

    @Test
    fun `a status goes to the pictures and not to the messages`() = runTest {
        watchEverything()

        val posted = Message(
            "s1", "Anna", "+41791112233", null, "", 3000L, "com.whatsapp",
            groupIdentity = status
        )
        store.addOrUpdateMessage(posted)
        store.addOrUpdateMessage(person("m1", "Lisa", 1000L))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("m1"), viewModel.visibleMessages.value.map { it.id })
        assertEquals(listOf("s1"), viewModel.statusPosts.value.map { it.id })
    }

    @Test
    fun `a group the caregiver switched off is gone from the list and the card`() = runTest {
        watchEverything()

        settings.updateSettings(
            settings.settings.value.copy(hiddenGroups = listOf(GroupVisibility.keyOf(family)))
        )
        store.addOrUpdateMessage(person("m1", "Lisa", 1000L))
        store.addOrUpdateMessage(
            Message(
                "g1", "Markus", "+41794445566", null, "Wer kommt?", 2000L, "org.threema.app",
                groupIdentity = family
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("m1"), viewModel.visibleMessages.value.map { it.id })
        val nudge = viewModel.singleNudge.value
        assertTrue(nudge is Nudge.MessageNudge)
        assertEquals("m1", (nudge as Nudge.MessageNudge).message.id)
    }

    @Test
    fun `switching the group back on brings it back`() = runTest {
        watchEverything()

        settings.updateSettings(
            settings.settings.value.copy(hiddenGroups = listOf(GroupVisibility.keyOf(family)))
        )
        store.addOrUpdateMessage(
            Message(
                "g1", "Markus", "+41794445566", null, "Wer kommt?", 2000L, "org.threema.app",
                groupIdentity = family
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.visibleMessages.value.isEmpty())

        settings.updateSettings(settings.settings.value.copy(hiddenGroups = emptyList()))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("g1"), viewModel.visibleMessages.value.map { it.id })
    }
}
