package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.CaregiverAlerts
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.GroupVisibility
import ch.heuscher.gentleconnect.model.SenderJudgement
import ch.heuscher.gentleconnect.model.StatusPosterVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Keeps the caregiver website in step with the phone: the groups, the company senders and the
 * status posters it can switch off, and the faults worth waking a caregiver for.
 *
 * These used to run in the home screen's view model, so the website only heard about a new group,
 * a new sender or a fault while somebody had the launcher open. A surgery that wrote for the first
 * time while the phone lay on the table had no switch on the website, and a messenger that failed
 * at night was not reported until the morning. This runs for as long as the process does.
 *
 * Every list goes to the repository on every change; the repository drops one it has already
 * sent, so re-emitting is cheap.
 */
class CaregiverPublisher(
    private val scope: CoroutineScope,
    private val catalog: MessageCatalog,
    private val settingsRepository: SettingsRepository,
    private val caregiverEvents: Flow<List<CaregiverEvent>>,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private var started = false

    /** Starts the four publishers, once. Later calls do nothing. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        publishGroups()
        publishSenders()
        publishStatusPosters()
        publishAlerts()
    }

    /**
     * The website's list of groups.
     *
     * Driven off the same names her own screen uses, so a switch on the website is labelled with
     * the words she would read for that conversation — a caregiver deciding to hide "Turnverein"
     * must not have to work out which of three group ids that is. A collector rather than a call at
     * startup, because groups are learnt over time.
     */
    private fun publishGroups() {
        scope.launch {
            catalog.groupDescriptions.collect { names ->
                settingsRepository.publishGroups(GroupVisibility.known(names))
            }
        }
    }

    /**
     * The website's list of company senders.
     *
     * Driven off [MessageCatalog.namedMessages] — everything that has arrived, named as her screen
     * would name it — and deliberately **not** off the visible list. That is the one the companies
     * have already been taken out of when `showCompanyMessages` is off, so publishing from it
     * would send an empty list the moment somebody switched them off, the website would lose every
     * switch, and "hide the companies, except the surgery" would become unsayable exactly when
     * somebody wants to say it.
     */
    private fun publishSenders() {
        scope.launch {
            catalog.namedMessages.collect { messages ->
                settingsRepository.publishSenders(SenderJudgement.known(messages))
            }
        }
    }

    /**
     * The website's short list of status posters.
     *
     * Driven off every stored status, the hidden ones included, for the reason the senders list is
     * not driven off what is drawn: a poster switched off must keep the switch that turns them back
     * on.
     */
    private fun publishStatusPosters() {
        scope.launch {
            combine(
                catalog.namedMessages,
                catalog.namedStoredMessages,
                catalog.remoteSettings
            ) { arrived, stored, settings ->
                StatusPosterVisibility.known(
                    catalog.allStatuses(arrived, stored),
                    StatusPosterVisibility.hiddenKeys(settings.hiddenStatusPosters)
                )
            }.flowOn(Dispatchers.Default).collect { posters ->
                settingsRepository.publishStatusPosters(posters)
            }
        }
    }

    /**
     * The faults worth travelling to the caregiver website, from the log on disk — so a fault
     * written by the pipeline, a receiver or a worker is sent when it happens rather than the next
     * time somebody opens the launcher. What goes is decided by [CaregiverAlerts.toAlerts] and
     * nothing else.
     */
    private fun publishAlerts() {
        scope.launch {
            caregiverEvents.collect { events ->
                settingsRepository.publishCaregiverAlerts(CaregiverAlerts.toAlerts(events, clock()))
            }
        }
    }
}
