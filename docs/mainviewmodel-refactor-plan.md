# Refactoring plan: breaking up `MainViewModel`

Status: all six phases are implemented — phases 0–4 in PR #7, phases 5 and 6 in PR #8.
`ui/MainViewModel.kt` is gone; what was left of it is `ui/HomeViewModel.kt`.

What changed from the proposal while implementing it:

- `Messengers` and `AppStores` stay as they are; `AppGraph` sits beside them instead of absorbing
  them.
- `MutedConversationsRepository` and `NudgeMemory` also follow their preferences file through a
  change listener, so a stray second instance (a receiver) cannot disagree with the shared one.
- `MessengerRuntime.start()` starts `ArrivalPipeline` itself, so the "collectors before clients"
  rule holds on the screen's path too, not only the service's.
- Phases 3 and 4 shipped together, as the plan allowed.
- Phase 6 split differently from the table below where the code demanded it:
  - The support button and restlessness: `SupportViewModel`, with the restlessness tracker as one
    shared `Restlessness` object, because calls placed from the home screen count towards it too.
  - The read-aloud voice and voice-message playback: one shared `Speech` (they stop each other, so
    they cannot live apart), with `VoiceViewModel` as the screens' handle on it.
  - The calendar: `AppointmentsViewModel`.
  - The calls list and blocking: `CallsViewModel`. Placing a call stays in `HomeViewModel`, because
    ringing somebody back is what clears the home screen's missed-call card.
  - `forwardRecipients` and `writableRecipients` stay in `HomeViewModel`; they are lists of people,
    built from the same flows as the home screen's.
  - All view models are built by one `GentleViewModelFactory` from `AppGraph`.

## The problem in one paragraph

`ui/MainViewModel.kt` is 4,567 lines. It renders the screens, and it also runs the app.
`initializeReplier()` builds about twenty dependencies into nullable `var`s, starts
`MessengerService`, starts the Threema and WhatsApp clients, attaches the only collectors for
connection state, contact matches and WhatsApp groups, publishes groups, senders, status posters
and alerts to the caregiver website, and owns every outbound send. Meanwhile `Messengers`,
`AppStores`, `ArrivalPipeline` and `MessengerService` already own the same things at process
level. The migration stopped halfway. The screen and the pipeline each build their own
`NudgeMemory`, `MutedConversationsRepository`, `ConversationRepository`,
`CaregiverLogRepository` and `ThreemaIdentityRepository`, and each runs its own `combine`
graph over the same tables. Anything that holds state in memory now exists twice, and anything
the view model starts only runs while an activity is alive.

## Bugs that come from the split (verified in the code)

| # | Symptom | Cause |
|---|---|---|
| B1 | Muting a conversation on the phone does not stop it being announced until the process restarts. | `MutedConversationsRepository` caches the set in a `MutableStateFlow` that is read once at construction. The view model mutes its own instance (`MainViewModel.kt:2159`), but `ArrivalPipeline` checks a different instance (`ArrivalPipeline.kt:141`). |
| B2 | After a cold start with the screen off (boot, update, process reclaimed), Threema group messages from members it has not seen yet are rejected. | `seedThreemaContactsIfConnected()` only runs inside view model collectors. `MessengerService` connects the client without them. |
| B3 | Under the same conditions, `MESSENGER_CONNECTION_FAILED` is never logged. (Contact-match results have the same flaw, but in practice a caregiver starts a match from the UI, so the view model is usually alive.) | The only collectors of `client.connectionState` and `client.contactMatches` live in `startThreemaClientIfProvisioned()` in the view model. |
| B4 | Faults logged by the pipeline, receivers, `MailSyncWorker`, `CallPlacer`, `VoipCallManager` and `SupportManager` only reach the caregiver website once someone opens the launcher. | `publishAlertsToCaregivers` is driven by a collector in the view model. |
| B5 | The same for new groups, company senders and status posters: the caregiver cannot get a switch for them while the screen is off. | `startPublishingGroups/Senders/StatusPosters` run in `viewModelScope`. |
| B6 | Fragile rather than visibly broken: a missed call handled from the notification or the in-call screen relies on `onResume` calling `refreshMissedCallHandling()` to clear its card. Any path that shows home without going through `onResume` shows a stale card. | `CallActionReceiver` and `GentlePhoneCallService` write to a fresh `NudgeMemory`, but the view model keeps its own `missedCallsDealtWithUpTo` flow and only reads the file again when asked. |
| B7 | Dead and misleading state: the view model still loads and stores `announcedUpTo` (`:248`, `:2109`), but nothing reads it any more. Announcing moved to `ArrivalPipeline`. | Migration leftover. |
| B8 | Two places start the clients: the view model and `MessengerService.onStartCommand`. Each has its own copy of the startup ordering rule ("call manager before client"). `saveThreemaIdentity` re-implements it a third time. | Migration leftover. Any future ordering fix has to be made three times. |

If you have your own list of bugs, map each one to a row here or add it. Every phase below
names the rows it closes.

## Target shape

```
GentleApplication.onCreate
   └── AppGraph (one per process, built lazily from the application context)
         ├── stores:      AppDatabase, ConversationRepository, ThreemaGroupRepository,
         │                CaregiverLogRepository, MutedConversationsRepository,
         │                NudgeMemory (observable), SeenPictures, Settings, Mail, LocalMessageStore
         ├── messengers:  ThreemaIdentityRepository, ThreemaClient, VoipCallManager, WhatsAppClient
         ├── MessageCatalog      – the shared derived flows (links, names, groups, visibility)
         ├── MessengerRuntime    – start/stop/reprovision clients + their always-on collectors
         ├── CaregiverPublisher  – groups, senders, status posters, alerts → website
         └── Outbox (MessageSender) – every outbound send, reaction, receipt, forward

MessengerService ── starts ──> ArrivalPipeline, MessengerRuntime, CaregiverPublisher
MainActivity ─────> feature view models, constructed from AppGraph and holding UI state only
```

The rules this layout enforces:

1. **One instance per process for anything with state.** All of them come from `AppGraph`.
   `Messengers` and `AppStores` become sections of it (keep them as thin delegating facades
   during the migration so call sites move gradually).
2. **Anything that must happen while the screen is off belongs to the service side.** View
   models only observe, and turn user intents into calls on process-level services.
3. **View models take non-null constructor parameters.** There is no `initializeReplier`. A
   test builds one with fakes, which is how `PaymentStateTest` and `NudgeCalculationTest` already
   want to work.
4. **No DI framework.** The project has none, and a manual `AppGraph` is enough for this size.
   Adding Hilt would mean kapt/ksp, Application/Activity annotations and a larger diff, and it
   would not fix any of the bugs above.

## Phases

Each phase ships on its own, changes no behaviour except the bugs it names, and leaves
`MainViewModel`'s public API alone until phase 6, so screens are not touched before then.

### Phase 0 – Safety net (small)

- Add characterization tests around the parts that move: arrival announcing with
  mute and quiet hours, missed-call dealt-with, group-reply decisions, send-state transitions
  in `recordOutgoing`/`sendAndReport`. Use fakes for the DAOs. Most of the logic is already in
  pure `model/` objects; the tests pin down how it is wired.
- Add a test that fails today for B1: mute through one consumer, check `isMuted` through the
  other. It goes green in phase 1.

### Phase 1 – One graph (closes B1, B6, B7)

- Introduce `data/AppGraph.kt`: `object AppGraph { fun init(app: Context) }` with lazy,
  `@Synchronized` accessors. Call `init` from `GentleApplication.onCreate`.
- Move these into it: `ConversationRepository`, `ThreemaGroupRepository`,
  `CaregiverLogRepository`, `MutedConversationsRepository`, `NudgeMemory`, `SeenPictures`,
  `PartnerRepository`, `BridgeMessageRepository`, `ContactsRepository`, `CallLogRepository`,
  `CalendarRepository`.
- Turn `NudgeMemory` into a store that can be observed: `StateFlow<Long>` for
  `missedCallsDealtWithUpTo` and `announcedUpTo`, written through and kept monotonic inside
  the store (it currently does `MissedCallNotice.dealtWith` at every call site). With that,
  `refreshMissedCallHandling()` and the view model's private copy can go.
- Switch `ArrivalPipeline`, `CallActionReceiver`, `GentlePhoneCallService`, the SMS/MMS
  receivers, `SupportManager`, `CallPlacer`, `VoipCallManager` and `MailSyncWorker` to the
  graph instances.
- In `MainViewModel`, the defaults of the nullable constructor parameters come from
  `AppGraph`. They stay nullable for now so the tests compile. Delete `announcedUpTo`.
- Replace every `ThreemaIdentityRepository(context)` in the view model and in
  `ThreemaRegistrar` with `Messengers.threemaIdentity()`.

Risk: low. These are mostly constructor moves. Watch for `AppDatabase.getInstance` called
before `Application.onCreate` (content providers); keep it lazy.

### Phase 2 – `MessengerRuntime` owns the clients (closes B2, B3, B8)

- New `data/MessengerRuntime.kt` (process scope, `SupervisorJob + Dispatchers.IO`). It takes
  over from the view model:
  - `startThreemaClientIfProvisioned`, and the startup ordering now in
    `MessengerService.onStartCommand` (call manager → Threema → WhatsApp), in one place;
  - the `connectionState` collector, including the caregiver event on `Failed` and contact
    seeding on `Connected`;
  - the `contactMatches` collector and the `threema_match` upsert, exposing
    `contactSyncOutcome: StateFlow`;
  - `seedThreemaContacts`, fed from the graph's repositories (needs `MessageCatalog`'s contact
    list; until phase 4, read the same sources directly);
  - WhatsApp `linkState`, `pairingCode`, `error` and the group-name/member maps, plus
    `requestPairing`, `unlink`, and `reprovisionThreema(id, key)`, which replaces the client
    juggling in `saveThreemaIdentity`.
- `MessengerService.onStartCommand` calls `ArrivalPipeline.start` and then
  `MessengerRuntime.start`. Order is preserved: the pipeline collectors attach before any
  client connects.
- In the view model, `initializeReplier` keeps only `MessengerService.start(context)`. The
  WhatsApp/Threema state flows become pass-throughs to `MessengerRuntime`.

Risk: medium. This is where startup ordering lives. Test it on a device: cold boot with the
screen off, then an incoming Threema group message from an unknown member, a Threema call and
a WhatsApp message. Then do the same after re-entering Threema credentials.

### Phase 3 – `CaregiverPublisher` (closes B4, B5)

- New process-scoped class, started by `MessengerService`. It moves in
  `startPublishingGroups/Senders/StatusPosters` and `publishAlertsToCaregivers`, driven by
  `CaregiverLogRepository.events` and not by the view model's copy.
- `recordCaregiverEvent` becomes a single `CaregiverLog.record(kind, …)` on the graph. That
  also removes the view model's in-memory fallback branch, which exists only because the
  database was nullable.
- Note: until phase 4 the publisher needs the named-message flows. Do phase 4 first if
  duplicating them for one phase is unacceptable. They can also ship together.

### Phase 4 – `MessageCatalog`: one derived-message graph

- Extract the pure derivation chain from the view model into a process-scoped class:
  `conversationLinks`, `nameByIdentity`, `contactNames`, `allContacts`/`favoriteContacts`,
  `threemaGroupDescriptions`/`groupDescriptions`, `groupReplyDecisions`, `namedMessages`,
  `namedStoredMessages`, `visibleMessages`, `statusPosts`. This is roughly lines 650–1460 and is
  mostly `combine` blocks that call pure `model/` functions.
- `ArrivalPipeline.visibleMessagesFlow` is then deleted in favour of
  `MessageCatalog.visibleMessages`. The phone then announces exactly what the home screen
  shows, by construction, which is the promise `startAnnouncingArrivals`'s KDoc already makes.
- Use `SharingStarted.WhileSubscribed(5_000)` for the UI-only projections and `Eagerly` for the
  ones the pipeline and publisher need. Keep `flowOn(Dispatchers.Default)` where it is now.
- Inject `nudgeClock`/`currentTimeMillis` here so tests keep their non-ticking clock (see
  the `runTest` hang note on `nudgeClock`).

Risk: medium. Most of it is cut and paste, but it changes sharing semantics. Characterization
tests from phase 0 on `visibleMessages`/`singleNudge` are the guard.

### Phase 5 – `Outbox`: outbound sending leaves the UI

- Move lines ~3240–4300 into `data/Outbox.kt`: `sendTextReply`, `sendNewMessage`, `resend`,
  `deliverAgain`, the group fan-out, `forwardMedia`, `sendHeartToPartner`, reactions,
  read receipts, `recordOutgoing`, `sendAndReport`, and the `awaitTransportReady` waits.
- It runs in the process scope. A send that is in flight when she presses Home or the
  activity is recreated finishes instead of being cancelled with `viewModelScope`. Verify
  whether this is already observed as stuck "sending" rows; if so, it is another row for the
  table.
- `outgoingSequence` moves with it, so there is one sequence per process instead of one per
  view model.
- The view model keeps one-line `suspend fun`s that delegate, so screens do not change.

### Phase 6 – Split the view model by screen

Only now does the public API move. The 119 distinct `viewModel.*` members used from
`MainActivity`, `HomeScreen`, `CaregiverScreenHost` and `AppointmentsScreen` split along these
seams, which already exist in the file:

| New view model | Takes from `MainViewModel` |
|---|---|
| `HomeViewModel` | home feeds, `singleNudge`, missed-call cards, look feed, unread counts, favourites |
| `ConversationViewModel` | open conversation, reply target, mark read, mute/delete, send/react/forward (→ `Outbox`) |
| `PaymentViewModel` | the whole payment card block (lines ~290–460), self-contained already |
| `SupportViewModel` | support button/request/members and the agitation tracker / restless snapshots |
| `CallsViewModel` | recent calls, keypad, `placeCall*`, block number, appointments |
| `CaregiverSettingsViewModel` | Threema/WhatsApp account admin, export/import, mail account/allowlist, pairing code, contact sync (→ `MessengerRuntime`) |
| `VoicePlaybackController` | `MediaPlayer` and TTS. A plain class, not a view model |

All constructors take non-null parameters from `AppGraph` through one small
`ViewModelProvider.Factory`. `initializeReplier` is deleted. What is left of it (permission
check, region and wording reads, mail sync scheduling) moves to `MainActivity.onCreate` or to
the service. Do this one view model per PR, starting with `PaymentViewModel` (no shared state,
already tested) to set the pattern.

## Order, size and what to watch

| Phase | Est. size | Bugs closed | Device test needed |
|---|---|---|---|
| 0 | S | – | no |
| 1 | M | B1, B6, B7 | light |
| 2 | M | B2, B3, B8 | **yes**: cold start with screen off, re-provisioning |
| 3+4 | L | B4, B5 | yes: caregiver site gets updates with screen off |
| 5 | M | (in-flight sends) | yes: send, then Home immediately |
| 6 | L, split into 6 PRs | – | per screen |

Constraints to keep throughout:

- **Startup order** is what makes calls and messages work: the pipeline collectors attach
  before the clients connect, and `VoipCallManager` is built before `ThreemaClient.start()`.
  After phase 2 it lives in exactly one function, which gets a comment and a test.
- **Clients are never replaced, only restarted** (`Messengers.stopAll` comment). `AppGraph`
  keeps that invariant.
- **Nothing secret moves into logs** while code is being moved: pairing codes and client keys
  stay out of `Log`, Firestore and the caregiver log.
- Process-scoped collectors must be idempotent to `start()` (the existing `started` and
  `*CollectionStarted` guards). Collect them into one `Startable` pattern instead of a dozen
  booleans.

## Recommendation

Do phases 0–2 first, as three PRs. They are the smallest changes that remove the duplicated
state and the "only works while the screen is open" class of bugs, and they don't touch any UI
code. Phases 3–5 then empty the view model of non-UI work, and phase 6 is mechanical after
that.
