package ch.heuscher.gentleconnect.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.heuscher.gentleconnect.data.SettingsRepository
import ch.heuscher.gentleconnect.data.payment.PaymentAuthGate
import ch.heuscher.gentleconnect.data.payment.PaymentPinHint
import ch.heuscher.gentleconnect.model.PaymentButtonState
import ch.heuscher.gentleconnect.model.RemoteSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The payment card on the home screen, and the two message boxes that go with it.
 *
 * Its own view model because nothing else on the screen shares any of this: the card asks the
 * phone, through [PaymentAuthGate], whether a terminal would take a tap now, and says what it is
 * told. It used to be two hundred lines in the middle of the home screen's view model.
 */
class PaymentViewModel(
    remoteSettingsRepository: SettingsRepository,
    /** The gate that answers for the phone. Null where there is none — in tests, until set. */
    paymentAuthGate: PaymentAuthGate? = null
) : ViewModel() {

    private val remoteSettings: StateFlow<RemoteSettings> = remoteSettingsRepository.settings

    /** The gate that answers for the phone. Settable so a test can hand one in. */
    var paymentAuthGate: PaymentAuthGate? = paymentAuthGate

    /**
     * What the payment card says, read from the phone rather than remembered. See
     * [PaymentAuthGate]: every value here is the answer to "would the terminal take a tap now?",
     * asked again on each refresh, so nothing on her screen can outlive the truth behind it.
     */
    private val _paymentButtonState = MutableStateFlow(PaymentButtonState.Unavailable)
    val paymentButtonState: StateFlow<PaymentButtonState> = _paymentButtonState.asStateFlow()

    /**
     * Whether this payment is over, which happens one way: the phone went dark with the card
     * still lit. There is no button for it. A sleeping phone is a locked phone, a locked phone
     * will not pay anybody, and that is a thing she can do with the key under her thumb rather
     * than a thing to find on a screen.
     *
     * While it is set, a [PaymentButtonState.Ready] is shown as [PaymentButtonState.Idle]: the
     * card goes back to asking for her PIN instead of standing there green for the rest of the
     * window, repeating "hold the phone to the terminal now" at somebody who has already paid.
     *
     * This is the one place the card says less than the phone knows, and it is the safe
     * direction of the two. Over-stating readiness fails at the till, in public, with a queue
     * behind her; under-stating it costs one PIN entry that would have worked without. What it
     * is not is a lock: nothing here can take a strong unlock back from the phone, so Wallet
     * would still pay on a tap. The card is an invitation, not a switch.
     *
     * Cleared when she starts a payment ([beginPaymentAuth]) and as soon as the phone itself
     * leaves Ready, so the next payment starts from a clean card.
     */
    private val _paymentFinished = MutableStateFlow(false)
    val paymentFinished: StateFlow<Boolean> = _paymentFinished.asStateFlow()

    private var paymentWatchJob: Job? = null

    /**
     * Read the payment state once, now.
     *
     * Called when she comes back to the home screen and after the PIN screen closes — including
     * when she backed out of it, because "she cancelled" is not ours to interpret either.
     */
    fun refreshPaymentState() {
        viewModelScope.launch { readPaymentState() }
    }

    /**
     * Keep the card honest for as long as she can see it.
     *
     * The state can go stale with nothing happening in the app at all — the unlock window simply
     * runs out — so while the home screen is in front the phone is asked again every few seconds.
     * Stopped in onPause: a card nobody is looking at does not need watching, and the keystore
     * should not be woken for it.
     */
    fun watchPaymentState(watching: Boolean) {
        paymentWatchJob?.cancel()
        paymentWatchJob = null
        if (!watching) return
        paymentWatchJob = viewModelScope.launch {
            while (true) {
                readPaymentState()
                delay(PAYMENT_POLL_MILLIS)
            }
        }
    }

    /**
     * The phone has gone to sleep with the card lit. See [_paymentFinished].
     *
     * Deliberately not "she has paid": no app outside the wallet is told that a terminal took a
     * payment — there is no such API, and the one signal that might stand in for it, a Wallet
     * transaction notification, would need permission to read every notification on the phone
     * and is not reliably posted anyway. The screen going dark is the nearest honest thing, and
     * it is also the one she is told about — see [_paymentSleepHintVisible].
     */
    fun finishPayment() {
        // Nothing to finish, and nothing to wake the keystore for. The screen goes dark dozens of
        // times a day with no payment anywhere near it; only a card that is actually lit has
        // anything to put out.
        if (_paymentButtonState.value != PaymentButtonState.Ready) return
        _paymentFinished.value = true
        refreshPaymentState()
    }

    /**
     * A payment is beginning: she pressed the card. Called on the way into the PIN screen and
     * again on the way out of it, because either way she is starting a payment, not ending one.
     */
    fun beginPaymentAuth() {
        _paymentFinished.value = false
        _paymentSleepHintVisible.value = false
    }

    /**
     * Whether the message box saying how to stop being able to pay is open.
     *
     * Shown when she presses a card that is already lit. That press used to open the PIN screen
     * for a second time, which does nothing she needs — the phone is already unlocked enough to
     * pay. So it answers the question she is more likely to be asking by pressing a green card
     * she did not expect: how do I turn this off again. The words are in
     * [ch.heuscher.gentleconnect.ui.components.PaymentSleepDialog]; the notice under the clock
     * keeps none of them, so that what it does say can be read at a glance at a till.
     */
    private val _paymentSleepHintVisible = MutableStateFlow(false)
    val paymentSleepHintVisible: StateFlow<Boolean> = _paymentSleepHintVisible.asStateFlow()

    /** She pressed the lit card. Tell her how a payment ends. */
    fun showPaymentSleepHint() {
        _paymentSleepHintVisible.value = true
    }

    /**
     * She has read it, or it ran out of patience waiting for her to.
     *
     * The same nothing either way: the card is still lit, because nothing in a message box can
     * put a keystore window back, and the phone going to sleep is still what ends it.
     */
    fun dismissPaymentSleepHint() {
        _paymentSleepHintVisible.value = false
    }

    /**
     * The hint shown before the PIN screen while its message box is open, or null when it is not.
     * See [PaymentPinHint] for where it comes from and MainActivity.launchPaymentPinAuth for when.
     */
    private val _paymentPinHintShown = MutableStateFlow<String?>(null)
    val paymentPinHintShown: StateFlow<String?> = _paymentPinHintShown.asStateFlow()

    fun showPaymentPinHint(hint: String) {
        _paymentPinHintShown.value = hint
    }

    fun dismissPaymentPinHint() {
        _paymentPinHintShown.value = null
    }

    fun getPaymentPinHint(context: Context): String? = PaymentPinHint(context).get()

    fun setPaymentPinHint(context: Context, hint: String) = PaymentPinHint(context).set(hint)

    private suspend fun readPaymentState() {
        val gate = paymentAuthGate
        // Switched off by a caregiver, or no context yet: nothing is drawn, and nothing is left
        // behind from when it was. A Ready held over from before the switch would come back the
        // moment somebody turned the button on again.
        val actual = if (gate == null || !remoteSettings.value.enableWallet) {
            PaymentButtonState.Unavailable
        } else {
            gate.state()
        }
        // The latch can only ever outlive a Ready. Once the phone says the window is over there
        // is nothing left to suppress, and leaving it set would grey out the first honest Ready
        // of her next trip to the shop.
        if (actual != PaymentButtonState.Ready) {
            _paymentFinished.value = false
            // The notice is gone, so the line inside it goes too rather than waiting under the
            // clock for a payment three days from now.
            _paymentSleepHintVisible.value = false
        }
        _paymentButtonState.value =
            if (actual == PaymentButtonState.Ready && _paymentFinished.value) {
                PaymentButtonState.Idle
            } else {
                actual
            }
    }

    // Last, so every flow above exists before the first read writes to them.
    init {
        // Once, straight away, so the card is already right the first time the home screen is
        // drawn instead of grey until the first poll comes round.
        if (paymentAuthGate != null) refreshPaymentState()
    }

    private companion object {
        /**
         * How often the payment card asks the phone whether a tap would still be taken, while
         * the home screen is in front. Short enough that the card goes back to asking for the
         * PIN while she is still looking at it, rather than a minute after the window closed.
         */
        const val PAYMENT_POLL_MILLIS = 5_000L
    }
}
