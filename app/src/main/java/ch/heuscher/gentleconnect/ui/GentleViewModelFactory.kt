package ch.heuscher.gentleconnect.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ch.heuscher.gentleconnect.data.AppGraph
import ch.heuscher.gentleconnect.data.AppStores
import ch.heuscher.gentleconnect.data.Messengers
import ch.heuscher.gentleconnect.data.WearSyncManager
import ch.heuscher.gentleconnect.data.payment.KeystorePaymentAuthGate
import ch.heuscher.gentleconnect.data.support.SupportManager
import java.io.File

/**
 * Builds every view model the launcher's screens use, from the process's own instances.
 *
 * One place, so that no screen constructs a repository of its own — that is how the home screen
 * and the arrival pipeline came to disagree about what was muted. Everything here comes from
 * [AppGraph] and [Messengers].
 */
class GentleViewModelFactory(context: Context) : ViewModelProvider.Factory {

    private val app = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        HomeViewModel::class.java -> HomeViewModel(
            remoteSettingsRepository = Messengers.settings(app),
            messageCatalog = AppGraph.catalog(app),
            messageOutbox = AppGraph.outbox(app),
            restlessness = Restlessness.shared(app),
            speech = Speech.shared(app),
            contactsRepository = AppGraph.contacts(app),
            callLogRepository = AppGraph.callLog(app),
            nudgeMemory = AppGraph.nudgeMemory(app),
            partnerRepository = AppGraph.partners(app),
            seenPicturesStore = AppGraph.seenPictures(app),
            wearSyncManager = WearSyncManager(app),
            whatsAppAvatars = File(app.filesDir, "whatsapp/avatars")
        )
        PaymentViewModel::class.java -> PaymentViewModel(
            remoteSettingsRepository = Messengers.settings(app),
            paymentAuthGate = KeystorePaymentAuthGate(app)
        )
        CaregiverSettingsViewModel::class.java -> CaregiverSettingsViewModel(
            app = app,
            remoteSettingsRepository = Messengers.settings(app),
            catalog = AppGraph.catalog(app),
            caregiverEvents = AppGraph.caregiverLog(app).events
        )
        SupportViewModel::class.java -> SupportViewModel(
            supportManager = SupportManager.getInstance(app),
            restlessness = Restlessness.shared(app)
        )
        AppointmentsViewModel::class.java -> AppointmentsViewModel(
            calendarRepository = AppGraph.calendar(app)
        )
        VoiceViewModel::class.java -> VoiceViewModel(speech = Speech.shared(app))
        CallsViewModel::class.java -> CallsViewModel(
            callLogRepository = AppGraph.callLog(app),
            recordFault = { event -> AppGraph.recordFault(app, event) }
        )
        ConversationViewModel::class.java -> ConversationViewModel(
            messageStore = AppStores.messages,
            catalog = AppGraph.catalog(app),
            outbox = AppGraph.outbox(app),
            contactsRepository = AppGraph.contacts(app),
            mutedConversationsRepository = AppGraph.muted(app),
            conversationRepository = AppGraph.conversations(app),
            recordFault = { event -> AppGraph.recordFault(app, event) },
            onGoal = { Restlessness.shared(app).recordGoal(it) }
        )
        else -> throw IllegalArgumentException("No view model called ${modelClass.name}")
    } as T
}
