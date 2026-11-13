package ch.heuscher.gentleconnect.data.threema

/**
 * Decides whether the embedded Threema client may be started on its own.
 *
 * There is exactly one rule here, and it exists because of what [ThreemaClient]'s connection
 * loop does when it finds no stored identity: it calls [ThreemaRegistrar.ensureIdentity], which
 * registers a **brand new Threema ID** against the serial licence. That is the right behaviour
 * when someone has deliberately asked for an identity, and completely the wrong behaviour as a
 * side effect of the app starting up.
 *
 * A silently self-registered ID is not a harmless extra: it is an address none of her contacts
 * have, it consumes a licence, and — worst — it looks like everything is working. The app would
 * connect, report Connected, and receive nothing forever, because nobody in the world knows to
 * write to it.
 *
 * So: starting the client is never what brings an identity into existence. Provisioning happens
 * on purpose, from the caregiver surface, and only then does the client run.
 */
object ThreemaStartupPolicy {

    /**
     * @param hasIdentity whether [ThreemaIdentityRepository.hasIdentity] already holds one.
     * @return true when the client may be started without registering anything.
     */
    fun shouldStartAutomatically(hasIdentity: Boolean): Boolean = hasIdentity

    /**
     * Whether a brand new identity may be registered for this phone.
     *
     * Never once an identity has existed here and has not been deliberately removed. "No identity"
     * then does not mean a phone nobody has set up — it means hers cannot be read: the Android
     * Keystore behind the encrypted store failed, and the store fell back to an empty file.
     * Registering then would give her an address none of her family have and, if the Keystore
     * never recovers, lose the one they do have, silently. A caregiver has to decide that: restore
     * the account from a backup, or unlink it, which is what makes a fresh one allowed again.
     *
     * @param hasIdentity whether [ThreemaIdentityRepository.hasIdentity] holds one now.
     * @param provisionedBefore whether an identity was ever stored here and not since unlinked.
     */
    fun mayRegisterNewIdentity(hasIdentity: Boolean, provisionedBefore: Boolean): Boolean =
        !hasIdentity && !provisionedBefore

    /**
     * Whether unlinking has anything to remove.
     *
     * An unreadable identity has to count. [mayRegisterNewIdentity] sends the caregiver here to
     * unlink it, and if unlinking answered "nothing linked" because the identity cannot be read,
     * the marker would stand for good and the phone could never be given an identity again.
     */
    fun hasSomethingToUnlink(hasIdentity: Boolean, provisionedBefore: Boolean): Boolean =
        hasIdentity || provisionedBefore
}
