package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.reply.MessageReplier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message on its way is the process's, not the screen's.
 *
 * Sends used to run in whatever scope the screen asked from, so pressing home while a text was
 * leaving cancelled it halfway. No database here: with nothing to record into, a send is only the
 * send and the report of a failure, which is exactly the part these tests are about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxTest {

    /** An SMS line that holds every text until [gate] opens, and says what it was asked. */
    private class HeldReplier(private val answer: Boolean = true) : MessageReplier {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<Pair<String, String>>()

        override suspend fun replyWithEmoji(message: Message, emoji: String): Boolean = answer
        override suspend fun replyWithText(message: Message, text: String): Boolean = answer

        override suspend fun sendDirectText(address: String, text: String): Boolean {
            gate.await()
            sent += address to text
            return answer
        }
    }

    /** See CaregiverPublisherTest: foreground work on the test scheduler, cancelled afterwards. */
    private suspend fun TestScope.withProcessScope(block: suspend (CoroutineScope) -> Unit) {
        val process = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            block(process)
        } finally {
            process.cancel()
        }
    }

    private fun outbox(process: CoroutineScope, replier: MessageReplier, faults: MutableList<CaregiverEvent>) =
        Outbox(
            scope = process,
            messageStore = LocalMessageStore(),
            catalog = MessageCatalog(process, LocalMessageStore(), RemoteSettingsRepository(), SharingStarted.Eagerly),
            dependencies = Outbox.Dependencies(
                replier = { replier },
                recordFault = { faults += it }
            )
        )

    private val anna = Recipient(name = "Anna", phoneNumber = "+41791112233")

    @Test
    fun `a text keeps going when the screen that sent it goes away`() = runTest {
        withProcessScope { process ->
            val replier = HeldReplier()
            val faults = mutableListOf<CaregiverEvent>()
            val outbox = outbox(process, replier, faults)

            val screen = launch { outbox.sendNewMessage(anna, "Bin unterwegs") }
            advanceUntilIdle()
            // She pressed home: the screen's coroutine is gone before the line answered.
            screen.cancel()
            replier.gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("+41791112233" to "Bin unterwegs"), replier.sent)
            assertTrue("nothing failed, so nothing is reported: $faults", faults.isEmpty())
        }
    }

    @Test
    fun `a text the line refuses is reported to the caregiver`() = runTest {
        withProcessScope { process ->
            val replier = HeldReplier(answer = false).apply { gate.complete(Unit) }
            val faults = mutableListOf<CaregiverEvent>()
            val outbox = outbox(process, replier, faults)

            val sent = outbox.sendNewMessage(anna, "Hallo")

            assertFalse(sent)
            assertEquals(listOf(CaregiverEventKind.MESSAGE_NOT_SENT), faults.map { it.kind })
        }
    }

    @Test
    fun `somebody with no way to reach them is not sent anything`() = runTest {
        withProcessScope { process ->
            val replier = HeldReplier().apply { gate.complete(Unit) }
            val outbox = outbox(process, replier, mutableListOf())

            val sent = outbox.sendNewMessage(Recipient(name = "Niemand"), "Hallo")

            assertFalse(sent)
            assertTrue(replier.sent.isEmpty())
        }
    }
}
