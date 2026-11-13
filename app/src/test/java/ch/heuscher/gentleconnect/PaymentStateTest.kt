package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.RemoteSettingsRepository
import ch.heuscher.gentleconnect.data.payment.PaymentAuthGate
import ch.heuscher.gentleconnect.model.PaymentButtonState
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.ui.PaymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the payment card is allowed to say.
 *
 * The rule these are all about: the card may only ever repeat what the phone answered. There is
 * no countdown behind it and nothing is ever assumed ready — a green card that is wrong is worse
 * than no card at all, because it is wrong at the till, with a queue behind her.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentStateTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepo: RemoteSettingsRepository
    private lateinit var gate: FakeGate
    private lateinit var viewModel: PaymentViewModel

    /** A phone that answers however the test says it answers. */
    private class FakeGate(var answer: PaymentButtonState) : PaymentAuthGate {
        var reads = 0
        override suspend fun state(): PaymentButtonState {
            reads++
            return answer
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsRepo = RemoteSettingsRepository()
        gate = FakeGate(PaymentButtonState.Idle)
        // Handed the gate afterwards rather than built with it, so that construction does not
        // already ask the phone once and every count below starts from nothing.
        viewModel = PaymentViewModel(remoteSettingsRepository = settingsRepo)
        viewModel.paymentAuthGate = gate
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun enableWallet(enabled: Boolean) {
        settingsRepo.updateSettings(settingsRepo.settings.value.copy(enableWallet = enabled))
    }

    @Test
    fun `the payment card is off until a caregiver asks for it`() {
        assertFalse("enableWallet must default to false", RemoteSettings().enableWallet)
    }

    @Test
    fun `the card repeats what the phone says`() = runTest {
        enableWallet(true)

        gate.answer = PaymentButtonState.Ready
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PaymentButtonState.Ready, viewModel.paymentButtonState.value)

        // The unlock window runs out with nobody touching anything. Nothing in the app decides
        // this; the next read simply comes back different, and the card follows it.
        gate.answer = PaymentButtonState.Idle
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PaymentButtonState.Idle, viewModel.paymentButtonState.value)
    }

    @Test
    fun `a phone with no screen lock is never called ready`() = runTest {
        enableWallet(true)
        gate.answer = PaymentButtonState.Unavailable

        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(PaymentButtonState.Unavailable, viewModel.paymentButtonState.value)
    }

    @Test
    fun `switching the button off leaves no ready state behind`() = runTest {
        enableWallet(true)
        gate.answer = PaymentButtonState.Ready
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PaymentButtonState.Ready, viewModel.paymentButtonState.value)

        // A caregiver unticks it on the website while the card is lit. The phone is not even
        // asked any more, and what it last said is not kept: turning the button on again a week
        // later must not bring a week-old "ready" back with it.
        val readsBefore = gate.reads
        enableWallet(false)
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(PaymentButtonState.Unavailable, viewModel.paymentButtonState.value)
        assertEquals("the gate is not consulted when the button is off", readsBefore, gate.reads)
    }

    @Test
    fun `the phone going to sleep ends the payment`() = runTest {
        enableWallet(true)
        gate.answer = PaymentButtonState.Ready
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PaymentButtonState.Ready, viewModel.paymentButtonState.value)

        // She presses the side key on the way out of the shop. The phone is still strongly
        // unlocked and the keystore still says Ready — that is not something the app can take
        // back — but the card stops standing there green, and a sleeping phone is locked, which
        // is the part that actually stops a terminal taking anything.
        viewModel.finishPayment()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.paymentFinished.value)
        assertEquals(PaymentButtonState.Ready, gate.answer)
        assertEquals(PaymentButtonState.Idle, viewModel.paymentButtonState.value)
    }

    @Test
    fun `the next payment starts from a clean card`() = runTest {
        enableWallet(true)
        gate.answer = PaymentButtonState.Ready
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.finishPayment()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PaymentButtonState.Idle, viewModel.paymentButtonState.value)

        // The window runs out. From here on there is nothing left to suppress, so the latch goes
        // — otherwise her next trip to the shop would open with a card greyed out by last week.
        gate.answer = PaymentButtonState.Idle
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.paymentFinished.value)

        gate.answer = PaymentButtonState.Ready
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PaymentButtonState.Ready, viewModel.paymentButtonState.value)
    }

    @Test
    fun `pressing the card again lights it, even inside the same window`() = runTest {
        enableWallet(true)
        gate.answer = PaymentButtonState.Ready
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.finishPayment()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PaymentButtonState.Idle, viewModel.paymentButtonState.value)

        // Two things in the same shop, a minute apart. She wakes the phone, presses the card,
        // types her PIN, and comes back to a green card — the sleep that ended the first
        // payment must not outlive it.
        viewModel.beginPaymentAuth()
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PaymentButtonState.Ready, viewModel.paymentButtonState.value)
    }

    @Test
    fun `pressing a lit card says how to end the payment instead of asking again`() = runTest {
        enableWallet(true)
        gate.answer = PaymentButtonState.Ready
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse("unasked, the notice says one thing only", viewModel.paymentSleepHintVisible.value)

        viewModel.showPaymentSleepHint()
        assertTrue(viewModel.paymentSleepHintVisible.value)

        // And it goes with the notice it lives in, rather than waiting under the clock for a
        // payment three days from now.
        gate.answer = PaymentButtonState.Idle
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.paymentSleepHintVisible.value)
    }

    @Test
    fun `reading the message box changes nothing about the payment`() = runTest {
        enableWallet(true)
        gate.answer = PaymentButtonState.Ready
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.showPaymentSleepHint()

        // She presses "Verstanden", or it fades out while she is holding the phone to the
        // terminal. Either way the card is still lit: nothing in a message box can take a
        // keystore window back, and telling her it had would be the one lie that matters here.
        viewModel.dismissPaymentSleepHint()

        assertFalse(viewModel.paymentSleepHintVisible.value)
        assertEquals(PaymentButtonState.Ready, viewModel.paymentButtonState.value)
        assertFalse(viewModel.paymentFinished.value)
    }

    @Test
    fun `a payment cannot be finished before it is ready`() = runTest {
        enableWallet(true)
        gate.answer = PaymentButtonState.Idle

        // The screen going dark with no payment in progress — the ordinary case, many times a
        // day. It must leave nothing behind that greys out the next honest Ready.
        viewModel.finishPayment()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.paymentFinished.value)

        gate.answer = PaymentButtonState.Ready
        viewModel.refreshPaymentState()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PaymentButtonState.Ready, viewModel.paymentButtonState.value)
    }
}
