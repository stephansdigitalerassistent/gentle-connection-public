package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.KnownBluetoothDevice
import ch.heuscher.gentleconnect.model.KnownGroup
import ch.heuscher.gentleconnect.model.KnownPoster
import ch.heuscher.gentleconnect.model.KnownSender
import ch.heuscher.gentleconnect.model.RemoteSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing caregiver remote configuration settings.
 */
interface SettingsRepository {
    val settings: StateFlow<RemoteSettings>
    fun updateSettings(newSettings: RemoteSettings)
    fun pairWithCode(inputCode: String): Boolean
    fun setFavorites(contacts: List<Contact>)
    fun setTextSizeMultiplier(multiplier: Float)
    fun setPhotoSizeMultiplier(multiplier: Float)
    fun setSpeechRate(rate: Float)
    fun setDarkMode(enabled: Boolean)
    suspend fun generateTemporaryPairingCode(): String

    /**
     * Takes away every caregiver's access to this phone. Nothing to take away for a repository
     * that is not connected to anything.
     */
    fun removeAllCaregivers() = Unit

    /**
     * Tell the caregiver website who is starred on the phone, so it has people to arrange.
     *
     * The website cannot read her address book and must never be able to. What it gets is this:
     * the handful of contacts somebody has starred, by id and display name only — no telephone
     * numbers, no photographs, and none of the 1400-odd people who are not favourites. Enough to
     * put names against the arrows, and nothing beyond that.
     *
     * A no-op off Firestore.
     */
    fun publishStarredContacts(contacts: List<Contact>) = Unit

    /**
     * Tell the caregiver website which several-people conversations exist, so it has switches.
     *
     * The website cannot see her messages and must never be able to. What it gets is what her own
     * screen calls each group and the phone's opaque key for it — see
     * [ch.heuscher.gentleconnect.model.KnownGroup] — which is exactly enough to draw a list of
     * names with a switch beside each, and nothing about who is in them or what was said.
     *
     * Written whole, including the empty list: a group that has gone quiet must stop appearing on
     * the website, or its switch outlives it and hides a group somebody later re-joins.
     *
     * A no-op off Firestore.
     */
    fun publishGroups(groups: List<KnownGroup>) = Unit

    /**
     * Tell the caregiver website which senders the phone has filed as companies, so it has
     * switches to disagree with.
     *
     * The same bargain as [publishGroups]: what travels is the name her own screen shows and the
     * phone's key for the address — see [ch.heuscher.gentleconnect.model.KnownSender] — and
     * nothing that was said. Only the ones filed as companies, which is what keeps her family's
     * numbers out of it: anybody in her address book is already a person and needs no correction.
     *
     * Written whole, including the empty list: a sender that has stopped writing must stop
     * appearing on the website, or its switch outlives it.
     *
     * A no-op off Firestore.
     */
    fun publishSenders(senders: List<KnownSender>) = Unit

    /**
     * Tell the caregiver website whose WhatsApp statuses she receives, so it has switches.
     *
     * Only a short list — see [ch.heuscher.gentleconnect.model.StatusPosterVisibility.known] —
     * and only name, key and how many: no pictures, no captions. Written whole, like the groups.
     *
     * A no-op off Firestore.
     */
    fun publishStatusPosters(posters: List<KnownPoster>) = Unit

    /**
     * Tell the caregiver website what has gone wrong, so it can reach somebody who is not here.
     *
     * The caregiver log is written and kept on the phone, which means it is read only by whoever
     * is holding the phone — and the person who needs it is by definition somewhere else. This is
     * the subset that travels: see [ch.heuscher.gentleconnect.model.CaregiverAlerts.toAlerts] for
     * which faults those are and why the rest stay behind.
     *
     * Nothing new is exposed by this that the same reader could not already see. What travels is
     * exactly what the log holds — already anonymised on the way in, so a name that reaches here
     * is a name she herself reads on her own screen, never a messenger identity — and the people
     * who can read it are the caregivers already linked to this phone.
     *
     * A no-op off Firestore.
     */
    fun publishCaregiverAlerts(alerts: List<CaregiverEvent>) = Unit

    /**
     * Tell the caregiver website which Bluetooth devices are paired or regularly seen nearby.
     *
     * A no-op off Firestore.
     */
    fun publishBluetoothDevices(devices: List<KnownBluetoothDevice>) = Unit
}
