package ch.heuscher.gentleconnect.data

/**
 * The two stores that have to outlive any single screen.
 *
 * Her mail arrives from a background worker that runs whether or not the launcher is open, so the
 * repository holding it cannot belong to a ViewModel. These previously hung off the notification
 * listener's companion object, which made a message store's lifetime depend on a permission she
 * had to grant in the system settings. The listener is gone; the stores are not.
 */
object AppStores {

    /** Messages from the Threema bridge and from her SMS inbox. */
    val messages: LocalMessageStore by lazy { LocalMessageStore() }

    /** Letters fetched over IMAP by [ch.heuscher.gentleconnect.service.MailSyncWorker]. */
    val mail: MailRepository by lazy { MailRepository() }
}
