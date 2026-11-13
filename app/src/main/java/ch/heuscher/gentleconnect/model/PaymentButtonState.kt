package ch.heuscher.gentleconnect.model

/**
 * What the payment card on her home screen is saying, and it says only what is true.
 *
 * There is no timer behind this and nothing counts down. The state is read from the phone
 * itself — from a key in the Android keystore that can only be used while a strong unlock
 * (device PIN, pattern, password, or a Class 3 fingerprint) is still recent enough to count.
 * That is the same test Google Wallet's own card key has to pass at the terminal, so when this
 * says [Ready] the tap will be taken, and when it does not, nothing on her screen pretends
 * otherwise. See data/payment/PaymentAuthGate.
 */
enum class PaymentButtonState {
    /**
     * The phone wants the PIN again before it will pay. The card shows "PIN"; touching it opens
     * the system's own full-screen PIN entry, which is the screen she already knows.
     */
    Idle,

    /**
     * A strong unlock is recent enough that the payment key works right now. Green tick, and the
     * notice under the clock telling her to hold the phone to the terminal.
     */
    Ready,

    /**
     * This phone cannot pay at all: no PIN, pattern or password is set, so there is no strong
     * unlock for a payment key to stand on. Her home screen leaves the card out entirely rather
     * than offering a button that cannot work.
     */
    Unavailable
}
