/**
 * Caregiver Remote Control Portal Script for gentle connection.
 * Authenticates caregiver via Google Sign-In and communicates with Firebase Firestore
 * (devices/{deviceId}/settings/current and pairing_codes/{code}).
 */

(function () {
  'use strict';

  // DOM elements
  const firebaseNotice = document.getElementById('firebase-notice');
  const statusMessage = document.getElementById('status-message');
  const userBar = document.getElementById('user-bar');
  const userEmailSpan = document.getElementById('user-email');
  const signoutBtn = document.getElementById('signout-btn');
  const googleLoginBtn = document.getElementById('google-login-btn');
  const authCard = document.getElementById('auth-card');
  const introCard = document.getElementById('intro-card');

  const pairingCard = document.getElementById('pairing-card');
  const pairingForm = document.getElementById('pairing-form');
  const pairingCodeInput = document.getElementById('pairing-code');

  const deviceSelectorCard = document.getElementById('device-selector-card');
  const deviceSelect = document.getElementById('device-select');
  const deviceNameInput = document.getElementById('device-name');

  const statusCard = document.getElementById('status-card');
  const heartbeatLine = document.getElementById('heartbeat-line');
  const locationLine = document.getElementById('location-line');
  const locatedWithLine = document.getElementById('located-with-line');
  const alertsContainer = document.getElementById('alerts-container');
  const managedCard = document.getElementById('managed-card');
  const managedContainer = document.getElementById('managed-container');
  const notificationHint = document.getElementById('notification-hint');
  const enableNotificationsBtn = document.getElementById('enable-notifications-btn');

  const viewOverview = document.getElementById('view-overview');
  const viewSettings = document.getElementById('view-settings');
  const toSettingsBtn = document.getElementById('to-settings-btn');
  const backToOverviewBtn = document.getElementById('back-to-overview-btn');
  const deviceNameCard = document.getElementById('device-name-card');
  const saveStatusPill = document.getElementById('save-status-pill');
  const channelSwitchesDetails = document.getElementById('channel-switches-details');
  const presetCustomState = document.getElementById('preset-custom-state');

  const settingsCard = document.getElementById('settings-card');
  const currentDeviceIdSpan = document.getElementById('current-device-id');
  const unpairBtn = document.getElementById('unpair-btn');
  const contactsContainer = document.getElementById('contacts-container');
  const groupsContainer = document.getElementById('groups-container');
  const showCompanyMessagesInput = document.getElementById('show-company-messages');
  const sendersContainer = document.getElementById('senders-container');
  const statusPostersContainer = document.getElementById('status-posters-container');
  const bluetoothDevicesContainer = document.getElementById('bluetooth-devices-container');
  const bluetoothSuggestionsList = document.getElementById('bluetooth-label-suggestions');

  // Support Requests Card & Support Group
  const supportRequestsCard = document.getElementById('support-requests-card');
  const supportRequestBadge = document.getElementById('support-request-badge');
  const supportRequestsContainer = document.getElementById('support-requests-container');
  const supportGroupNameInput = document.getElementById('support-group-name');
  const supportPersonNameInput = document.getElementById('support-person-name');
  const supportShareLocationInput = document.getElementById('support-share-location');
  const supportMembersContainer = document.getElementById('support-members-container');

  // Inputs
  const textSizeInput = document.getElementById('text-size');
  const photoSizeInput = document.getElementById('photo-size');
  const speechRateInput = document.getElementById('speech-rate');
  const darkModeInput = document.getElementById('dark-mode');
  const enableMessagesInput = document.getElementById('enable-messages');
  const enableMailInput = document.getElementById('enable-mail');
  const mailOnlyKnownSendersInput = document.getElementById('mail-only-known-senders');
  const enableNudgeInput = document.getElementById('enable-nudge');
  const enableCalendarInput = document.getElementById('enable-calendar');
  const enableWalletInput = document.getElementById('enable-wallet');
  const showPaymentReadyNoticeInput = document.getElementById('show-payment-ready-notice');
  const paymentAuthMethodInput = document.getElementById('payment-auth-method');
  const showBirthdaysInput = document.getElementById('show-birthdays');
  const showStepsInput = document.getElementById('show-steps');
  const missedCallHoursInput = document.getElementById('missed-call-hours');
  const blockUnknownCallersInput = document.getElementById('block-unknown-callers');
  const showBlockedCallsInCallLogInput = document.getElementById('show-blocked-calls-in-call-log');
  const notifyBlockedCallsInput = document.getElementById('notify-blocked-calls');
  // The two blocked-call options only mean something while calls are being blocked. Disabled
  // rather than hidden, so a caregiver can see they exist; their values are kept as they are.
  function syncBlockedCallOptions() {
    const blocking = blockUnknownCallersInput.checked;
    showBlockedCallsInCallLogInput.disabled = !blocking;
    notifyBlockedCallsInput.disabled = !blocking;
  }
  blockUnknownCallersInput.addEventListener('change', syncBlockedCallOptions);
  const rejectWhatsAppCallsInput = document.getElementById('reject-whatsapp-calls');
  const threemaOnlyWhenRoamingInput = document.getElementById('threema-only-when-roaming');
  const blockUnknownThreemaCallersInput = document.getElementById('block-unknown-threema-callers');
  const speakCallFailuresInput = document.getElementById('speak-call-failures');
  const enableSearchInput = document.getElementById('enable-search');
  const enableEmojiChoiceInput = document.getElementById('enable-emoji-choice');
  const enablePhotoSendingInput = document.getElementById('enable-photo-sending');
  const enableCameraSwitchInput = document.getElementById('enable-camera-switch');
  const photoButtonPlacementInput = document.getElementById('photo-button-placement');
  const quietFromInput = document.getElementById('quiet-from');
  const quietUntilInput = document.getElementById('quiet-until');
  const ringSilenceTimeoutInput = document.getElementById('ring-silence-timeout');

  let db = null;
  let auth = null;
  let currentUser = null;
  let currentDeviceId = localStorage.getItem('gc_device_id') || '';
  let currentSettings = null;
  let unsubscribeDevices = null;
  let unsubscribeStatus = null;
  let unsubscribeSupport = null;
  let unsubscribeManagedState = null;
  let unsubscribeManagedCompliance = null;
  let unsubscribeAccessOwners = null;
  let unsubscribePolicyCurrent = null;
  let unsubscribePolicyConsent = null;
  let unsubscribeCommands = null;
  let unsubscribeAudit = null;
  let managedState = null;
  let managedCompliance = null;
  let accessOwners = null;
  let policyCurrent = null;
  let policyConsent = null;
  let recentCommands = [];
  let recentAudits = [];
  let selectedLaunchablePackage = null;
  let currentSupportRequests = [];
  let supportMembers = [];
  const notifiedSupportRequestIds = new Set();

  // What the phone last said is wrong, and when it last said anything at all.
  let currentAlerts = [];
  let lastSeenAtMs = null;
  // The last position the phone sent, as it sent it: { latitude, longitude, at, accuracyMetres? }.
  // Null both when the phone has never sent one and when this browser has not looked yet — the
  // difference is carried by heartbeatKnown, exactly as it is for lastSeenAtMs.
  let lastLocation = null;
  // The last Bluetooth proximity attribution summary: { place?, people: [], ownDevices: [], at }.
  let lastLocatedWith = null;
  // What each phone is called, by id. Filled from devices/{id}/settings/current, which is the
  // one document a caregiver may write — the device document itself is the phone's, and the
  // rules let a caregiver touch nothing on it but the claim. Cached because the devices listener
  // fires on every heartbeat and a name does not change quarter-hourly.
  let deviceNameById = {};

  // Per-device cache from the claimed devices snapshot so switching between devices in the
  // dropdown updates status and location immediately.
  let lastSeenById = {};
  let locationById = {};
  let locatedWithById = {};
  // What hardware each phone says it is, written by the phone itself on every start. The name a
  // caregiver chose wins over it; it exists so that a phone nobody has named yet still reads as
  // something ("SM-S916B") rather than as twelve hex characters.
  let modelById = {};
  // Whether a device snapshot has arrived yet. Without this, the fraction of a second between the
  // card appearing and the first snapshot has lastSeenAtMs still null — which reads as "this phone
  // has never reported", in red, with a notification behind it, on every single page load.
  let heartbeatKnown = false;
  let statusEverLoaded = false;
  let baseTitle = document.title;

  // Who is starred on the phone, as the phone last published it: {id, name} and nothing else.
  // The website cannot read her address book and never sees the people who are not favourites.
  let starredContacts = [];

  // The groups the phone knows about, as it last published them: {key, name} and nothing else.
  // No members, no messages — enough to put a name against a switch. See GroupVisibility.kt.
  let knownGroups = [];

  // The senders the phone has filed as companies, as it last published them: {key, name} and
  // nothing else. Only the ones it takes for companies are ever published — anybody in her address
  // book is already a person and needs no switch here. See SenderJudgement.kt.
  let knownSenders = [];

  // Whose WhatsApp statuses arrive, as the phone last published them: {key, name, count}. Only a
  // short list — the five who posted last and the five who post most, plus anybody already
  // switched off. See StatusPosterVisibility.kt.
  let knownStatusPosters = [];

  // Which Bluetooth devices are known nearby or paired, as the phone last published them.
  let knownBluetoothDevices = [];

  // A pairing code that arrived in the link from the phone, waiting for a signed-in caregiver.
  // Held in memory only: see takeCodeFromLink for why it is taken out of the URL at once.
  let codeFromLink = '';

  /**
   * The pairing code carried in the link, removed from the address bar as it is read.
   *
   * The phone puts it in the fragment (`#code=Q0227S`), so it never reaches Hosting's logs and
   * never rides along in a Referer. It is taken straight back out of the URL for the rest: a
   * caregiver who bookmarks the page, copies the address to a second device, or simply leaves the
   * tab open should not be carrying a live claim on her phone around with them. The claim is a
   * one-time act; the link should behave like one.
   *
   * `?code=` is accepted too, because a link that has been through something that rewrites
   * fragments still ought to work — it is just not what the phone generates.
   */
  function takeCodeFromLink() {
    const fromHash = new URLSearchParams(window.location.hash.replace(/^#/, '')).get('code');
    const fromQuery = new URLSearchParams(window.location.search).get('code');
    const raw = (fromHash || fromQuery || '').trim().toUpperCase();

    if (fromHash || fromQuery) {
      history.replaceState(null, '', window.location.pathname);
    }

    return /^[A-Z0-9]{6}$/.test(raw) ? raw : '';
  }

  /**
   * The list of settings tab slugs, mapped to panel IDs ('tabpanel-' + slug).
   *
   * 'kontakte' is the default tab represented by '#einstellungen' without a sub-path.
   * 'anzeige', 'hinweise', 'anrufe', and 'geraet' are accessed via '#einstellungen/<slug>'.
   */
  const SETTINGS_TABS = ['kontakte', 'anzeige', 'hinweise', 'anrufe', 'geraet'];

  /**
   * Determines which settings tab is requested by the current URL hash.
   *
   * '#einstellungen' defaults to 'kontakte'. Deep links such as '#einstellungen/anrufe'
   * activate that specific tab. An unknown tab slug falls back to 'kontakte' so that
   * invalid or mistyped links still present a working view rather than an empty screen.
   */
  function getTabFromHash() {
    const hash = window.location.hash;
    if (hash === '#einstellungen' || hash === '#einstellungen/' || hash === '#einstellungen/kontakte') {
      return 'kontakte';
    }
    if (hash.startsWith('#einstellungen/')) {
      const slug = hash.slice('#einstellungen/'.length).toLowerCase().trim();
      if (SETTINGS_TABS.indexOf(slug) !== -1) {
        return slug;
      }
      if (slug === 'gerät' || slug === 'ger%c3%a4t') {
        return 'geraet';
      }
    }
    return 'kontakte';
  }

  /**
   * Switches the active tab panel, updating ARIA attributes and the URL hash.
   *
   * Tab switching uses history.replaceState rather than pushState so that the browser's
   * Back button returns to the overview in a single step, rather than stepping backwards
   * through every tab visited in the current session. When user interaction triggers the
   * switch, shouldScroll is set to smooth-scroll back to the top of the tab bar so the
   * beginning of the newly selected panel is in view.
   */
  function switchTab(tabId, updateHash = false, shouldScroll = false) {
    const targetTab = SETTINGS_TABS.indexOf(tabId) !== -1 ? tabId : 'kontakte';

    SETTINGS_TABS.forEach((id) => {
      const btn = document.getElementById('tab-' + id);
      const panel = document.getElementById('tabpanel-' + id);
      const isSelected = (id === targetTab);

      if (btn) {
        btn.setAttribute('aria-selected', isSelected ? 'true' : 'false');
        btn.tabIndex = isSelected ? 0 : -1;
        btn.classList.toggle('is-active', isSelected);
      }
      if (panel) {
        if (isSelected) {
          panel.removeAttribute('hidden');
        } else {
          panel.setAttribute('hidden', '');
        }
      }
    });

    if (updateHash) {
      const newHash = targetTab === 'kontakte' ? '#einstellungen' : '#einstellungen/' + targetTab;
      if (window.location.hash !== newHash) {
        history.replaceState(null, '', newHash);
      }
    }

    if (shouldScroll) {
      const anchor = document.getElementById('settings-tab-anchor') || document.querySelector('.settings-tablist');
      if (anchor) {
        anchor.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    }
  }

  /**
   * Binds click and keyboard navigation handlers to the tab bar.
   *
   * Implements the WAI-ARIA tab pattern: Left and Right arrow keys cycle through tabs
   * and immediately switch to the newly focused tab.
   */
  function initSettingsTabs() {
    const tabList = document.querySelector('.settings-tablist');
    if (!tabList) return;

    const tabButtons = Array.from(tabList.querySelectorAll('[role="tab"]'));

    tabButtons.forEach((btn) => {
      btn.addEventListener('click', () => {
        const tabId = btn.dataset.tab;
        switchTab(tabId, true, true);
      });
    });

    tabList.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;

      const activeBtn = document.activeElement;
      const currentIndex = tabButtons.indexOf(activeBtn);
      if (currentIndex === -1) return;

      e.preventDefault();
      let nextIndex = 0;
      if (e.key === 'ArrowRight') {
        nextIndex = (currentIndex + 1) % tabButtons.length;
      } else if (e.key === 'ArrowLeft') {
        nextIndex = (currentIndex - 1 + tabButtons.length) % tabButtons.length;
      }

      const nextBtn = tabButtons[nextIndex];
      nextBtn.focus();
      const tabId = nextBtn.dataset.tab;
      switchTab(tabId, true, true);
    });
  }

  /**
   * Switches between the overview and settings views based on the URL hash '#einstellungen'.
   *
   * The hash '#einstellungen' (defaulting to the 'kontakte' tab) or any sub-tab path like
   * '#einstellungen/anzeige' is treated as the settings view; anything else is the overview.
   * When a device exists, the pairing card lives inside the "Gerät" tab panel; when the user
   * has no claimed device yet, it is moved into the overview so it remains immediately accessible.
   */
  function updateView() {
    if (!currentUser) {
      if (viewOverview) viewOverview.style.display = 'none';
      if (viewSettings) viewSettings.style.display = 'none';
      return;
    }

    const hasDevice = !!currentDeviceId;
    const hash = window.location.hash;
    const isSettings = hash === '#einstellungen' || hash.startsWith('#einstellungen/');
    const tabpanelGeraet = document.getElementById('tabpanel-geraet');

    if (!hasDevice) {
      // When the user has no device yet, the pairing card must still be reachable from the overview.
      if (pairingCard && viewOverview && pairingCard.parentNode !== viewOverview) {
        viewOverview.appendChild(pairingCard);
      }
      if (toSettingsBtn) toSettingsBtn.style.display = 'none';
      if (isSettings) {
        history.replaceState(null, '', window.location.pathname + (window.location.search || ''));
      }
      if (viewOverview) viewOverview.style.display = 'block';
      if (viewSettings) viewSettings.style.display = 'none';
      return;
    }

    // A device is selected: move pairing card into the "Gerät" tab panel if not already there.
    if (toSettingsBtn) toSettingsBtn.style.display = 'block';
    if (pairingCard && tabpanelGeraet && pairingCard.parentNode !== tabpanelGeraet) {
      tabpanelGeraet.appendChild(pairingCard);
    }

    if (isSettings) {
      if (viewOverview) viewOverview.style.display = 'none';
      if (viewSettings) viewSettings.style.display = 'block';
      if (deviceNameCard) deviceNameCard.style.display = settingsCard.style.display;

      const activeTab = getTabFromHash();
      switchTab(activeTab, false, false);
    } else {
      if (viewOverview) viewOverview.style.display = 'block';
      if (viewSettings) viewSettings.style.display = 'none';
    }
  }

  function initViewRouting() {
    if (toSettingsBtn) {
      toSettingsBtn.addEventListener('click', () => {
        history.pushState(null, '', '#einstellungen');
        updateView();
      });
    }

    if (backToOverviewBtn) {
      backToOverviewBtn.addEventListener('click', () => {
        history.pushState(null, '', window.location.pathname + (window.location.search || ''));
        updateView();
      });
    }

    window.addEventListener('popstate', updateView);
    window.addEventListener('hashchange', updateView);
  }

  // Initialize Firebase if config is loaded
  function initFirebase() {
    // Before anything else, so the code is out of the address bar even if Firebase never loads.
    codeFromLink = takeCodeFromLink();
    if (codeFromLink) {
      // Filled in either way, so a caregiver who signs in slowly enough for the code to expire
      // can still see what it was, and so nothing about this looks like magic.
      pairingCodeInput.value = codeFromLink;
    }

    initViewRouting();
    initSettingsTabs();
    initStaticSettingsBindings();

    if (window.firebaseConfig && typeof firebase !== 'undefined') {
      try {
        if (!firebase.apps.length) {
          firebase.initializeApp(window.firebaseConfig);
        }
        db = firebase.firestore();
        auth = firebase.auth();
        firebaseNotice.style.display = 'none';

        auth.onAuthStateChanged((user) => {
          currentUser = user;
          if (user) {
            userEmailSpan.textContent = describeUser(user);
            // Signing out of an anonymous session throws the access away for good: the uid is
            // never issued again, so what is left behind in caregiverUids is a dead entry and the
            // way back in is a fresh link from her phone. The button says so rather than offering
            // "Abmelden", which sounds like something you can undo by logging back in.
            signoutBtn.textContent = user.isAnonymous ? 'Zugang hier entfernen' : 'Abmelden';
            userBar.style.display = 'block';
            authCard.style.display = 'none';
            introCard.style.display = 'none';
            pairingCard.style.display = 'block';
            deviceSelectorCard.style.display = 'block';

            updateView();
            listenToClaimedDevices(user.uid);

            // A code that came in from the phone's link. Claiming needs a signed-in caregiver,
            // and tapping the link is usually the first time this browser has ever seen the site
            // — so the code waits here rather than being spent and lost against a null user.
            // Cleared either way: a code is good for one claim, and a second attempt on a failed
            // one should be a deliberate act, not something that happens again on every reload.
            if (codeFromLink) {
              const code = codeFromLink;
              codeFromLink = '';
              claimDevice(code);
            }
          } else {
            // A code in hand and nobody signed in: sign in without asking, and claim.
            //
            // This is the whole point of the link. The person holding it is standing at her phone
            // — that is how they got the code — and what stood between them and the settings was
            // a Google login screen, on someone else's phone, chosen under pressure. It proved
            // nothing: the account they picked was a name we had never seen before and could not
            // check. The pairing code is the claim; see firestore.rules.
            //
            // Anonymous, so there is nothing to type and nothing to remember. The session lives
            // in this browser's storage, so coming back to the page later still works — and if
            // that storage is cleared, tapping a fresh link from her phone is the way back in,
            // which is the same gesture as the first time.
            if (codeFromLink) {
              signInFromLink();
            }

            userBar.style.display = 'none';
            authCard.style.display = 'block';
            introCard.style.display = 'block';
            pairingCard.style.display = 'none';
            deviceSelectorCard.style.display = 'none';
            settingsCard.style.display = 'none';
            if (deviceNameCard) deviceNameCard.style.display = 'none';

            if (unsubscribeDevices) {
              unsubscribeDevices();
              unsubscribeDevices = null;
            }
            lastSeenById = {};
            locationById = {};
            stopWatchingStatus();
            updateView();
          }
        });
      } catch (err) {
        showWarning('Fehler beim Initialisieren von Firebase: ' + err.message);
      }
    } else if (typeof firebase === 'undefined') {
      // config.js is checked in, so on the hosted site this is nearly always the SDK from gstatic
      // failing to load — a blocker or a bad connection — and telling the visitor to edit a file
      // they do not have would only confuse them.
      showWarning('Die Verbindung zum Server konnte nicht hergestellt werden. Bitte die Internetverbindung prüfen, Werbeblocker für diese Seite deaktivieren und die Seite neu laden.');
    } else {
      showWarning('Hinweis: Firebase-Konfiguration (config.js) wurde nicht gefunden. Bitte config.example.js als config.js speichern und Firebase API-Schlüssel eintragen.');
    }
  }

  // Whether a sign-in started by the link is already under way, so a second auth event does not
  // start another one. onAuthStateChanged fires more than once on a cold load.
  let signingInFromLink = false;

  /**
   * Sign in silently, because the caregiver arrived on the link from her phone.
   *
   * No account, no password, no chooser. The claim itself is still checked against a live pairing
   * code for that one device, which is the barrier that was always doing the work.
   *
   * Every failure ends as the login card with the code already in the box — an anonymous sign-in
   * can fail for reasons the person reading this page cannot act on (the provider disabled in the
   * Firebase console, a browser that blocks the storage it needs), and the Google button is a way
   * in that does not depend on any of them.
   */
  async function signInFromLink() {
    if (!auth || signingInFromLink) return;
    signingInFromLink = true;

    showStatus('Kopplungscode vom Smartphone übernommen. Verbindung wird hergestellt…');

    try {
      await auth.signInAnonymously();
      // Nothing else here: onAuthStateChanged fires with the new user and claims the code, the
      // same path a Google sign-in takes.
    } catch (err) {
      showStatus(
        'Automatische Verbindung nicht möglich: ' + parseErrorMessage(err) +
        ' Bitte melden Sie sich mit Google an — das Gerät wird danach automatisch verknüpft.',
        false
      );
    } finally {
      signingInFromLink = false;
    }
  }

  /**
   * How this browser is signed in, in words the person reading it can act on.
   *
   * An anonymous uid is a forty-character string that names nobody; printing it where an email
   * address goes would be the page showing somebody an identifier and calling it their account.
   */
  function describeUser(user) {
    if (user.email || user.displayName) return user.email || user.displayName;
    return 'über den Link vom Smartphone';
  }

  // Sign in with Google
  googleLoginBtn.addEventListener('click', async () => {
    if (!auth) return;
    const provider = new firebase.auth.GoogleAuthProvider();
    try {
      await auth.signInWithPopup(provider);
    } catch (err) {
      showStatus('Fehler bei der Google-Anmeldung: ' + parseErrorMessage(err), false);
    }
  });

  // Sign out
  signoutBtn.addEventListener('click', async () => {
    if (!auth) return;

    // Asked only of the anonymous kind, and only because for them it is not reversible: there is
    // no account to sign back in to, so this ends the access until somebody stands at her phone
    // again. A Google sign-out is undone by signing in, and gets no dialog.
    const user = auth.currentUser;
    if (user && user.isAnonymous) {
      const goOn = window.confirm(
        'Der Zugang zu diesem Smartphone wird auf diesem Gerät entfernt. ' +
        'Um ihn wiederherzustellen, muss auf dem Smartphone erneut die Betreuungs-Website ' +
        'geöffnet werden. Fortfahren?'
      );
      if (!goOn) return;

      // Off every phone's list first, then out. Signing out alone left the uid on each phone's
      // caregiverUids for good — nobody could sign in as it again, but it still counted as
      // somebody looking after her, and nothing ever took it off. The rules allow exactly this
      // write: a caregiver removing their own uid and nobody else's.
      await leaveEveryDevice(user.uid);
    }

    auth.signOut();
  });

  /** Removes [uid] from caregiverUids on every phone it is on. Best effort, per phone. */
  async function leaveEveryDevice(uid) {
    if (!db) return;
    try {
      const snapshot = await db.collection('devices')
        .where('caregiverUids', 'array-contains', uid)
        .get();
      await Promise.all(snapshot.docs.map((doc) =>
        doc.ref.update({ caregiverUids: firebase.firestore.FieldValue.arrayRemove(uid) })
          .catch((err) => console.warn('Could not leave device', doc.id, err))
      ));
    } catch (err) {
      console.warn('Could not list devices to leave', err);
    }
  }

  /**
   * What to call a phone in the list: the name a caregiver gave it, else the model the phone
   * reports for itself, else — for a phone that has not started since either existed — the id.
   *
   * The id goes in brackets after a name, because it is still the thing every other screen and
   * every support request is keyed by, and a list that hides it entirely makes the two
   * impossible to line up.
   */
  function deviceLabel(id) {
    const chosen = (deviceNameById[id] || '').trim();
    const model = (modelById[id] || '').trim();
    if (chosen) return `${chosen} (${id})`;
    if (model) return `${model} (${id})`;
    return `Gerät: ${id}`;
  }

  // Listen to devices claimed by the caregiver
  function listenToClaimedDevices(uid) {
    if (!db) return;

    if (unsubscribeDevices) {
      unsubscribeDevices();
    }

    unsubscribeDevices = db.collection('devices')
      .where('caregiverUids', 'array-contains', uid)
      .orderBy('lastSeenAt', 'desc')
      .onSnapshot((snapshot) => {
        deviceSelect.innerHTML = '<option value="">-- Kein Gerät ausgewählt --</option>';

        let foundCurrent = false;
        // When each phone last said it was still running. This listener is already watching the
        // device documents and the phone rewrites lastSeenAt every quarter of an hour, so the
        // heartbeat arrives here for free — no second listener, and the snapshot that carries it
        // is the same one that keeps the dropdown up to date.
        lastSeenById = {};
        // And where each phone was when it said so. Same snapshot, same document, no extra read.
        locationById = {};
        locatedWithById = {};
        modelById = {};

        snapshot.forEach((doc) => {
          const id = doc.id;
          const seen = doc.data().lastSeenAt;
          lastSeenById[id] = seen && seen.toMillis ? seen.toMillis() : null;
          locationById[id] = doc.data().lastLocation || null;
          locatedWithById[id] = doc.data().lastLocatedWith || null;
          modelById[id] = doc.data().model || '';

          const option = document.createElement('option');
          option.value = id;
          option.textContent = deviceLabel(id);
          if (id === currentDeviceId) {
            option.selected = true;
            foundCurrent = true;
          }
          deviceSelect.appendChild(option);
        });

        // Only (re)load the form when the selected device actually changes. The phone
        // rewrites lastSeenAt on every start, and reloading here would silently discard
        // whatever the caregiver is halfway through editing.
        if (foundCurrent) {
          deviceSelect.value = currentDeviceId;
          if (settingsCard.style.display !== 'block') {
            loadDeviceSettings(currentDeviceId);
          }
        } else if (snapshot.docs.length > 0) {
          const firstId = snapshot.docs[0].id;
          deviceSelect.value = firstId;
          loadDeviceSettings(firstId);
        } else {
          currentDeviceId = '';
          localStorage.removeItem('gc_device_id');
          settingsCard.style.display = 'none';
        }
        updateView();

        // The names, for the phones whose name we have not read yet. One small read each, once
        // per session rather than once per heartbeat, and a failure changes nothing: the label
        // falls back to the model and then to the id.
        snapshot.forEach((doc) => {
          if (Object.prototype.hasOwnProperty.call(deviceNameById, doc.id)) return;
          db.collection('devices').doc(doc.id).collection('settings').doc('current').get()
            .then((settingsDoc) => {
              deviceNameById[doc.id] = (settingsDoc.exists && settingsDoc.data().deviceName) || '';
              const option = deviceSelect.querySelector(`option[value="${doc.id}"]`);
              if (option) option.textContent = deviceLabel(doc.id);
            })
            .catch(() => { deviceNameById[doc.id] = ''; });
        });

        // After the selection, because which phone is selected decides whose heartbeat this is.
        updateHeartbeatForCurrentDevice();
        heartbeatKnown = true;
        renderStatus();
      }, (err) => {
        showStatus('Fehler beim Abrufen der Geräte: ' + parseErrorMessage(err), false);
      });
  }

  /**
   * Update heartbeat timestamp and last known location for currentDeviceId
   * from the claimed devices cache.
   */
  function updateHeartbeatForCurrentDevice() {
    lastSeenAtMs = Object.prototype.hasOwnProperty.call(lastSeenById, currentDeviceId)
      ? lastSeenById[currentDeviceId]
      : null;
    lastLocation = Object.prototype.hasOwnProperty.call(locationById, currentDeviceId)
      ? locationById[currentDeviceId]
      : null;
    lastLocatedWith = Object.prototype.hasOwnProperty.call(locatedWithById, currentDeviceId)
      ? locatedWithById[currentDeviceId]
      : null;
  }

  deviceSelect.addEventListener('change', (e) => {
    const selectedId = e.target.value;
    if (selectedId) {
      loadDeviceSettings(selectedId);
    } else {
      currentDeviceId = '';
      localStorage.removeItem('gc_device_id');
      settingsCard.style.display = 'none';
      if (deviceNameCard) deviceNameCard.style.display = 'none';
      stopWatchingStatus();
      updateView();
    }
  });

  /** Stop watching a phone, and stop claiming to know anything about it. */
  function stopWatchingStatus() {
    if (unsubscribeStatus) {
      unsubscribeStatus();
      unsubscribeStatus = null;
    }
    if (unsubscribeSupport) {
      unsubscribeSupport();
      unsubscribeSupport = null;
    }
    currentAlerts = [];
    currentSupportRequests = [];
    lastSeenAtMs = null;
    lastLocation = null;
    lastLocatedWith = null;
    heartbeatKnown = false;
    statusEverLoaded = false;
    statusCard.style.display = 'none';
    if (locatedWithLine) {
      locatedWithLine.style.display = 'none';
      locatedWithLine.innerHTML = '';
    }
    if (supportRequestsCard) supportRequestsCard.style.display = 'none';
    stopWatchingManaged();
    document.title = baseTitle;
  }

  function showWarning(msg) {
    firebaseNotice.className = 'banner banner-warning';
    firebaseNotice.innerHTML = msg;
    firebaseNotice.style.display = 'block';
  }

  function showStatus(msg, isSuccess = true) {
    statusMessage.className = isSuccess ? 'banner banner-info' : 'banner banner-warning';
    statusMessage.textContent = msg;
    statusMessage.style.display = 'block';
    setTimeout(() => {
      statusMessage.style.display = 'none';
    }, 5000);
  }

  function parseErrorMessage(err) {
    if (!err) return 'Unbekannter Fehler';
    if (err.code === 'permission-denied') {
      return 'Zugriff verweigert. Sie haben keine Berechtigung für dieses Gerät oder der Kopplungscode ist abgelaufen.';
    }
    if (err.code === 'not-found') {
      return 'Der angeforderte Datensatz wurde nicht gefunden.';
    }
    // The device list is a composite query (array-contains + orderBy), and Firestore refuses it
    // outright until the matching index exists. This looked exactly like "pairing did not work":
    // the claim had in fact succeeded, the dropdown just stayed empty. Say which it is.
    if (err.code === 'failed-precondition') {
      return 'Die Geräteliste kann nicht abgefragt werden, weil der zugehörige Firestore-Index fehlt (firestore.indexes.json). Die Kopplung selbst ist davon nicht betroffen. Technisch: ' + (err.message || '');
    }
    return err.message || err.toString();
  }

  // Pairing form submit (Claiming device via short-lived pairing code)
  pairingForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const code = pairingCodeInput.value.trim().toUpperCase();
    if (!code || code.length !== 6) {
      showStatus('Bitte geben Sie einen gültigen 6-stelligen Kopplungscode ein.', false);
      return;
    }
    claimDevice(code);
  });

  /**
   * Link this caregiver's account to the phone the code belongs to.
   *
   * Reached two ways: typed into the form, or carried in from the phone's link and run the moment
   * sign-in completes. Both end in the same place, so a code that came in by link gets the same
   * expiry check and the same wording when something is wrong.
   */
  async function claimDevice(code) {
    if (!db || !currentUser) return;

    const pairBtn = document.getElementById('pair-device-btn');
    pairBtn.disabled = true;
    pairBtn.textContent = 'Wird überprüft...';

    try {
      const pairingDoc = await db.collection('pairing_codes').doc(code).get();

      if (!pairingDoc.exists) {
        showStatus('Ungültiger Kopplungscode. Bitte überprüfen Sie die Eingabe auf dem Smartphone.', false);
        return;
      }

      const data = pairingDoc.data();
      const expiresAtMs = data.expiresAt ? (data.expiresAt.toMillis ? data.expiresAt.toMillis() : data.expiresAt) : 0;

      if (expiresAtMs <= Date.now()) {
        showStatus('Der Kopplungscode ist abgelaufen (10 Min.). Bitte generieren Sie auf dem Smartphone einen neuen Code.', false);
        return;
      }

      const deviceId = data.deviceId;
      if (!deviceId) {
        showStatus('Fehlerhafter Kopplungscode: Keine Geräte-ID hinterlegt.', false);
        return;
      }

      // Already linked? Then this is a returning caregiver, not a new claim.
      //
      // Opening the link from the phone is how a caregiver reaches these settings at all, so the
      // second visit and every one after it lands here with a phone that is already theirs. The
      // claim would be a write that changes nothing; skipping it keeps a normal visit from
      // depending on a pairing code being live, which is the difference between "the settings
      // opened" and a red banner denying a permission they hold.
      const deviceRef = db.collection('devices').doc(deviceId);
      let alreadyLinked = false;
      try {
        const deviceSnap = await deviceRef.get();
        alreadyLinked = deviceSnap.exists &&
          (deviceSnap.data().caregiverUids || []).indexOf(currentUser.uid) !== -1;
      } catch (readErr) {
        // Not permitted to read it means not linked to it. Fall through and claim.
      }

      if (alreadyLinked) {
        showStatus(`Gerät ${deviceId} ist bereits mit Ihrem Konto verknüpft.`);
      } else {
        await deviceRef.update({
          caregiverUids: firebase.firestore.FieldValue.arrayUnion(currentUser.uid),
          pairingCode: code
        });
        showStatus(`Gerät ${deviceId} erfolgreich verknüpft!`);
      }
      pairingCodeInput.value = '';
      currentDeviceId = deviceId;
      localStorage.setItem('gc_device_id', deviceId);
      loadDeviceSettings(deviceId);
    } catch (err) {
      showStatus('Fehler beim Verknüpfen des Geräts: ' + parseErrorMessage(err), false);
    } finally {
      pairBtn.disabled = false;
      pairBtn.textContent = 'Gerät verknüpfen';
    }
  }

  // Load settings from Firestore
  async function loadDeviceSettings(deviceId) {
    if (!db) return;

    try {
      const settingsCol = db.collection('devices').doc(deviceId).collection('settings');

      // Who is starred, published by the phone. Fetched before the settings are rendered, since
      // the favourites list is drawn from it. An absent document is a phone that has not sent one
      // yet, which renderFavourites explains rather than showing an empty list with no reason.
      try {
        const starredSnap = await settingsCol.doc('starred').get();
        starredContacts = starredSnap.exists ? (starredSnap.data().contacts || []) : [];
      } catch (starredErr) {
        starredContacts = [];
      }

      // Which groups exist, also published by the phone. Same treatment as the starred list: an
      // absent document is a phone that has not sent one yet — either because it is an older
      // version or because she is in no groups — and renderGroups says which rather than showing
      // an empty box.
      try {
        const groupsSnap = await settingsCol.doc('groups').get();
        knownGroups = groupsSnap.exists ? (groupsSnap.data().groups || []) : [];
      } catch (groupsErr) {
        knownGroups = [];
      }

      // Which senders the phone takes for companies. Same treatment again: an absent document is
      // a phone that has not sent one — an older version, or an inbox with no company in it — and
      // renderSenders says so rather than showing an empty box.
      try {
        const sendersSnap = await settingsCol.doc('senders').get();
        knownSenders = sendersSnap.exists ? (sendersSnap.data().senders || []) : [];
      } catch (sendersErr) {
        knownSenders = [];
      }

      try {
        const postersSnap = await settingsCol.doc('statusPosters').get();
        knownStatusPosters = postersSnap.exists ? (postersSnap.data().posters || []) : [];
      } catch (postersErr) {
        knownStatusPosters = [];
      }

      try {
        const btSnap = await settingsCol.doc('bluetoothDevices').get();
        knownBluetoothDevices = btSnap.exists ? (btSnap.data().devices || []) : [];
      } catch (btErr) {
        knownBluetoothDevices = [];
      }

      const docRef = settingsCol.doc('current');
      const docSnap = await docRef.get();

      if (docSnap.exists) {
        currentSettings = docSnap.data();
      } else {
        // No settings document yet — a phone that has been claimed but never configured. This is
        // the live case: devices/2EBADC138D3F has no settings/current at all.
        //
        // The defaults here used to include two invented favourites, "Lisa" and "Markus", with
        // invented phone numbers. They rendered in the form looking exactly like real entries a
        // previous caregiver had saved, and pressing Save wrote them to her phone — which puts
        // two strangers on the home screen of somebody who navigates by recognising faces, and
        // gives her two telephone buttons that ring numbers belonging to nobody. Sample data is
        // safe in a demo and is not safe here.
        //
        // pairingCode and isPaired are left out for the same reason: they are the phone's to
        // write, and a placeholder code saved over the real one is a claim that cannot be made.
        currentSettings = {
          textSizeMultiplier: 1.0,
          photoSizeMultiplier: 1.0,
          speechRate: 0.8,
          isDarkMode: false,
          enableMessages: true,
          enableMail: true,
          // Off: every letter in her inbox. Showing only people she knows is a decision about her.
          mailOnlyKnownSenders: false,
          enableNudge: true,
          enableCalendar: true,
          // Off until a caregiver asks for it: the card only makes sense once she has a
          // screen lock and a card in Google Wallet. See RemoteSettings.enableWallet.
          // Unnamed. The list then falls back to the model the phone reports for itself.
          deviceName: '',
          enableWallet: false,
          // Shown; the phone's own confirm screen. See RemoteSettings on the phone.
          showPaymentReadyNotice: true,
          paymentAuthMethod: '',
          // Shown, as before this switch existed.
          showBirthdays: true,
          showSteps: true,
          missedCallHours: 24,
          // Off. Ending calls from anybody not in her address book keeps scam callers away and
          // keeps the surgery away too, and that trade is somebody's deliberate decision about
          // one person's life — never a default a phone arrives with.
          blockUnknownCallers: false,
          showBlockedCallsInCallLog: false,
          notifyBlockedCalls: false,
          // Off. Turning WhatsApp calls away ends calls, which is somebody's decision to make.
          rejectWhatsAppCalls: false,
          // On. Abroad a telephone call is paid by the minute; Threema is not.
          threemaOnlyWhenRoaming: true,
          // On. No legitimate stranger reaches her on Threema.
          blockUnknownThreemaCallers: true,
          // Off. The card always shows; a voice she did not expect can startle her.
          speakCallFailures: false,
          enableSearch: false,
          // Off. Five faces and one press each is the phone she is used to; a chooser is a
          // second screen, and whether she wants one is a decision about her, not a default.
          enableEmojiChoice: false,
          enablePhotoSending: false,
          enableCameraSwitch: false,
          photoButtonPlacement: 'card',
          quietFromHour: 21,
          quietUntilHour: 7,
          ringSilenceTimeoutSeconds: 0,
          favouriteOrder: [],
          // Nothing hidden. Every group she is in is shown until somebody decides otherwise —
          // the phone behaved this way before this list existed, and a default that hid a group
          // nobody had switched off would be a family that had apparently stopped writing.
          hiddenGroups: [],
          // Nobody's statuses hidden, which is what the phone did before this list existed.
          hiddenStatusPosters: [],
          // Nobody corrected. The phone's own reading of an address stands until somebody
          // disagrees with it, which is what it did before this list existed.
          sendersAsPeople: [],
          // Shown. Hiding what nobody asked to hide is the worse mistake of the two.
          showCompanyMessages: true,
          supportGroupName: 'Familie',
          supportPersonName: 'Mama',
          supportShareLocation: true,
          supportGroup: [],
          bluetoothAttributions: []
        };
      }

      currentDeviceId = deviceId;
      deviceNameById[deviceId] = currentSettings.deviceName || '';
      localStorage.setItem('gc_device_id', deviceId);
      currentDeviceIdSpan.textContent = deviceId;
      settingsCard.style.display = 'block';
      if (deviceNameCard) deviceNameCard.style.display = 'block';

      // Immediately switch the heartbeat and location lines to the newly selected phone
      // using cached snapshot data so switching phones updates on screen instantly.
      updateHeartbeatForCurrentDevice();
      renderStatus();

      populateSettingsForm(currentSettings);
      updateView();
      listenToStatus(deviceId);
      listenToSupportRequests(deviceId);
      listenToManaged(deviceId);
    } catch (err) {
      showStatus('Fehler beim Laden der Einstellungen: ' + parseErrorMessage(err), false);
    }
  }

  function populateSettingsForm(s) {
    // toFixed(1), because the options read "1.0" and (1.0).toString() is "1": a phone that never
    // had a size written showed an empty select here instead of "Normal".
    textSizeInput.value = Number(s.textSizeMultiplier || 1.0).toFixed(1);
    photoSizeInput.value = Number(s.photoSizeMultiplier || 1.0).toFixed(1);
    if (textSizeInput.selectedIndex === -1) textSizeInput.value = '1.0';
    if (photoSizeInput.selectedIndex === -1) photoSizeInput.value = '1.0';
    // `|| 0.8` covers absent, 0 and every other falsy value at once, the same way the two
    // sizes above do. A document written before this field existed reads as the default the
    // phone itself uses, so the page never shows a speed the phone is not actually reading at.
    speechRateInput.value = (s.speechRate || 0.8).toString();
    // A value that is not one of the five offered — hand-written into the document, or an
    // option removed here later — leaves a select showing nothing at all. Falling back to the
    // default keeps the control readable and, because this is only what is displayed, does not
    // change the phone until somebody presses save.
    if (!speechRateInput.value || speechRateInput.selectedIndex === -1) {
      speechRateInput.value = '0.8';
    }
    darkModeInput.checked = !!s.isDarkMode;
    enableMessagesInput.checked = s.enableMessages !== false;
    enableMailInput.checked = s.enableMail !== false;
    // `=== true`: absent means every letter, as it does on the phone.
    mailOnlyKnownSendersInput.checked = s.mailOnlyKnownSenders === true;
    enableNudgeInput.checked = s.enableNudge !== false;
    enableCalendarInput.checked = s.enableCalendar !== false;
    enableWalletInput.checked = s.enableWallet === true;
    // `!== false`: absent means shown, as it was before this switch existed.
    showPaymentReadyNoticeInput.checked = s.showPaymentReadyNotice !== false;
    // Anything the page does not offer reads as the phone's own screen, as on the phone.
    paymentAuthMethodInput.value = s.paymentAuthMethod === 'pin' ? 'pin' : '';
    deviceNameInput.value = s.deviceName || '';
    // `!== false`: absent means shown. Like showCompanyMessages, this switch takes things off her
    // screen, so a document written before it existed must not read as a request to hide them.
    showBirthdaysInput.checked = s.showBirthdays !== false;
    showStepsInput.checked = s.showSteps !== false;
    // String, because an option's value always is one and a number never matches it.
    // Anything the page does not offer falls back to the day the phone used to assume.
    const missedCallHours = String(s.missedCallHours ?? 24);
    const knownMissedCallHours =
      Array.from(missedCallHoursInput.options).some((o) => o.value === missedCallHours);
    missedCallHoursInput.value = knownMissedCallHours ? missedCallHours : '24';
    // `=== true` rather than `!== false`: a settings document written before this switch existed
    // has no field here, and absent must read as off.
    blockUnknownCallersInput.checked = s.blockUnknownCallers === true;
    showBlockedCallsInCallLogInput.checked = s.showBlockedCallsInCallLog === true;
    notifyBlockedCallsInput.checked = s.notifyBlockedCalls === true;
    syncBlockedCallOptions();
    // `=== true`, like blockUnknownCallers: absent means off.
    rejectWhatsAppCallsInput.checked = s.rejectWhatsAppCalls === true;
    // `!== false`, unlike the switches above: absent means on, as it does on the phone.
    threemaOnlyWhenRoamingInput.checked = s.threemaOnlyWhenRoaming !== false;
    blockUnknownThreemaCallersInput.checked = s.blockUnknownThreemaCallers !== false;
    // `=== true`: absent means silent, as it does on the phone.
    speakCallFailuresInput.checked = s.speakCallFailures === true;
    // Written as numbers by the phone and by this page, but read defensively: a document from
    // before these fields existed has neither, and the pair of defaults is the one the app itself
    // falls back to, so the page never shows a window the phone is not actually keeping.
    // `=== true`, like blockUnknownCallers: absent means off, not on.
    enableSearchInput.checked = s.enableSearch === true;
    // `=== true` again: a document written before this switch existed means off.
    enableEmojiChoiceInput.checked = s.enableEmojiChoice === true;
    enablePhotoSendingInput.checked = s.enablePhotoSending === true;
    enableCameraSwitchInput.checked = s.enableCameraSwitch === true;
    if (photoButtonPlacementInput) {
      const p = s.photoButtonPlacement;
      photoButtonPlacementInput.value = (p === 'none' || p === 'clock' || p === 'faces' || p === 'bottom') ? p : 'card';
    }
    quietFromInput.value = hourOrDefault(s.quietFromHour, 21).toString();
    quietUntilInput.value = hourOrDefault(s.quietUntilHour, 7).toString();
    ringSilenceTimeoutInput.value = (s.ringSilenceTimeoutSeconds || 0).toString();

    renderFavourites(starredContacts, s.favouriteOrder || []);
    // `!== false`, not `=== true`: absent means shown. This switch takes messages off her phone,
    // so a settings document written before it existed must never read as somebody having asked
    // for her bank's codes to disappear. The opposite way round from enableSearch above.
    showCompanyMessagesInput.checked = s.showCompanyMessages !== false;
    renderGroups(knownGroups, s.hiddenGroups || []);
    renderSenders(knownSenders, s.sendersAsPeople || []);
    renderStatusPosters(knownStatusPosters, s.hiddenStatusPosters || []);
    renderBluetoothDevices(knownBluetoothDevices, s.bluetoothAttributions || []);

    // Support group configuration
    if (supportGroupNameInput) supportGroupNameInput.value = s.supportGroupName || 'Familie';
    if (supportPersonNameInput) supportPersonNameInput.value = s.supportPersonName || 'Mama';
    if (supportShareLocationInput) supportShareLocationInput.checked = s.supportShareLocation !== false;
    loadSupportSelection(s.supportGroup || []);
    renderSupportMembers();
    updatePresetUI(true);
    if (deviceNameCard) {
      deviceNameCard.style.display = settingsCard.style.display;
    }
  }

  /**
   * The groups she is in, each with a switch.
   *
   * On means shown on the phone, which is how every group starts: a caregiver opening this page
   * for the first time sees the state the phone is already in, and nothing changes until they
   * change it. What is written back is the *off* list — see the save handler — because that is
   * the smaller of the two and because a group the phone has not published yet must not be
   * treated as switched off by a browser that has never heard of it.
   *
   * Read-only names. Renaming a group happens in the messenger the group lives in, and a name
   * typed here would only be this page disagreeing with her screen.
   */
  function renderGroups(groups, hidden) {
    if (!groupsContainer) return;
    groupsContainer.innerHTML = '';

    if (!groups || groups.length === 0) {
      groupsContainer.innerHTML = '<p style="color: var(--text-muted); font-style: italic;">' +
        'Das Telefon hat noch keine Gruppen gemeldet. Sobald eine Gruppennachricht ankommt, ' +
        'erscheint die Gruppe hier.</p>';
      return;
    }

    const hiddenKeys = {};
    (hidden || []).forEach((key) => {
      if (typeof key === 'string') hiddenKeys[key.trim().toLowerCase()] = true;
    });

    groups.forEach((group) => {
      const key = String(group.key || '').trim().toLowerCase();
      if (!key) return;

      const row = document.createElement('div');
      row.className = 'toggle-group';
      row.dataset.key = key;
      row.innerHTML = `
        <div class="toggle-label">
          <span>${escapeHtml(group.name || '(ohne Namen)')}</span>
          <small>Gruppennachrichten auf dem Telefon anzeigen</small>
        </div>
        <label class="switch">
          <input type="checkbox" class="group-visible"${hiddenKeys[key] ? '' : ' checked'}>
          <span class="slider"></span>
        </label>
      `;

      row.querySelector('.group-visible').addEventListener('change', () => {
        // Which several-people conversations she is not shown, by the phone's own key for each —
        // see getHiddenGroups, and GroupVisibility.kt for what the phone does with them.
        saveFields({ hiddenGroups: getHiddenGroups() });
      });

      groupsContainer.appendChild(row);
    });
  }

  /**
   * The groups switched off: the ones turned off on screen, plus the ones not on screen at all.
   *
   * The second half is the part that matters. A group the phone has stopped publishing — quiet
   * long enough to fall off the list, or published by a phone that was offline when this page
   * loaded — has no switch here, and a save that wrote only what it could see would turn that
   * group back on. Somebody adjusting the text size would silently undo a decision taken weeks
   * ago, and the first sign of it would be the group reappearing on her home screen.
   */
  function getHiddenGroups() {
    const onScreen = {};
    (knownGroups || []).forEach((group) => {
      const key = String(group.key || '').trim().toLowerCase();
      if (key) onScreen[key] = true;
    });

    const carriedOver = ((currentSettings && currentSettings.hiddenGroups) || [])
      .filter((key) => typeof key === 'string')
      .map((key) => key.trim().toLowerCase())
      .filter((key) => key && !onScreen[key]);

    const switchedOff = groupsContainer
      ? Array.from(groupsContainer.querySelectorAll('.toggle-group'))
        .filter((row) => {
          const box = row.querySelector('.group-visible');
          return box && !box.checked;
        })
        .map((row) => row.dataset.key)
      : [];

    return Array.from(new Set(carriedOver.concat(switchedOff))).sort();
  }

  /**
   * Whose WhatsApp statuses she is shown, each with a switch — the same shape as the groups.
   *
   * On means shown, which is how everybody starts. What is written back is the *off* list.
   */
  function renderStatusPosters(posters, hidden) {
    if (!statusPostersContainer) return;
    statusPostersContainer.innerHTML = '';

    if (!posters || posters.length === 0) {
      statusPostersContainer.innerHTML = '<p style="color: var(--text-muted); font-style: italic;">' +
        'Das Telefon hat noch keine WhatsApp-Status gemeldet. Sobald jemand einen Status ' +
        'veröffentlicht, erscheint die Person hier.</p>';
      return;
    }

    const hiddenKeys = {};
    (hidden || []).forEach((key) => {
      if (typeof key === 'string') hiddenKeys[key.trim().toLowerCase()] = true;
    });

    posters.forEach((poster) => {
      const key = String(poster.key || '').trim().toLowerCase();
      if (!key) return;
      const count = Number(poster.count) || 0;

      const row = document.createElement('div');
      row.className = 'toggle-group';
      row.dataset.key = key;
      row.innerHTML = `
        <div class="toggle-label">
          <span>${escapeHtml(poster.name || '(ohne Namen)')}</span>
          <small>${count === 1 ? '1 Status' : count + ' Status'} auf dem Telefon — Status anzeigen</small>
        </div>
        <label class="switch">
          <input type="checkbox" class="poster-visible"${hiddenKeys[key] ? '' : ' checked'}>
          <span class="slider"></span>
        </label>
      `;

      row.querySelector('.poster-visible').addEventListener('change', () => {
        saveFields({ hiddenStatusPosters: getHiddenStatusPosters() });
      });

      statusPostersContainer.appendChild(row);
    });
  }

  /**
   * The posters switched off: the ones turned off on screen, plus the ones not on screen at all —
   * see getHiddenGroups for why a save must never drop a decision this page cannot see.
   */
  function getHiddenStatusPosters() {
    const onScreen = {};
    (knownStatusPosters || []).forEach((poster) => {
      const key = String(poster.key || '').trim().toLowerCase();
      if (key) onScreen[key] = true;
    });

    const carriedOver = ((currentSettings && currentSettings.hiddenStatusPosters) || [])
      .filter((key) => typeof key === 'string')
      .map((key) => key.trim().toLowerCase())
      .filter((key) => key && !onScreen[key]);

    const switchedOff = statusPostersContainer
      ? Array.from(statusPostersContainer.querySelectorAll('.toggle-group'))
        .filter((row) => {
          const box = row.querySelector('.poster-visible');
          return box && !box.checked;
        })
        .map((row) => row.dataset.key)
      : [];

    return Array.from(new Set(carriedOver.concat(switchedOff))).sort();
  }

  /**
   * The Bluetooth devices seen nearby or paired, each configurable for proximity attribution.
   */
  function renderBluetoothDevices(devices, attributions) {
    if (!bluetoothDevicesContainer) return;
    bluetoothDevicesContainer.innerHTML = '';

    // Suggestions for label
    if (bluetoothSuggestionsList) {
      bluetoothSuggestionsList.innerHTML = '';
      const suggestions = new Set(['Zuhause', 'Wohnzimmer', 'Ihre Uhr']);
      (starredContacts || []).forEach((c) => {
        if (c.name && c.name.trim()) suggestions.add(c.name.trim());
      });
      suggestions.forEach((text) => {
        const opt = document.createElement('option');
        opt.value = text;
        bluetoothSuggestionsList.appendChild(opt);
      });
    }

    const attributionByKey = {};
    (attributions || []).forEach((attr) => {
      if (attr && typeof attr.key === 'string' && attr.key.trim()) {
        attributionByKey[attr.key.trim().toLowerCase()] = attr;
      }
    });

    const deviceKeys = new Set();
    const mergedList = (devices || []).map((d) => {
      const k = String(d.key || '').trim().toLowerCase();
      if (k) deviceKeys.add(k);
      return {
        key: k,
        name: d.name || '',
        bonded: !!d.bonded,
        sightings: Number(d.sightings) || 1,
        lastSeenAt: Number(d.lastSeenAt) || 0
      };
    }).filter((d) => d.key !== '');

    // Include any attributed device not currently present in devices
    Object.keys(attributionByKey).forEach((k) => {
      if (!deviceKeys.has(k)) {
        const attr = attributionByKey[k];
        mergedList.push({
          key: k,
          name: attr.label || k,
          bonded: false,
          sightings: 1,
          lastSeenAt: 0
        });
      }
    });

    if (mergedList.length === 0) {
      bluetoothDevicesContainer.innerHTML =
        '<p style="color: var(--text-muted); font-style: italic;">' +
        'Das Telefon hat noch keine Bluetooth-Geräte in der Nähe gemeldet. ' +
        'Gekoppelte Geräte (z.B. Uhr, Auto, Lautsprecher) und regelmässig gesehene Geräte erscheinen nach dem nächsten Statusbericht hier.</p>';
      return;
    }

    mergedList.forEach((dev) => {
      const key = dev.key;
      const attr = attributionByKey[key];
      const kind = attr ? (attr.kind || '') : '';
      const label = attr ? (attr.label || '') : '';

      const subtitle = dev.bonded
        ? 'Gekoppeltes Gerät'
        : (dev.sightings === 1 ? '1 Mal in der Nähe gesehen' : `${dev.sightings} Mal in der Nähe gesehen`);

      const row = document.createElement('div');
      row.className = 'bt-device-row';
      row.dataset.key = key;
      row.style.cssText = 'padding: 0.75rem 0; border-bottom: 1px solid var(--border-color);';

      row.innerHTML = `
        <div style="margin-bottom: 0.4rem;">
          <strong style="color: var(--text-main); font-size: 1rem;">${escapeHtml(dev.name || key)}</strong>
          <div class="help-text" style="font-size: 0.85rem; margin-top: 0.1rem; margin-bottom: 0;">${escapeHtml(subtitle)}</div>
        </div>
        <div style="display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap;">
          <select class="bt-kind" style="flex: 1 1 140px; min-width: 130px; padding: 0.5rem 0.75rem; font-size: 0.95rem;">
            <option value=""${!kind ? ' selected' : ''}>Keine Zuordnung</option>
            <option value="PLACE"${kind === 'PLACE' ? ' selected' : ''}>📍 Ort</option>
            <option value="PERSON"${kind === 'PERSON' ? ' selected' : ''}>👤 Person</option>
            <option value="OWN_DEVICE"${kind === 'OWN_DEVICE' ? ' selected' : ''}>⌚ Eigenes Gerät</option>
          </select>
          <input type="text" class="bt-label" list="bluetooth-label-suggestions" maxlength="40"
                 placeholder="z.B. Zuhause oder Simon" value="${escapeHtml(label)}"
                 style="flex: 2 1 180px; min-width: 150px; padding: 0.5rem 0.75rem; font-size: 0.95rem; ${kind ? '' : 'display: none;'}">
        </div>
      `;

      const kindSelect = row.querySelector('.bt-kind');
      const labelInput = row.querySelector('.bt-label');

      function onKindChange() {
        if (!kindSelect.value) {
          labelInput.style.display = 'none';
          labelInput.disabled = true;
          labelInput.value = '';
          saveFields({ bluetoothAttributions: getBluetoothAttributions() });
        } else {
          labelInput.style.display = 'inline-block';
          labelInput.disabled = false;
          if (!labelInput.value.trim()) {
            if (kindSelect.value === 'PLACE') labelInput.value = 'Zuhause';
            else if (kindSelect.value === 'OWN_DEVICE') labelInput.value = 'Ihre Uhr';
            else if (dev.name && dev.name.toLowerCase() !== dev.key) labelInput.value = dev.name;
          }
          if (labelInput.value.trim()) {
            saveFields({ bluetoothAttributions: getBluetoothAttributions() });
          }
          labelInput.focus();
        }
      }

      kindSelect.addEventListener('change', onKindChange);
      labelInput.addEventListener('change', () => {
        if (kindSelect.value && labelInput.value.trim()) {
          saveFields({ bluetoothAttributions: getBluetoothAttributions() });
        }
      });

      bluetoothDevicesContainer.appendChild(row);
    });
  }

  /**
   * Collect all configured Bluetooth proximity attributions from the form.
   */
  function getBluetoothAttributions() {
    if (!bluetoothDevicesContainer) return [];
    const rows = bluetoothDevicesContainer.querySelectorAll('.bt-device-row');
    const attributions = [];
    rows.forEach((row) => {
      const key = (row.dataset.key || '').trim().toLowerCase();
      const kindSelect = row.querySelector('.bt-kind');
      const labelInput = row.querySelector('.bt-label');
      const kind = kindSelect ? kindSelect.value : '';
      const label = labelInput ? labelInput.value.trim() : '';
      if (key && kind && label) {
        attributions.push({ key: key, kind: kind, label: label });
      }
    });
    return attributions;
  }

  /**
   * The senders the phone takes for companies, each with a switch.
   *
   * Off means what the phone decided on its own, which is how every sender starts: a caregiver
   * opening this page sees the state the phone is already in, and nothing changes until they
   * change it. On means "this is a person" — a face, a place on her home screen, and a sound when
   * it arrives. What is written back is the *on* list, which is the smaller of the two and the
   * only half that is a decision anybody took.
   *
   * Read-only names, like the groups: the name is what her own screen calls the sender, so that a
   * switch is labelled with the words she would read. Typing one here would only be this page
   * disagreeing with her phone.
   */
  function renderSenders(senders, asPeople) {
    if (!sendersContainer) return;
    sendersContainer.innerHTML = '';

    if (!senders || senders.length === 0) {
      sendersContainer.innerHTML = '<p style="color: var(--text-muted); font-style: italic;">' +
        'Das Telefon hat noch keine Firmen-Absender gemeldet. Sobald eine solche Nachricht ' +
        'ankommt, erscheint der Absender hier.</p>';
      return;
    }

    const onKeys = {};
    (asPeople || []).forEach((key) => {
      if (typeof key === 'string') onKeys[normaliseSenderKey(key)] = true;
    });

    senders.forEach((sender) => {
      const key = normaliseSenderKey(sender.key);
      if (!key) return;

      const row = document.createElement('div');
      row.className = 'toggle-group';
      row.dataset.key = key;
      row.innerHTML = `
        <div class="toggle-label">
          <span>${escapeHtml(sender.name || '(ohne Namen)')}</span>
          <small>Wie eine Person behandeln: Gesicht, Hauptbildschirm, Ton</small>
        </div>
        <label class="switch">
          <input type="checkbox" class="sender-person"${onKeys[key] ? ' checked' : ''}>
          <span class="slider"></span>
        </label>
      `;

      row.querySelector('.sender-person').addEventListener('change', () => {
        // Which senders the phone should treat as people despite their address — see
        // getSendersAsPeople, and SenderJudgement.kt for what the phone does with them.
        saveFields({ sendersAsPeople: getSendersAsPeople() });
      });

      sendersContainer.appendChild(row);
    });
  }

  /**
   * The phone's own spelling of a sender's address — see SenderJudgement.keyOf, which this must
   * agree with exactly.
   *
   * Spacing and the punctuation people write telephone numbers with are dropped, letters lowered.
   * A key that disagrees with the phone's by one space is a switch that appears to do nothing.
   */
  function normaliseSenderKey(key) {
    return String(key || '').trim().replace(/[\s\-()./]/g, '').toLowerCase();
  }

  /**
   * The senders corrected to people: the ones switched on, plus the ones not on screen at all.
   *
   * The second half matters for the same reason it does with the groups. A sender the phone has
   * stopped publishing — quiet long enough to fall off the list, or published by a phone that was
   * offline when this page loaded — has no switch here, and a save that wrote only what it could
   * see would quietly undo the correction. The first sign of it would be her phone going silent
   * again for the surgery, weeks after somebody thought they had fixed exactly that.
   */
  function getSendersAsPeople() {
    const onScreen = {};
    (knownSenders || []).forEach((sender) => {
      const key = normaliseSenderKey(sender.key);
      if (key) onScreen[key] = true;
    });

    const carriedOver = ((currentSettings && currentSettings.sendersAsPeople) || [])
      .filter((key) => typeof key === 'string')
      .map(normaliseSenderKey)
      .filter((key) => key && !onScreen[key]);

    const switchedOn = sendersContainer
      ? Array.from(sendersContainer.querySelectorAll('.toggle-group'))
        .filter((row) => {
          const box = row.querySelector('.sender-person');
          return box && box.checked;
        })
        .map((row) => row.dataset.key)
      : [];

    return Array.from(new Set(carriedOver.concat(switchedOn))).sort();
  }

  /**
   * The starred contacts, in the order the caregiver has put them in.
   *
   * Read-only rows with two arrows. There is nothing to type here and nothing to delete: who is a
   * favourite is decided on the phone by starring somebody in the ordinary contacts app, where
   * they already have their real name, their number and their photograph. This screen decides
   * only the order — which matters, because the home screen shows the first two.
   *
   * The old version of this asked for a name, a telephone number and a photo *URL* in three text
   * boxes. That produced second copies of people already in the address book, without pictures,
   * going stale in silence — and it was the only reason the portal ever needed her contact
   * details at all.
   */
  function renderFavourites(starred, order) {
    contactsContainer.innerHTML = '';

    if (!starred || starred.length === 0) {
      contactsContainer.innerHTML = '<p style="color: var(--text-muted); font-style: italic;">' +
        'Auf dem Smartphone ist noch niemand mit einem Stern markiert. Markieren Sie in der ' +
        'Kontakte-App des Smartphones die wichtigsten Personen — sie erscheinen dann hier.</p>';
      return;
    }

    const arranged = arrangeFavourites(starred, order);

    arranged.forEach((contact, idx) => {
      const card = document.createElement('div');
      card.className = 'contact-card';
      card.dataset.id = contact.id;

      // The first two carry a note, because that is the part of this list she actually sees
      // without scrolling and the reason the order is worth setting at all.
      const onHomeScreen = idx < 2
        ? '<span style="color: var(--text-muted); font-size: 0.8rem;"> — auf dem Hauptbildschirm</span>'
        : '';

      card.innerHTML = `
        <div class="contact-header">
          <strong>#${idx + 1} ${escapeHtml(contact.name || '(ohne Namen)')}</strong>${onHomeScreen}
          <div class="contact-order-controls">
            <button type="button" class="btn-icon move-up" ${idx === 0 ? 'disabled' : ''}>▲</button>
            <button type="button" class="btn-icon move-down" ${idx === arranged.length - 1 ? 'disabled' : ''}>▼</button>
          </div>
        </div>
      `;

      card.querySelector('.move-up').addEventListener('click', () => moveFavourite(idx, -1));
      card.querySelector('.move-down').addEventListener('click', () => moveFavourite(idx, 1));

      contactsContainer.appendChild(card);
    });
  }

  /**
   * Starred contacts in the caregiver's order.
   *
   * The same rule the phone applies, and deliberately the same shape — see FavouriteArrangement
   * in the app. Anyone starred since the last arrangement is appended, never inserted: a face
   * arriving at the top of her home screen that nobody put there is a change she cannot account
   * for. Ids that are no longer starred fall out, because unstarring is the whole gesture for
   * removing somebody.
   */
  function arrangeFavourites(starred, order) {
    const byId = {};
    starred.forEach((c) => { byId[c.id] = c; });

    const seen = {};
    const arranged = [];
    (order || []).forEach((id) => {
      if (byId[id] && !seen[id]) {
        seen[id] = true;
        arranged.push(byId[id]);
      }
    });
    starred.forEach((c) => {
      if (!seen[c.id]) arranged.push(c);
    });

    return arranged;
  }

  /** The order as it currently stands on screen: ids, nothing else. */
  function getFavouriteOrder() {
    return Array.from(contactsContainer.querySelectorAll('.contact-card'))
      .map((card) => card.dataset.id);
  }

  function moveFavourite(idx, direction) {
    const order = getFavouriteOrder();
    const target = idx + direction;
    if (target < 0 || target >= order.length) return;

    const moved = order.slice();
    moved.splice(target, 0, moved.splice(idx, 1)[0]);
    renderFavourites(starredContacts, moved);
    // The support group follows the favourites' order, so its first face may have changed.
    supportMembers = collectSupportMembers();
    renderSupportMembers();

    // The order only. favoriteContacts is no longer written: who is a favourite comes from the
    // stars on the phone, and a list of names and numbers stored here would be a second copy of
    // her address book — stale the moment anybody fixes a spelling, and needing protection all
    // over again for no gain.
    // The support group follows that order too, so it is written alongside: saving the order
    // alone would leave her help button pointing at the face that used to be first.
    saveFields({ favouriteOrder: getFavouriteOrder(), supportGroup: supportMembers });
  }

  /* ---------------------------------------------------------------------------------------
   * What is wrong, and telling somebody who is not looking at this page.
   *
   * The rules below are the phone's, mirrored — see CaregiverAlerts.kt, which is where they are
   * decided and where they are tested. Mirrored rather than shared because there is no build step
   * here and no server anywhere: this portal is three static files, which is the reason it costs
   * nothing to run and the reason it cannot be sent a compiled constant.
   * ------------------------------------------------------------------------------------------ */

  /** Nothing for twelve hours means the phone is not running. See CaregiverAlerts.SILENCE_THRESHOLD_MS. */
  const SILENCE_THRESHOLD_MS = 12 * 60 * 60 * 1000;

  /** A fault older than a week is history, not a fault. See CaregiverAlerts.RECENT_WINDOW_MS. */
  const RECENT_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;

  /** The same fault may interrupt the same person once every six hours. See CaregiverAlerts.NOTIFY_COOLDOWN_MS. */
  const NOTIFY_COOLDOWN_MS = 6 * 60 * 60 * 1000;

  /**
   * How recent a position has to be to be spoken of in the present tense.
   *
   * Twice the heartbeat interval, so that one missed beat does not turn "the phone is at home"
   * into "the phone was at home" — but nothing older than that is described as where the phone
   * *is*. Somebody reading this may be deciding whether to drive somewhere.
   */
  const LOCATION_CURRENT_MS = 30 * 60 * 1000;

  /**
   * The German the phone would use, for each fault that travels.
   *
   * Deliberately the same sentences as `values-de/strings.xml` — the caregiver reads this page and
   * then, on a visit, reads the log on the phone itself, and two wordings for one fault is two
   * faults to somebody trying to work out what is happening.
   */
  function alertText(alert) {
    const who = alert.subject || 'jemand';
    switch (alert.kind) {
      case 'MESSAGE_NOT_SENT':
        return `Eine Nachricht an ${who} wurde nicht gesendet.`;
      case 'GROUP_MESSAGE_PART_SENT':
        return `Eine Nachricht an ${who} hat nur ${alert.count} von ${alert.total} Personen erreicht ` +
          'und wird ihr deshalb als nicht gesendet angezeigt.';
      case 'MESSENGER_CONNECTION_FAILED':
        return 'Der Messenger konnte sich nicht verbinden. Nachrichten können weder ankommen noch gesendet werden.';
      case 'MAIL_ACCESS_LOST':
        return 'Ihr Gmail kann nicht mehr gelesen werden, deshalb kommt keine neue Post an. ' +
          'Auf ihrem Telefon in der Betreuungsansicht erneut «Gmail verbinden» tippen.';
      case 'MMS_DROPPED_UNSUPPORTED':
        return 'Eine MMS (Bildnachricht über Mobilfunk) ist eingegangen, konnte aber nicht empfangen werden: MMS wird auf diesem Telefon nicht unterstützt. Bitte Fotos per WhatsApp, Threema oder E-Mail senden.';
      default:
        // A kind this version of the page does not know. Dropped rather than shown as a blank
        // line, exactly as CaregiverEventMapper drops an unknown row on the phone.
        return '';
    }
  }

  /**
   * Whether a messenger was up, however the phone happened to say so.
   *
   * Old builds sent a boolean, current ones send the word. Anything else is left out rather than
   * guessed at: "getrennt" printed because a field was missing is a fault reported that never
   * happened.
   */
  function connectionWord(value) {
    if (typeof value === 'boolean') return value ? 'verbunden' : 'getrennt';
    if (typeof value === 'string' && value.trim() !== '') return value.trim();
    return '';
  }

  /**
   * A fault named in German, from the bare kind the help request carries.
   *
   * Short labels rather than the sentences in `alertText`: a request carries the kinds only, with
   * no subject to put in "Eine Nachricht an Anna …". An unknown kind is shown as it arrived —
   * this page is the caregiver's, and a fault silently dropped is a fault nobody looks for.
   */
  function faultLabel(kind) {
    switch (kind) {
      case 'MESSAGE_NOT_SENT': return 'Nachricht nicht gesendet';
      case 'GROUP_MESSAGE_PART_SENT': return 'Gruppennachricht nur teilweise gesendet';
      case 'MESSENGER_CONNECTION_FAILED': return 'Messenger ohne Verbindung';
      case 'MESSAGES_LOST_ON_ARRIVAL': return 'Nachrichten beim Empfang verloren';
      case 'SMS_NOT_STORED': return 'SMS nicht gespeichert';
      case 'MAIL_ACCESS_LOST': return 'Gmail-Zugriff verloren';
      case 'MMS_DROPPED_UNSUPPORTED': return 'MMS nicht empfangen';
      case 'CONTACT_SYNC_FAILED': return 'Kontaktabgleich fehlgeschlagen';
      default: return String(kind);
    }
  }

  /** "vor 20 Minuten", "vor 3 Stunden", "vor 2 Tagen" — coarse on purpose; precision is not the point. */
  function agoText(ms) {
    const minutes = Math.floor(ms / 60000);
    if (minutes < 2) return 'gerade eben';
    if (minutes < 60) return `vor ${minutes} Minuten`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return hours === 1 ? 'vor 1 Stunde' : `vor ${hours} Stunden`;
    const days = Math.floor(hours / 24);
    return days === 1 ? 'vor 1 Tag' : `vor ${days} Tagen`;
  }

  /** Kind and subject, matching CaregiverAlerts.signature: the same fault about the same person. */
  function alertSignature(alert) {
    return `${alert.kind}|${(alert.subject || '').toLowerCase()}`;
  }

  /**
   * A map anybody can open, from two numbers.
   *
   * OpenStreetMap rather than an embedded map: an embed means a tile provider, an API key and a
   * third party learning where she lives every time this tab is left open. A link is one address,
   * followed only if somebody chooses to follow it. `mlat`/`mlon` drop the marker; the fragment
   * sets what the map opens on.
   *
   * The zoom is taken from the accuracy, because a map opened at street level around a fix that is
   * good to two kilometres shows a confident marker on the wrong street corner.
   */
  function mapUrl(latitude, longitude, accuracyMetres) {
    const zoom = !accuracyMetres || accuracyMetres <= 100 ? 17 : (accuracyMetres <= 1000 ? 15 : 12);
    return 'https://www.openstreetmap.org/?mlat=' + latitude + '&mlon=' + longitude +
      '#map=' + zoom + '/' + latitude + '/' + longitude;
  }

  /**
   * Say where the phone was, and be exact about how long ago that was true.
   *
   * The phone sends a position with the moment it was *measured*, which is frequently older than
   * the heartbeat that carried it — the phone can only place itself while it is awake, and this
   * one spends its nights on a shelf. So the age is never rounded away and never implied: a fix
   * from this morning says so, in the past tense, with the hours on it.
   *
   * Absent is a normal state and gets no line at all. The permission may have been declined, the
   * phone may be running a build from before this existed, or nobody may have paired yet — the
   * phone publishes nothing until somebody has. None of those are faults and none of them belong
   * in a card about what has gone wrong.
   */
  function renderLocation(now) {
    const fix = lastLocation;
    const usable = heartbeatKnown && fix &&
      typeof fix.latitude === 'number' && typeof fix.longitude === 'number' &&
      isFinite(fix.latitude) && isFinite(fix.longitude);

    if (!usable) {
      locationLine.style.display = 'none';
      locationLine.innerHTML = '';
      return;
    }

    const accuracy = typeof fix.accuracyMetres === 'number' && fix.accuracyMetres > 0
      ? fix.accuracyMetres
      : null;
    const measuredAt = typeof fix.at === 'number' ? fix.at : null;
    const age = measuredAt === null ? null : Math.max(0, now - measuredAt);

    let when;
    if (age === null) {
      when = 'Standort des Smartphones';
    } else if (age <= LOCATION_CURRENT_MS) {
      when = 'Das Smartphone ist hier (' + agoText(age) + ' gemessen)';
    } else {
      when = 'Zuletzt war das Smartphone hier: ' + agoText(age);
    }

    // German decimal comma, because everything else on this page is German and "1.8 km" reads as
    // eighteen hundred kilometres to half of the people who will see it.
    const howExact = accuracy === null
      ? ''
      : ' · auf etwa ' + (accuracy >= 1000
        ? String(Math.round(accuracy / 100) / 10).replace('.', ',') + ' km'
        : accuracy + ' m') + ' genau';

    // The coordinates are put through Number() as well as typeof-checked above, so nothing from
    // the document can reach the href as text.
    const href = mapUrl(Number(fix.latitude), Number(fix.longitude), accuracy);
    locationLine.innerHTML = escapeHtml(when + howExact) +
      ' · <a href="' + escapeHtml(href) + '" target="_blank" rel="noopener noreferrer">Auf der Karte zeigen</a>';
    locationLine.style.display = 'block';
  }

  /**
   * Say with whom and where the phone was detected via Bluetooth proximity attribution.
   *
   * Displays place, people and own devices detected in reach on the heartbeat.
   * Hidden when no attributions match or when the phone has never reported.
   */
  function renderLocatedWith(now) {
    if (!locatedWithLine) return;
    if (!heartbeatKnown || !lastLocatedWith) {
      locatedWithLine.style.display = 'none';
      locatedWithLine.innerHTML = '';
      return;
    }

    const place = (typeof lastLocatedWith.place === 'string' && lastLocatedWith.place.trim())
      ? lastLocatedWith.place.trim()
      : null;
    const people = Array.isArray(lastLocatedWith.people)
      ? lastLocatedWith.people.filter((p) => typeof p === 'string' && p.trim() !== '')
      : [];
    const ownDevices = Array.isArray(lastLocatedWith.ownDevices)
      ? lastLocatedWith.ownDevices.filter((d) => typeof d === 'string' && d.trim() !== '')
      : [];

    if (!place && people.length === 0 && ownDevices.length === 0) {
      locatedWithLine.style.display = 'none';
      locatedWithLine.innerHTML = '';
      return;
    }

    const age = typeof lastLocatedWith.at === 'number' ? Math.max(0, now - lastLocatedWith.at) : null;
    const placeAtMs = typeof lastLocatedWith.placeAt === 'number'
      ? lastLocatedWith.placeAt
      : (typeof lastLocatedWith.at === 'number' ? lastLocatedWith.at : null);
    const placeAge = placeAtMs !== null ? Math.max(0, now - placeAtMs) : null;
    const placeCarriedOver = Boolean(
      place && placeAge !== null && age !== null &&
      (placeAge - age) > 2 * 60 * 1000 && placeAge > LOCATION_CURRENT_MS
    );

    const parts = [];
    if (place) {
      parts.push(placeCarriedOver ? ('📍 Zuletzt ' + place + ' (' + agoText(placeAge) + ')') : ('📍 ' + place));
    }
    if (people.length > 0) parts.push('👤 Bei ' + people.join(', '));
    if (ownDevices.length > 0) parts.push('⌚ ' + ownDevices.join(', ') + ' in Reichweite');

    let text = '';
    if (age === null) {
      text = parts.join(' · ');
    } else if (age <= LOCATION_CURRENT_MS) {
      text = parts.join(' · ') + ' (' + agoText(age) + ')';
    } else {
      text = 'Zuletzt erkannt (' + agoText(age) + '): ' + parts.join(' · ');
    }

    locatedWithLine.innerHTML = '<strong>Bei wem &amp; wo:</strong> ' + escapeHtml(text);
    locatedWithLine.style.display = 'block';
  }

  /**
   * Watch what the phone says about itself.
   *
   * A live listener, not a fetch, because the entire point is the caregiver who left the tab open
   * on a laptop in another town. `status/current` is written by the phone and read-only to this
   * page — see firestore.rules — so there is nothing here that can be acknowledged away.
   */
  function listenToStatus(deviceId) {
    if (unsubscribeStatus) {
      unsubscribeStatus();
      unsubscribeStatus = null;
    }
    currentAlerts = [];
    statusEverLoaded = false;

    if (!db || !deviceId) return;

    unsubscribeStatus = db.collection('devices').doc(deviceId)
      .collection('status').doc('current')
      .onSnapshot((snap) => {
        statusEverLoaded = true;
        currentAlerts = (snap.exists ? (snap.data().alerts || []) : []);
        renderStatus();
      }, () => {
        // Denied or unreachable. Left silent: a phone that has never published a status document
        // is the ordinary case on a device running an older build, and a red banner about a
        // missing document would be the page's own fault report crowding out hers.
        statusEverLoaded = true;
        currentAlerts = [];
        renderStatus();
      });
  }

  /**
   * Draw the state of the phone, and raise anything new.
   *
   * Re-run on a timer as well as on every snapshot, because the most important condition here is
   * an absence: twelve hours passing with nothing arriving produces no event to react to.
   */
  function renderStatus() {
    if (!currentDeviceId) {
      statusCard.style.display = 'none';
      document.title = baseTitle;
      return;
    }

    statusCard.style.display = 'block';

    const now = Date.now();
    const quiet = heartbeatKnown && lastSeenAtMs !== null && (now - lastSeenAtMs) >= SILENCE_THRESHOLD_MS;
    const neverHeard = heartbeatKnown && lastSeenAtMs === null;

    if (!heartbeatKnown) {
      heartbeatLine.textContent = 'Wird geladen…';
    } else if (neverHeard) {
      heartbeatLine.innerHTML = '<strong style="color: var(--danger-color);">Dieses Smartphone hat sich noch nie gemeldet.</strong>';
    } else if (quiet && managedReportedRecently(now)) {
      // The management app is a second witness on the same phone. It still reporting while this
      // app has gone quiet means the phone is on and online — it is gentle connection that is down.
      heartbeatLine.innerHTML = '<strong style="color: var(--danger-color);">Seit ' + escapeHtml(agoText(now - lastSeenAtMs)) +
        ' keine Lebenszeichen von gentle connection.</strong> Das Smartphone selbst ist an und im Internet ' +
        '(die Geräteverwaltung meldet sich), aber die App läuft nicht. Nachrichten kommen dann weder an noch weg.';
    } else if (quiet) {
      heartbeatLine.innerHTML = '<strong style="color: var(--danger-color);">Seit ' + escapeHtml(agoText(now - lastSeenAtMs)) +
        ' keine Lebenszeichen.</strong> Das Smartphone ist vermutlich aus, ohne Internet, oder die App läuft nicht mehr. ' +
        'Nachrichten kommen dann weder an noch weg.';
    } else {
      heartbeatLine.innerHTML = 'Zuletzt gemeldet: ' + escapeHtml(agoText(now - lastSeenAtMs)) + '. Alles läuft.';
    }

    renderLocation(now);
    renderLocatedWith(now);

    // Faults older than a week are dropped here as well as on the phone. The phone only rewrites
    // this list when something changes, so without this a fault from a fortnight ago would sit on
    // the page until the next one came along to push it off.
    const fresh = currentAlerts
      .filter((a) => typeof a.at === 'number' && (now - a.at) <= RECENT_WINDOW_MS)
      .filter((a) => alertText(a) !== '');

    alertsContainer.innerHTML = '';
    if (fresh.length === 0) {
      alertsContainer.innerHTML = statusEverLoaded
        ? '<p style="color: var(--text-muted); font-style: italic;">In der letzten Woche ist nichts schiefgegangen.</p>'
        : '<p style="color: var(--text-muted); font-style: italic;">Wird geladen…</p>';
    } else {
      fresh.forEach((alert) => {
        const row = document.createElement('div');
        row.className = 'alert-row';
        const repeated = alert.occurrences > 1 ? ` · ${alert.occurrences} Mal passiert` : '';
        row.innerHTML =
          `<div class="alert-what">${escapeHtml(alertText(alert))}</div>` +
          `<div class="alert-when">${escapeHtml(agoText(Math.max(0, now - alert.at)) + repeated)}</div>`;
        alertsContainer.appendChild(row);
      });
    }

    // A tab in the background is the normal state of this page. The count in the title is the one
    // signal that works with notifications switched off, on iOS, and behind a corporate policy.
    const outstanding = fresh.length + (quiet || neverHeard ? 1 : 0);
    document.title = outstanding > 0 ? `(${outstanding}) ${baseTitle}` : baseTitle;

    renderNotificationControls();
    raiseNotifications(fresh, quiet, neverHeard);
    // The report's age moves on with the clock, not with snapshots.
    renderManaged();
  }

  function notifiedStore() {
    try {
      return JSON.parse(localStorage.getItem('gc_notified_' + currentDeviceId) || '{}');
    } catch (err) {
      return {};
    }
  }

  function rememberNotified(store) {
    try {
      localStorage.setItem('gc_notified_' + currentDeviceId, JSON.stringify(store));
    } catch (err) {
      // Private browsing, or a full quota. The notification still went out; only the memory of it
      // is lost, and the cost of that is seeing the same fault twice.
    }
  }

  /**
   * Interrupt somebody, at most once every six hours per fault.
   *
   * A browser notification and nothing more: it needs no server, no billing and no push key, and
   * it reaches a caregiver who has this tab open behind their work. What it cannot do is reach
   * somebody who has closed it — that needs a sender, and there isn't one.
   */
  function raiseNotifications(fresh, quiet, neverHeard) {
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;

    const now = Date.now();
    const store = notifiedStore();
    const items = fresh.map((a) => ({ sig: alertSignature(a), body: alertText(a) }));

    if (quiet || neverHeard) {
      items.unshift({
        sig: 'PHONE_QUIET',
        body: neverHeard
          ? 'Das Smartphone hat sich noch nie gemeldet.'
          : 'Das Smartphone meldet sich seit ' + agoText(now - lastSeenAtMs) + ' nicht mehr.'
      });
    }

    let changed = false;
    items.forEach((item) => {
      const last = store[item.sig] || 0;
      if (now - last < NOTIFY_COOLDOWN_MS) return;
      store[item.sig] = now;
      changed = true;
      try {
        new Notification('gentle connection', { body: item.body, tag: item.sig });
      } catch (err) {
        // Some browsers refuse the constructor outside a service worker. Nothing to do but let
        // the page itself carry the message, which it already does.
      }
    });

    if (changed) rememberNotified(store);
  }

  function renderNotificationControls() {
    if (typeof Notification === 'undefined') {
      enableNotificationsBtn.style.display = 'none';
      notificationHint.textContent =
        'Dieser Browser kann keine Benachrichtigungen anzeigen. Diese Seite offen zu lassen genügt: ' +
        'die Anzahl offener Punkte steht im Tab-Titel.';
      return;
    }

    if (Notification.permission === 'granted') {
      enableNotificationsBtn.style.display = 'none';
      notificationHint.textContent =
        'Benachrichtigungen sind eingeschaltet. Sie erscheinen, solange diese Seite in einem ' +
        'Tab geöffnet ist — auch im Hintergrund. Ist die Seite geschlossen, erscheint nichts.';
      return;
    }

    enableNotificationsBtn.style.display = 'inline-flex';
    notificationHint.textContent = Notification.permission === 'denied'
      ? 'Benachrichtigungen sind für diese Seite im Browser blockiert. Sie lassen sich in den ' +
        'Website-Einstellungen des Browsers wieder erlauben.'
      : 'Schalten Sie Benachrichtigungen ein, damit Sie es mitbekommen, ohne diese Seite anzusehen. ' +
        'Voraussetzung ist, dass die Seite in einem Tab geöffnet bleibt.';
  }

  enableNotificationsBtn.addEventListener('click', async () => {
    if (typeof Notification === 'undefined') return;
    try {
      await Notification.requestPermission();
    } catch (err) {
      // Older Safari hands back a callback rather than a promise. Either way the next render
      // reads Notification.permission, which is the only state that matters.
    }
    renderStatus();
  });

  // The clock, for the one condition nothing pushes: silence.
  setInterval(() => {
    if (currentDeviceId) renderStatus();
  }, 60000);

  // Unpair button
  unpairBtn.addEventListener('click', () => {
    currentDeviceId = '';
    localStorage.removeItem('gc_device_id');
    settingsCard.style.display = 'none';
    if (deviceNameCard) deviceNameCard.style.display = 'none';
    stopWatchingStatus();
    if (deviceSelect) deviceSelect.value = '';
    history.pushState(null, '', window.location.pathname + (window.location.search || ''));
    updateView();
  });

  /* ---------------------------------------------------------------------------------------
   * Immediate save on change & Channel presets
   * --------------------------------------------------------------------------------------- */

  /**
   * Channel switch presets.
   *
   * Presets deliberately do NOT touch payment, call blocking, quiet hours or anything else
   * that is a decision about her. They adjust only the seven channel switches.
   */
  const CHANNEL_PRESETS = {
    ruhig: {
      enableMessages: true,
      enableMail: false,
      enableCalendar: true,
      showBirthdays: true,
      showSteps: true,
      enableNudge: false,
      enableSearch: false,
      enableEmojiChoice: false,
      enablePhotoSending: false,
      enableCameraSwitch: false
    },
    normal: {
      enableMessages: true,
      enableMail: true,
      enableCalendar: true,
      showBirthdays: true,
      showSteps: true,
      enableNudge: true,
      enableSearch: false,
      enableEmojiChoice: false,
      enablePhotoSending: false,
      enableCameraSwitch: false
    },
    alles: {
      enableMessages: true,
      enableMail: true,
      enableCalendar: true,
      showBirthdays: true,
      showSteps: true,
      enableNudge: true,
      enableSearch: true,
      enableEmojiChoice: true,
      enablePhotoSending: true,
      enableCameraSwitch: false
    }
  };

  function getCurrentChannelValues() {
    return {
      enableMessages: enableMessagesInput ? enableMessagesInput.checked : true,
      enableMail: enableMailInput ? enableMailInput.checked : true,
      enableCalendar: enableCalendarInput ? enableCalendarInput.checked : true,
      showBirthdays: showBirthdaysInput ? showBirthdaysInput.checked : true,
      showSteps: showStepsInput ? showStepsInput.checked : true,
      enableNudge: enableNudgeInput ? enableNudgeInput.checked : true,
      enableSearch: enableSearchInput ? enableSearchInput.checked : false,
      enableEmojiChoice: enableEmojiChoiceInput ? enableEmojiChoiceInput.checked : false,
      enablePhotoSending: enablePhotoSendingInput ? enablePhotoSendingInput.checked : false,
      enableCameraSwitch: enableCameraSwitchInput ? enableCameraSwitchInput.checked : false
    };
  }

  function getActivePreset() {
    const current = getCurrentChannelValues();
    const names = ['ruhig', 'normal', 'alles'];
    for (let i = 0; i < names.length; i++) {
      const name = names[i];
      const preset = CHANNEL_PRESETS[name];
      const match = Object.keys(preset).every((k) => current[k] === preset[k]);
      if (match) return name;
    }
    return null;
  }

  function updatePresetUI(isInitialPopulate) {
    const active = getActivePreset();
    const presetButtons = document.querySelectorAll('.preset-btn');
    presetButtons.forEach((btn) => {
      const isActive = btn.dataset.preset === active;
      btn.setAttribute('aria-pressed', isActive ? 'true' : 'false');
      btn.classList.toggle('is-active', isActive);
    });

    if (presetCustomState) {
      presetCustomState.style.display = active ? 'none' : 'inline-flex';
    }

    if (channelSwitchesDetails) {
      if (isInitialPopulate) {
        channelSwitchesDetails.open = !active;
      }
    }
  }

  let saveChain = Promise.resolve();
  let inFlightSaves = 0;
  let savePillTimer = null;

  function setStatusPill(state, text) {
    if (!saveStatusPill) return;
    if (savePillTimer) {
      clearTimeout(savePillTimer);
      savePillTimer = null;
    }
    saveStatusPill.className = 'status-pill status-pill-' + state;
    saveStatusPill.textContent = text;
    saveStatusPill.style.display = 'inline-flex';

    if (state === 'success') {
      savePillTimer = setTimeout(() => {
        saveStatusPill.style.display = 'none';
        savePillTimer = null;
      }, 2500);
    }
  }

  /**
   * Save a partial settings update directly to Firestore and merge into currentSettings.
   *
   * pairingCode and isPaired are not written here. Both are the phone's to maintain — it
   * stamps a fresh code every time the caregiver dialog is opened — and this save is a merge,
   * so leaving them out keeps whatever the phone last put there instead of overwriting a live
   * code with a stale copy the browser happened to be holding. Who is paired is now answered
   * by devices/{id}.caregiverUids, which the phone reads directly.
   */
  async function saveFields(partial) {
    if (!db || !currentDeviceId) return;

    // Taken now, not when the write reaches the front of the queue: a caregiver who switches
    // phones while a save is still waiting must not have it land on the phone they switched to.
    const deviceId = currentDeviceId;
    const cleanPartial = Object.assign({}, partial);
    delete cleanPartial.pairingCode;
    delete cleanPartial.isPaired;

    inFlightSaves++;
    setStatusPill('saving', 'Wird gespeichert…');

    saveChain = saveChain.then(async () => {
      try {
        await db.collection('devices').doc(deviceId)
          .collection('settings').doc('current')
          .set(cleanPartial, { merge: true });

        if (currentSettings && deviceId === currentDeviceId) {
          Object.assign(currentSettings, cleanPartial);
        }

        // The list still shows the old name until the label is redrawn: the devices listener
        // watches the device documents, and the name does not live in one.
        if (Object.prototype.hasOwnProperty.call(cleanPartial, 'deviceName')) {
          deviceNameById[deviceId] = cleanPartial.deviceName;
          const renamed = deviceSelect
            ? deviceSelect.querySelector(`option[value="${deviceId}"]`)
            : null;
          if (renamed) renamed.textContent = deviceLabel(deviceId);
        }

        updatePresetUI(false);

        inFlightSaves--;
        if (inFlightSaves === 0) {
          setStatusPill('success', '✓ Auf dem Telefon gespeichert');
        }
      } catch (err) {
        inFlightSaves--;
        setStatusPill('danger', 'Nicht gespeichert — ' + parseErrorMessage(err));
        if (currentSettings && deviceId === currentDeviceId) {
          populateSettingsForm(currentSettings);
        }
      }
    }, (chainErr) => {
      inFlightSaves--;
      setStatusPill('danger', 'Nicht gespeichert — ' + parseErrorMessage(chainErr));
      if (currentSettings) {
        populateSettingsForm(currentSettings);
      }
    });

    return saveChain;
  }

  /**
   * Table mapping input controls to settings field names and conversion readers.
   * Keeps field conversions and explanatory comments in one single place.
   */
  const STATIC_SETTINGS_BINDINGS = [
    {
      element: textSizeInput,
      field: 'textSizeMultiplier',
      read: () => parseFloat(textSizeInput.value) || 1.0
    },
    {
      element: photoSizeInput,
      field: 'photoSizeMultiplier',
      read: () => parseFloat(photoSizeInput.value) || 1.0
    },
    {
      element: speechRateInput,
      field: 'speechRate',
      read: () => parseFloat(speechRateInput.value) || 0.8
    },
    {
      element: darkModeInput,
      field: 'isDarkMode',
      read: () => darkModeInput.checked
    },
    {
      element: enableMessagesInput,
      field: 'enableMessages',
      read: () => enableMessagesInput.checked
    },
    {
      element: enableMailInput,
      field: 'enableMail',
      read: () => enableMailInput.checked
    },
    {
      element: mailOnlyKnownSendersInput,
      field: 'mailOnlyKnownSenders',
      read: () => mailOnlyKnownSendersInput.checked
    },
    {
      element: enableNudgeInput,
      field: 'enableNudge',
      read: () => enableNudgeInput.checked
    },
    {
      element: enableCalendarInput,
      field: 'enableCalendar',
      read: () => enableCalendarInput.checked
    },
    {
      // What this phone is called in the caregiver's own list. Kept here rather than on the
      // device document because that document is the phone's: the rules let a caregiver write
      // nothing on it but the claim.
      element: deviceNameInput,
      field: 'deviceName',
      read: () => deviceNameInput.value.trim()
    },
    {
      // Whether the payment card is on her home screen — see PaymentButton on the phone.
      element: enableWalletInput,
      field: 'enableWallet',
      read: () => enableWalletInput.checked
    },
    {
      // Whether the "card is ready" notice sits under the clock — see PaymentReadyNotice.
      element: showPaymentReadyNoticeInput,
      field: 'showPaymentReadyNotice',
      read: () => showPaymentReadyNoticeInput.checked
    },
    {
      // How she unlocks the card — see PaymentAuthMethod on the phone.
      element: paymentAuthMethodInput,
      field: 'paymentAuthMethod',
      read: () => paymentAuthMethodInput.value
    },
    {
      // Where the photo button sits on Home — see PhotoButtonPlacement on the phone.
      element: photoButtonPlacementInput,
      field: 'photoButtonPlacement',
      read: () => photoButtonPlacementInput.value
    },
    {
      // Whether birthdays are among her appointments — see Agenda.isBirthday on the phone.
      element: showBirthdaysInput,
      field: 'showBirthdays',
      read: () => showBirthdaysInput.checked
    },
    {
      element: showStepsInput,
      field: 'showSteps',
      read: () => showStepsInput.checked
    },
    {
      element: missedCallHoursInput,
      field: 'missedCallHours',
      read: () => parseInt(missedCallHoursInput.value, 10)
    },
    {
      element: blockUnknownCallersInput,
      field: 'blockUnknownCallers',
      read: () => blockUnknownCallersInput.checked
    },
    {
      element: showBlockedCallsInCallLogInput,
      field: 'showBlockedCallsInCallLog',
      read: () => showBlockedCallsInCallLogInput.checked
    },
    {
      element: notifyBlockedCallsInput,
      field: 'notifyBlockedCalls',
      read: () => notifyBlockedCallsInput.checked
    },
    {
      // Whether WhatsApp calls are turned away as they ring — see CallCourtesy on the phone.
      element: rejectWhatsAppCallsInput,
      field: 'rejectWhatsAppCalls',
      read: () => rejectWhatsAppCallsInput.checked
    },
    {
      // Whether calls go only over Threema while roaming — see CallRouter on the phone.
      element: threemaOnlyWhenRoamingInput,
      field: 'threemaOnlyWhenRoaming',
      read: () => threemaOnlyWhenRoamingInput.checked
    },
    {
      // Whether unknown Threema callers are rejected silently — see VoipCallManager on the phone.
      element: blockUnknownThreemaCallersInput,
      field: 'blockUnknownThreemaCallers',
      read: () => blockUnknownThreemaCallersInput.checked
    },
    {
      // Whether a call that cannot be made is also read aloud — see CallFailedActivity on the phone.
      element: speakCallFailuresInput,
      field: 'speakCallFailures',
      read: () => speakCallFailuresInput.checked
    },
    {
      element: enableSearchInput,
      field: 'enableSearch',
      read: () => enableSearchInput.checked
    },
    {
      element: enableEmojiChoiceInput,
      field: 'enableEmojiChoice',
      read: () => enableEmojiChoiceInput.checked
    },
    {
      element: enablePhotoSendingInput,
      field: 'enablePhotoSending',
      read: () => enablePhotoSendingInput.checked
    },
    {
      element: enableCameraSwitchInput,
      field: 'enableCameraSwitch',
      read: () => enableCameraSwitchInput.checked
    },
    {
      element: quietFromInput,
      field: 'quietFromHour',
      read: () => parseInt(quietFromInput.value, 10)
    },
    {
      element: quietUntilInput,
      field: 'quietUntilHour',
      read: () => parseInt(quietUntilInput.value, 10)
    },
    {
      element: ringSilenceTimeoutInput,
      field: 'ringSilenceTimeoutSeconds',
      read: () => parseInt(ringSilenceTimeoutInput.value, 10)
    },
    {
      // Whether the companies are on her phone at all — see SenderJudgement.shown. The corrected
      // senders above survive this, which is what makes "keine Firmen, ausser der Praxis" sayable.
      element: showCompanyMessagesInput,
      field: 'showCompanyMessages',
      read: () => showCompanyMessagesInput.checked
    },
    {
      // Support group settings
      element: supportGroupNameInput,
      field: 'supportGroupName',
      read: () => (supportGroupNameInput ? supportGroupNameInput.value : '').trim() || 'Familie'
    },
    {
      // Support group settings
      element: supportPersonNameInput,
      field: 'supportPersonName',
      read: () => (supportPersonNameInput ? supportPersonNameInput.value : '').trim() || 'Mama'
    },
    {
      // Support group settings
      element: supportShareLocationInput,
      field: 'supportShareLocation',
      read: () => (supportShareLocationInput ? supportShareLocationInput.checked : true)
    }
  ];

  function initStaticSettingsBindings() {
    STATIC_SETTINGS_BINDINGS.forEach(({ element, field, read }) => {
      if (!element) return;
      element.addEventListener('change', () => {
        if (!currentDeviceId) return;
        const partial = {};
        partial[field] = read();
        saveFields(partial);
      });
    });

    document.querySelectorAll('.preset-btn').forEach((btn) => {
      btn.addEventListener('click', async () => {
        if (!currentDeviceId) return;
        const presetKey = btn.dataset.preset;
        const values = CHANNEL_PRESETS[presetKey];
        if (!values) return;

        enableMessagesInput.checked = values.enableMessages;
        enableMailInput.checked = values.enableMail;
        enableCalendarInput.checked = values.enableCalendar;
        showBirthdaysInput.checked = values.showBirthdays;
        showStepsInput.checked = values.showSteps;
        enableNudgeInput.checked = values.enableNudge;
        enableSearchInput.checked = values.enableSearch;
        enableEmojiChoiceInput.checked = values.enableEmojiChoice;
        enablePhotoSendingInput.checked = values.enablePhotoSending;
        enableCameraSwitchInput.checked = values.enableCameraSwitch;

        if (channelSwitchesDetails) channelSwitchesDetails.open = false;
        updatePresetUI(false);
        await saveFields(Object.assign({}, values));
      });
    });
  }

  function escapeHtml(str) {
    return String(str || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  /* ---------------------------------------------------------------------------------------
   * Gerät: what gentle device, the management app, reports about the phone
   * --------------------------------------------------------------------------------------- */

  /**
   * Watch the management app's reports, policy, commands and audit entries.
   * Non-owners can view the report and audit log (Protokoll).
   * Owners can steer the phone via policy toggles, commands, and consent.
   */
  function listenToManaged(deviceId) {
    stopWatchingManaged();
    if (!db || !deviceId) return;
    const deviceRef = db.collection('devices').doc(deviceId);
    const managed = deviceRef.collection('managed');
    unsubscribeManagedState = managed.doc('state').onSnapshot((snap) => {
      managedState = snap.exists ? snap.data() : null;
      renderManaged();
    }, () => {
      managedState = null;
      renderManaged();
    });
    unsubscribeManagedCompliance = managed.doc('compliance').onSnapshot((snap) => {
      managedCompliance = snap.exists ? snap.data() : null;
      renderManaged();
    }, () => {
      managedCompliance = null;
      renderManaged();
    });
    const access = deviceRef.collection('access');
    unsubscribeAccessOwners = access.doc('owners').onSnapshot((snap) => {
      accessOwners = snap.exists ? snap.data() : null;
      renderManaged();
    }, () => {
      // Permission error or missing doc just means "no owners"
      accessOwners = null;
      renderManaged();
    });
    const policy = deviceRef.collection('policy');
    unsubscribePolicyCurrent = policy.doc('current').onSnapshot((snap) => {
      policyCurrent = snap.exists ? snap.data() : null;
      renderManaged();
    }, () => {
      policyCurrent = null;
      renderManaged();
    });
    unsubscribePolicyConsent = policy.doc('consent').onSnapshot((snap) => {
      policyConsent = snap.exists ? snap.data() : null;
      renderManaged();
    }, () => {
      policyConsent = null;
      renderManaged();
    });
    unsubscribeCommands = deviceRef.collection('commands')
      .orderBy('createdAt', 'desc')
      .limit(5)
      .onSnapshot((snap) => {
        const cmds = [];
        snap.forEach((doc) => {
          cmds.push(Object.assign({ id: doc.id }, doc.data()));
        });
        recentCommands = cmds;
        renderManaged();
      }, () => {
        recentCommands = [];
        renderManaged();
      });
    unsubscribeAudit = deviceRef.collection('audit')
      .orderBy('at', 'desc')
      .limit(20)
      .onSnapshot((snap) => {
        const entries = [];
        snap.forEach((doc) => {
          entries.push(Object.assign({ id: doc.id }, doc.data()));
        });
        recentAudits = entries;
        renderManaged();
      }, () => {
        recentAudits = [];
        renderManaged();
      });
  }

  function stopWatchingManaged() {
    if (unsubscribeManagedState) {
      unsubscribeManagedState();
      unsubscribeManagedState = null;
    }
    if (unsubscribeManagedCompliance) {
      unsubscribeManagedCompliance();
      unsubscribeManagedCompliance = null;
    }
    if (unsubscribeAccessOwners) {
      unsubscribeAccessOwners();
      unsubscribeAccessOwners = null;
    }
    if (unsubscribePolicyCurrent) {
      unsubscribePolicyCurrent();
      unsubscribePolicyCurrent = null;
    }
    if (unsubscribePolicyConsent) {
      unsubscribePolicyConsent();
      unsubscribePolicyConsent = null;
    }
    if (unsubscribeCommands) {
      unsubscribeCommands();
      unsubscribeCommands = null;
    }
    if (unsubscribeAudit) {
      unsubscribeAudit();
      unsubscribeAudit = null;
    }
    managedState = null;
    managedCompliance = null;
    accessOwners = null;
    policyCurrent = null;
    policyConsent = null;
    recentCommands = [];
    recentAudits = [];
    selectedLaunchablePackage = null;
    if (managedCard) managedCard.style.display = 'none';
  }

  function millisOf(value) {
    if (!value) return null;
    if (value.toMillis) return value.toMillis();
    return typeof value === 'number' ? value : null;
  }

  function gigabytes(bytes) {
    return (Math.round(bytes / 1e8) / 10).toString().replace('.', ',') + ' GB';
  }

  /**
   * The management app wrote its report in the last two hours. While gentle connection is
   * unhealthy it reports at least hourly, so a fresh report next to a silent heartbeat means
   * "phone on, app down" rather than "phone off".
   */
  function managedReportedRecently(now) {
    const at = managedState ? millisOf(managedState.updatedAt) : null;
    return at !== null && now - at < 2 * 60 * 60 * 1000;
  }

  function getGoogleProviderData(user) {
    if (!user || !Array.isArray(user.providerData)) return null;
    return user.providerData.find((p) => p && p.providerId === 'google.com') || null;
  }

  /**
   * Link the current anonymous session to a Google account.
   *
   * Why linking instead of signing in directly: the anonymous uid is the one stored in the device's
   * caregiverUids list when the pairing link from the phone was opened. Signing in directly would
   * issue a different uid (or switch to an existing one), losing access to this phone. Linking
   * keeps that same uid and attaches the Google identity to it, so the caregiver connection
   * remains intact while satisfying the owner rule ('google.com' in identities).
   */
  async function handleLinkOwner() {
    if (!auth || !auth.currentUser) return;
    const provider = new firebase.auth.GoogleAuthProvider();
    try {
      await auth.currentUser.linkWithPopup(provider);
      await auth.currentUser.reload(); // providerData refreshed
      await auth.currentUser.getIdToken(true); // token now carries google.com for the rules
      currentUser = auth.currentUser;
      if (currentUser) {
        userEmailSpan.textContent = describeUser(currentUser);
        signoutBtn.textContent = currentUser.isAnonymous ? 'Zugang hier entfernen' : 'Abmelden';
      }
      renderManaged();
    } catch (err) {
      if (err.code === 'auth/credential-already-in-use' && err.credential) {
        const goOn = window.confirm(
          'Dieses Google-Konto ist bereits mit einem anderen Zugang verbunden. Wenn Sie damit ' +
          'fortfahren, wechseln Sie zu diesem Zugang; die Verbindung zu diesem Smartphone über den Link ' +
          'gilt dann für diesen Browser nicht mehr. Fortfahren?'
        );
        if (goOn) {
          try {
            await auth.signInWithCredential(err.credential);
          } catch (signInErr) {
            showStatus('Fehler bei der Google-Anmeldung: ' + parseErrorMessage(signInErr), false);
          }
        }
      } else if (err.code === 'auth/popup-closed-by-user' || err.code === 'auth/cancelled-popup-request') {
        // User closed or canceled popup, nothing to do
      } else {
        showStatus('Fehler bei der Google-Anmeldung: ' + parseErrorMessage(err), false);
      }
    }
  }

  /**
   * Updates policy/current and writes an audit entry in ONE db.batch() [docs/phase1b.md §3b].
   * Always writes both booleans (enforcing, advancedMode).
   */
  async function updatePolicy(newEnforcing, newAdvancedMode, detail) {
    if (!db || !currentDeviceId || !currentUser) return;
    try {
      const batch = db.batch();
      const policyRef = db.collection('devices').doc(currentDeviceId).collection('policy').doc('current');
      const auditRef = db.collection('devices').doc(currentDeviceId).collection('audit').doc();
      const serverTime = firebase.firestore.FieldValue.serverTimestamp();
      batch.set(policyRef, {
        enforcing: !!newEnforcing,
        advancedMode: !!newAdvancedMode,
        updatedAt: serverTime,
        updatedBy: currentUser.uid
      });
      batch.set(auditRef, {
        at: serverTime,
        actor: 'owner',
        uid: currentUser.uid,
        action: 'policy_change',
        detail: detail
      });
      await batch.commit();
      showStatus('Richtlinie gespeichert.');
    } catch (err) {
      showStatus('Fehler beim Speichern der Richtlinie: ' + parseErrorMessage(err), false);
    }
  }

  /**
   * The consent recorded once by an owner (PLAN §8 decision 6): gentle connection's own location
   * report, nothing new. Stored verbatim in policy/consent so it is clear later what was agreed to.
   */
  const LOCATION_CONSENT_TEXT = 'Die Person, die dieses Smartphone benutzt, ist einverstanden, dass gentle connection den Standort des Smartphones an die Betreuenden meldet.';

  /**
   * Updates policy/consent and writes an audit entry in ONE db.batch() [docs/phase1b.md §3b].
   */
  async function updateConsent(locationConsent) {
    if (!db || !currentDeviceId || !currentUser) return;
    try {
      const batch = db.batch();
      const consentRef = db.collection('devices').doc(currentDeviceId).collection('policy').doc('consent');
      const auditRef = db.collection('devices').doc(currentDeviceId).collection('audit').doc();
      const serverTime = firebase.firestore.FieldValue.serverTimestamp();
      const consentText = LOCATION_CONSENT_TEXT;
      batch.set(consentRef, {
        location: !!locationConsent,
        text: consentText,
        updatedAt: serverTime,
        updatedBy: currentUser.uid
      });
      batch.set(auditRef, {
        at: serverTime,
        actor: 'owner',
        uid: currentUser.uid,
        action: 'consent_location',
        detail: locationConsent ? 'Einwilligung Standort erteilt' : 'Einwilligung Standort widerrufen'
      });
      await batch.commit();
      showStatus('Einwilligung gespeichert.');
    } catch (err) {
      showStatus('Fehler beim Speichern der Einwilligung: ' + parseErrorMessage(err), false);
    }
  }

  /**
   * Creates a command and writes an audit entry in ONE db.batch() [docs/phase1b.md §3b].
   */
  async function sendCommand(type, args, detail) {
    if (!db || !currentDeviceId || !currentUser) return;
    try {
      const batch = db.batch();
      const cmdRef = db.collection('devices').doc(currentDeviceId).collection('commands').doc();
      const auditRef = db.collection('devices').doc(currentDeviceId).collection('audit').doc();
      const serverTime = firebase.firestore.FieldValue.serverTimestamp();
      batch.set(cmdRef, {
        type: type,
        args: args || {},
        createdBy: currentUser.uid,
        createdAt: serverTime,
        status: 'pending'
      });
      batch.set(auditRef, {
        at: serverTime,
        actor: 'owner',
        uid: currentUser.uid,
        action: 'command_' + type,
        detail: detail
      });
      await batch.commit();
      showStatus('Befehl gesendet.');
    } catch (err) {
      showStatus('Fehler beim Senden des Befehls: ' + parseErrorMessage(err), false);
    }
  }

  function formatCommandStatus(cmd) {
    switch (cmd.status) {
      case 'pending': return 'ausstehend…';
      case 'done': return 'erledigt';
      case 'rejected': return 'abgelehnt' + (cmd.result ? `: ${escapeHtml(String(cmd.result))}` : '');
      case 'expired': return 'abgelaufen';
      case 'failed': return 'fehlgeschlagen' + (cmd.result ? `: ${escapeHtml(String(cmd.result))}` : '');
      default: return escapeHtml(cmd.status || 'unbekannt');
    }
  }

  function formatCommandType(cmd) {
    if (cmd.type === 'refresh') return 'Aktualisieren';
    if (cmd.type === 'reboot') return 'Neu starten';
    if (cmd.type === 'openApp') {
      const pkg = (cmd.args && cmd.args.package) || '';
      return 'App öffnen' + (pkg ? ` (${pkg})` : '');
    }
    return cmd.type || 'Befehl';
  }

  function formatAuditAction(action) {
    const map = {
      'policy_change': 'Richtlinie geändert',
      'policy_applied': 'Richtlinie angewendet',
      'command_refresh': 'Aktualisierung angefordert',
      'command_openApp': 'App-Start angefordert',
      'command_reboot': 'Neustart angefordert',
      'command_done': 'Befehl ausgeführt',
      'command_rejected': 'Befehl abgelehnt',
      'command_failed': 'Befehl fehlgeschlagen',
      'command_expired': 'Befehl abgelaufen',
      'consent_location': 'Einwilligung Standort'
    };
    return map[action] || action || 'Aktion';
  }

  if (managedContainer) {
    managedContainer.addEventListener('click', (e) => {
      const linkBtn = e.target.closest('#link-owner-btn');
      if (linkBtn) {
        handleLinkOwner();
        return;
      }
      const openAppBtn = e.target.closest('#managed-open-app-btn');
      if (openAppBtn) {
        const select = managedContainer.querySelector('#managed-launchable-app');
        if (select && select.value) {
          const pkg = select.value;
          const opt = select.options[select.selectedIndex];
          const label = opt ? opt.textContent : pkg;
          sendCommand('openApp', { package: pkg }, 'App öffnen: ' + label);
        }
        return;
      }
      const refreshBtn = e.target.closest('#managed-refresh-btn');
      if (refreshBtn) {
        sendCommand('refresh', {}, 'Bericht aktualisieren angefordert');
        return;
      }
      const rebootBtn = e.target.closest('#managed-reboot-btn');
      if (rebootBtn) {
        if (window.confirm('Das Telefon startet neu. Fortfahren?')) {
          sendCommand('reboot', {}, 'Neustart angefordert');
        }
        return;
      }
    });

    managedContainer.addEventListener('change', (e) => {
      if (e.target.id === 'managed-enforcing-toggle') {
        const checked = e.target.checked;
        const currentAdv = (policyCurrent && typeof policyCurrent.advancedMode === 'boolean')
          ? policyCurrent.advancedMode
          : (managedState && typeof managedState.advancedMode === 'boolean' ? managedState.advancedMode : false);
        updatePolicy(checked, currentAdv, checked ? 'Durchsetzen aktiviert' : 'Durchsetzen deaktiviert');
      } else if (e.target.id === 'managed-advanced-toggle') {
        const checked = e.target.checked;
        const currentEnf = (policyCurrent && typeof policyCurrent.enforcing === 'boolean')
          ? policyCurrent.enforcing
          : (managedState && typeof managedState.enforcing === 'boolean' ? managedState.enforcing : false);
        updatePolicy(currentEnf, checked, checked ? 'Erweiterter Modus aktiviert' : 'Erweiterter Modus deaktiviert');
      } else if (e.target.id === 'managed-consent-toggle') {
        const checked = e.target.checked;
        updateConsent(checked);
      } else if (e.target.id === 'managed-launchable-app') {
        selectedLaunchablePackage = e.target.value;
      }
    });
  }

  function renderManaged() {
    if (!managedCard) return;
    const NETWORK_NAMES = { WIFI: 'WLAN', CELLULAR: 'Mobilfunk', ETHERNET: 'Kabel', NONE: 'kein Netz' };
    const COMPLIANCE_WORDS = {
      DRIFTED: 'weicht ab',
      FAILED: 'liess sich nicht setzen',
      UNSUPPORTED: 'auf diesem Gerät nicht möglich',
      CORRECTED: 'wurde korrigiert'
    };
    const s = managedState;
    if (!currentDeviceId || !s) {
      managedCard.style.display = 'none';
      return;
    }
    managedCard.style.display = 'block';

    const ownerUids = (accessOwners && Array.isArray(accessOwners.uids)) ? accessOwners.uids : [];
    const googleProvider = getGoogleProviderData(currentUser);
    const hasGoogle = !!googleProvider;
    const isOwnerHere = !!currentUser && hasGoogle && ownerUids.includes(currentUser.uid);
    const googleEmail = (googleProvider && googleProvider.email) || (currentUser && currentUser.email) || '';

    let ownerHtml = '';
    if (isOwnerHere) {
      ownerHtml = `
        <div class="owner-status-box" style="margin-bottom: 1rem; display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap;">
          <span class="owner-badge">Verwalter</span>
          <span style="color: var(--text-muted); font-size: 0.95rem;">${escapeHtml(googleEmail)}</span>
        </div>
      `;
    } else if (!hasGoogle) {
      ownerHtml = `
        <div class="owner-status-box" style="margin-bottom: 1rem;">
          <button type="button" id="link-owner-btn" class="btn btn-secondary" style="font-size: 0.9rem; padding: 0.4rem 0.8rem;">
            Als Verwalter anmelden
          </button>
          <p class="help-text" style="margin-top: 0.4rem; margin-bottom: 0;">
            Nur für die Person, die dieses Smartphone verwaltet. Sie bleiben dabei mit diesem Smartphone verbunden.
          </p>
        </div>
      `;
    } else {
      ownerHtml = `
        <div class="owner-status-box" style="margin-bottom: 1rem;">
          <p class="help-text" style="margin: 0;">
            Mit Google angemeldet (${escapeHtml(googleEmail)}), aber nicht als Verwalter eingetragen.
          </p>
        </div>
      `;
    }

    let nonSamsungWarningHtml = '';
    if (s.manufacturer && String(s.manufacturer).trim().toLowerCase() !== 'samsung') {
      nonSamsungWarningHtml = `
        <div class="banner banner-warning" style="margin-bottom: 1rem; padding: 0.6rem 0.9rem;">
          ⚠️ <strong>Nur auf Samsung getestet</strong> (Hersteller laut Gerät: ${escapeHtml(String(s.manufacturer))})
        </div>
      `;
    }

    const now = Date.now();
    const rows = [];
    const reportedAt = millisOf(s.updatedAt) || millisOf(s.collected_at_ms);
    if (reportedAt !== null) {
      const age = Math.max(0, now - reportedAt);
      // The management app reports at least every six hours while the phone is on.
      const stale = age > 24 * 60 * 60 * 1000;
      rows.push(['Bericht', (stale ? '⚠️ ' : '') + agoText(age)]);
    }
    rows.push(['Verwaltung', s.enforcing
      ? 'aktiv — die Einstellungen werden durchgesetzt'
      : 'nur beobachtend — es wird nichts verändert']);
    if (typeof s.battery_percent === 'number') {
      rows.push(['Akku', s.battery_percent + ' %' + (s.charging ? ' (lädt)' : '')]);
    }
    if (typeof s.storage_free_bytes === 'number' && typeof s.storage_total_bytes === 'number' && s.storage_total_bytes > 0) {
      rows.push(['Speicher', gigabytes(s.storage_free_bytes) + ' von ' + gigabytes(s.storage_total_bytes) + ' frei']);
    }
    if (s.network_type) {
      rows.push(['Netz', (NETWORK_NAMES[String(s.network_type).toUpperCase()] || String(s.network_type)) + (s.roaming ? ' (Roaming)' : '')]);
    }
    if (s.security_patch) {
      rows.push(['Sicherheitsstand', String(s.security_patch)]);
    }
    // What the watchdog found when it asked gentle connection itself; older reports lack it.
    const LAUNCHER_WORDS = {
      ok: 'läuft',
      not_installed: '⚠️ nicht installiert',
      untrusted: '⚠️ installiert, aber nicht die echte App',
      too_old: 'läuft, Version zu alt für eine Auskunft',
      unreachable: '⚠️ antwortet nicht',
      messenger_down: '⚠️ Nachrichtendienst läuft nicht',
      beat_stale: '⚠️ erreicht den Server nicht',
      not_home: '⚠️ läuft, ist aber nicht der Startbildschirm'
    };
    if (s.launcher_verdict) {
      rows.push(['gentle connection', LAUNCHER_WORDS[s.launcher_verdict] || String(s.launcher_verdict)]);
    } else {
      rows.push(['gentle connection', !s.target_installed
        ? '⚠️ nicht installiert'
        : (s.target_is_home ? 'installiert, ist der Startbildschirm' : '⚠️ installiert, aber nicht der Startbildschirm')]);
    }

    let reportTableHtml = '<table class="managed-table">' + rows.map(([k, v]) =>
      `<tr><th>${escapeHtml(k)}</th><td>${escapeHtml(v)}</td></tr>`).join('') + '</table>';

    const problems = (managedCompliance && Array.isArray(managedCompliance.problems))
      ? managedCompliance.problems.filter((p) => p && p.status !== 'CORRECTED')
      : [];
    let complianceHtml = '';
    if (managedCompliance) {
      complianceHtml = problems.length === 0
        ? '<p class="help-text" style="margin-top: 0.75rem;">Alle Einstellungen stimmen.</p>'
        : '<p class="help-text" style="margin-top: 0.75rem;">Abweichungen:</p><ul class="managed-problems">' +
          problems.map((p) =>
            `<li><code>${escapeHtml(p.key)}</code> ${escapeHtml(COMPLIANCE_WORDS[p.status] || p.status)}` +
            (p.detail ? ` — ${escapeHtml(p.detail)}` : '') + '</li>').join('') + '</ul>';
    }

    // Owner controls (only for authenticated owner)
    let ownerControlsHtml = '';
    if (isOwnerHere) {
      const enforcingChecked = (policyCurrent && typeof policyCurrent.enforcing === 'boolean')
        ? policyCurrent.enforcing
        : (s && typeof s.enforcing === 'boolean' ? s.enforcing : false);
      const advancedChecked = (policyCurrent && typeof policyCurrent.advancedMode === 'boolean')
        ? policyCurrent.advancedMode
        : (s && typeof s.advancedMode === 'boolean' ? s.advancedMode : false);

      const stateEnforcing = typeof s.enforcing === 'boolean' ? s.enforcing : null;
      const isPendingSync = (policyCurrent && typeof policyCurrent.enforcing === 'boolean' &&
        stateEnforcing !== null && policyCurrent.enforcing !== stateEnforcing);

      const launchableApps = Array.isArray(s.launchableApps) ? s.launchableApps : [];
      const appsOptions = launchableApps.map((a) => {
        const isSel = selectedLaunchablePackage === a.package ? 'selected' : '';
        return `<option value="${escapeHtml(a.package)}" ${isSel}>${escapeHtml(a.label || a.package)}</option>`;
      }).join('');

      const consentChecked = policyConsent && policyConsent.location === true;

      let commandsListHtml = '';
      if (recentCommands.length > 0) {
        commandsListHtml = `
          <div style="margin-top: 1.25rem; border-top: 1px solid var(--border-color); padding-top: 0.75rem;">
            <strong style="font-size: 0.95rem; color: var(--text-main);">Letzte Befehle</strong>
            <ul style="list-style: none; padding: 0; margin-top: 0.5rem; font-size: 0.85rem;">
              ${recentCommands.map((cmd) => {
                const atMs = millisOf(cmd.createdAt);
                const timeStr = atMs ? new Date(atMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
                return `
                  <li style="display: flex; justify-content: space-between; padding: 0.25rem 0; border-bottom: 1px dotted var(--border-color); gap: 0.5rem;">
                    <span>${timeStr ? `<strong>${escapeHtml(timeStr)}</strong> ` : ''}${escapeHtml(formatCommandType(cmd))}</span>
                    <span style="color: var(--text-muted);">${formatCommandStatus(cmd)}</span>
                  </li>
                `;
              }).join('')}
            </ul>
          </div>
        `;
      }

      ownerControlsHtml = `
        <div class="managed-owner-controls" style="margin-top: 1.5rem; border-top: 1px solid var(--border-color); padding-top: 1rem;">
          <h3 style="font-size: 1.05rem; color: var(--text-main); margin-bottom: 0.75rem;">Steuerung</h3>

          <div class="toggle-group" style="padding-top: 0.25rem;">
            <div class="toggle-label">
              <span>
                Durchsetzen
                ${isPendingSync ? '<span style="color: var(--accent-color); font-size: 0.85rem; font-weight: normal; margin-left: 0.5rem;">wird übernommen …</span>' : ''}
              </span>
              <small>Einstellungen auf dem Smartphone erzwingen</small>
            </div>
            <label class="switch">
              <input type="checkbox" id="managed-enforcing-toggle" ${enforcingChecked ? 'checked' : ''}>
              <span class="slider"></span>
            </label>
          </div>

          <div class="toggle-group">
            <div class="toggle-label">
              <span>Erweiterter Modus</span>
              <small>Andere Apps auf dem Smartphone erlauben. Wird gespeichert, wirkt aber erst mit dem geschützten Modus.</small>
            </div>
            <label class="switch">
              <input type="checkbox" id="managed-advanced-toggle" ${advancedChecked ? 'checked' : ''}>
              <span class="slider"></span>
            </label>
          </div>

          <div class="form-group" style="margin-top: 1rem; margin-bottom: 0;">
            <label for="managed-launchable-app" style="font-size: 0.95rem;">App öffnen</label>
            ${launchableApps.length > 0 ? `
              <div style="display: flex; gap: 0.5rem; flex-wrap: wrap;">
                <select id="managed-launchable-app" style="flex: 1 1 200px; min-width: 0;">
                  ${appsOptions}
                </select>
                <button type="button" id="managed-open-app-btn" class="btn btn-secondary" style="font-size: 0.9rem; padding: 0.5rem 1rem;">
                  App öffnen
                </button>
              </div>
            ` : `
              <p class="help-text" style="margin: 0;">Keine startbaren Apps gemeldet.</p>
            `}
          </div>

          <div style="display: flex; gap: 0.75rem; margin-top: 1.25rem; flex-wrap: wrap;">
            <button type="button" id="managed-refresh-btn" class="btn btn-secondary" style="font-size: 0.9rem; padding: 0.5rem 1rem;">
              Aktualisieren
            </button>
            <button type="button" id="managed-reboot-btn" class="btn btn-danger" style="font-size: 0.9rem; padding: 0.5rem 1rem;">
              Neu starten
            </button>
          </div>

          <div class="toggle-group" style="margin-top: 1.25rem; padding-top: 0.75rem; border-top: 1px solid var(--border-color);">
            <div class="toggle-label">
              <span>Einwilligung Standort</span>
              <small>${escapeHtml(LOCATION_CONSENT_TEXT)}</small>
            </div>
            <label class="switch">
              <input type="checkbox" id="managed-consent-toggle" ${consentChecked ? 'checked' : ''}>
              <span class="slider"></span>
            </label>
          </div>

          ${commandsListHtml}
        </div>
      `;
    }

    // Protokoll (audit log): visible to EVERY caregiver (owners and non-owners)
    let auditHtml = '';
    if (recentAudits.length > 0) {
      auditHtml = `
        <div style="margin-top: 1.5rem; border-top: 1px solid var(--border-color); padding-top: 0.75rem;">
          <strong style="font-size: 0.95rem; color: var(--text-main);">Protokoll</strong>
          <div style="overflow-x: auto; margin-top: 0.5rem;">
            <table class="managed-table audit-table" style="font-size: 0.85rem;">
              <thead>
                <tr style="border-bottom: 1px solid var(--border-color); text-align: left; color: var(--text-muted);">
                  <th style="padding: 0.25rem 0.5rem 0.25rem 0;">Zeit</th>
                  <th style="padding: 0.25rem 0.5rem;">Wer</th>
                  <th style="padding: 0.25rem 0.5rem;">Aktion</th>
                  <th style="padding: 0.25rem 0;">Detail</th>
                </tr>
              </thead>
              <tbody>
                ${recentAudits.map((entry) => {
                  const atMs = millisOf(entry.at);
                  const dateStr = atMs ? new Date(atMs).toLocaleDateString([], { day: '2-digit', month: '2-digit' }) : '';
                  const timeStr = atMs ? new Date(atMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
                  const who = entry.actor === 'owner' ? 'Verwalter' : (entry.actor === 'dpc' ? 'Gerät' : (entry.actor || ''));
                  return `
                    <tr style="border-bottom: 1px dotted var(--border-color);">
                      <td style="white-space: nowrap; padding: 0.3rem 0.5rem 0.3rem 0; color: var(--text-muted);">${escapeHtml(dateStr)} ${escapeHtml(timeStr)}</td>
                      <td style="white-space: nowrap; padding: 0.3rem 0.5rem;"><span style="background: rgba(0,0,0,0.06); padding: 0.1rem 0.35rem; border-radius: 4px;">${escapeHtml(who)}</span></td>
                      <td style="white-space: nowrap; padding: 0.3rem 0.5rem; font-weight: 500;">${escapeHtml(formatAuditAction(entry.action))}</td>
                      <td style="padding: 0.3rem 0; color: var(--text-muted);">${escapeHtml(entry.detail || '')}</td>
                    </tr>
                  `;
                }).join('')}
              </tbody>
            </table>
          </div>
        </div>
      `;
    } else {
      auditHtml = `
        <div style="margin-top: 1.5rem; border-top: 1px solid var(--border-color); padding-top: 0.75rem;">
          <strong style="font-size: 0.95rem; color: var(--text-main);">Protokoll</strong>
          <p class="help-text" style="margin-top: 0.25rem;">Noch keine Protokolleinträge vorhanden.</p>
        </div>
      `;
    }

    managedContainer.innerHTML = nonSamsungWarningHtml + ownerHtml + reportTableHtml + complianceHtml + ownerControlsHtml + auditHtml;
  }

  /* ---------------------------------------------------------------------------------------
   * Support Group (Hilfe-Knopf) and Support Requests
   * --------------------------------------------------------------------------------------- */

  /**
   * The support group, picked from her favourites.
   *
   * Only who is stored — the contact id and a name to show here. The phone fills in number,
   * photograph, Threema and WhatsApp from its own address book (SupportGroupResolver), so nothing
   * is typed twice and nothing goes stale. No upper limit: everyone ticked is told.
   *
   * Members typed in by hand in the first version keep working on the phone. They are ticked here
   * when their name matches a favourite, and listed as such otherwise until the next save.
   */
  let supportSelectedIds = new Set();
  let supportLegacyNames = [];
  // Whose face is on the button. Saved as the first member, which is what the phone shows.
  let supportFaceId = null;

  function loadSupportSelection(group) {
    supportSelectedIds = new Set();
    supportLegacyNames = [];
    supportFaceId = null;
    (group || []).forEach((m) => {
      let id = m.contactId ? String(m.contactId) : null;
      if (!id) {
        const name = (m.name || '').trim().toLowerCase();
        const match = starredContacts.find((c) => (c.name || '').trim().toLowerCase() === name && name !== '');
        if (match) id = String(match.id);
        else if (m.name) supportLegacyNames.push(m.name);
      }
      if (id) {
        supportSelectedIds.add(id);
        if (supportFaceId === null) supportFaceId = id;
      }
    });
    supportMembers = collectSupportMembers();
  }

  function renderSupportMembers() {
    if (!supportMembersContainer) return;
    supportMembersContainer.innerHTML = '';

    if (!starredContacts || starredContacts.length === 0) {
      supportMembersContainer.innerHTML = '<p style="color: var(--text-muted); font-style: italic;">' +
        'Auf dem Smartphone ist noch niemand mit einem Stern markiert. Die Unterstützungsgruppe wird ' +
        'aus den Favoriten ausgewählt.</p>';
      return;
    }

    const ordered = arrangeFavourites(starredContacts, getFavouriteOrder());
    const faceId = currentFaceId(ordered);
    ordered.forEach((contact) => {
      const id = String(contact.id);
      const checked = supportSelectedIds.has(id);
      const isFace = checked && id === faceId;

      const row = document.createElement('div');
      row.className = 'contact-card support-member-card';
      row.style.display = 'flex';
      row.style.alignItems = 'center';
      row.style.justifyContent = 'space-between';
      row.style.gap = '0.75rem';
      row.innerHTML = `
        <label style="display: flex; align-items: center; gap: 0.75rem; cursor: pointer; flex: 1;">
          <input type="checkbox" class="sm-pick" ${checked ? 'checked' : ''} style="width: 1.2rem; height: 1.2rem;">
          <strong>${escapeHtml(contact.name || '(ohne Namen)')}</strong>
        </label>
        ${checked ? `
        <label style="display: flex; align-items: center; gap: 0.4rem; cursor: pointer; font-size: 0.85rem; color: ${isFace ? '#1F5FA8' : 'var(--text-muted)'}; font-weight: ${isFace ? '600' : '400'};">
          <input type="radio" name="support-face" class="sm-face" ${isFace ? 'checked' : ''}>
          Auf dem Hilfe-Knopf
        </label>` : ''}
      `;
      row.querySelector('.sm-pick').addEventListener('change', (e) => {
        if (e.target.checked) supportSelectedIds.add(id);
        else supportSelectedIds.delete(id);
        supportMembers = collectSupportMembers();
        renderSupportMembers();
        saveSupportGroup();
      });
      row.querySelector('.sm-face')?.addEventListener('change', (e) => {
        if (!e.target.checked) return;
        supportFaceId = id;
        supportMembers = collectSupportMembers();
        renderSupportMembers();
        saveSupportGroup();
      });
      supportMembersContainer.appendChild(row);
    });

    if (supportLegacyNames.length > 0) {
      const note = document.createElement('p');
      note.className = 'help-text';
      note.style.marginTop = '0.5rem';
      note.textContent = 'Von Hand eingetragen und nicht unter den Favoriten: ' +
        supportLegacyNames.join(', ') + '. Beim Speichern werden sie entfernt.';
      supportMembersContainer.appendChild(note);
    }
  }

  /**
   * Saves who is in the group the moment a box is ticked.
   *
   * It used to wait for the save button at the very bottom of this page. On 2026-09-17 a fourth
   * person was ticked, the page was left, and the help button went on telling three: a tick that
   * looks done but is not is the one mistake this list must not allow. Only the group is written
   * here, so nothing else half-edited on the page goes to the phone with it.
   */
  async function saveSupportGroup() {
    if (!db || !currentDeviceId) return;
    const group = collectSupportMembers();
    await saveFields({ supportGroup: group });
  }

  /** The chosen face while it is still ticked, otherwise the first ticked person. */
  function currentFaceId(ordered) {
    if (supportFaceId !== null && supportSelectedIds.has(supportFaceId)) return supportFaceId;
    const first = ordered.find((c) => supportSelectedIds.has(String(c.id)));
    return first ? String(first.id) : null;
  }

  function collectSupportMembers() {
    const ordered = arrangeFavourites(starredContacts, contactsContainer ? getFavouriteOrder() : []);
    const selected = ordered.filter((c) => supportSelectedIds.has(String(c.id)));
    const faceId = currentFaceId(ordered);
    // The face first: the phone shows the first member on the button.
    selected.sort((a, b) => (String(b.id) === faceId) - (String(a.id) === faceId));
    return selected
      .map((c) => ({
        id: 'contact-' + c.id,
        contactId: String(c.id),
        name: c.name || ''
      }));
  }

  function listenToSupportRequests(deviceId) {
    if (unsubscribeSupport) {
      unsubscribeSupport();
      unsubscribeSupport = null;
    }
    currentSupportRequests = [];

    if (!db || !deviceId) return;

    unsubscribeSupport = db.collection('devices').doc(deviceId)
      .collection('supportRequests')
      .orderBy('createdAt', 'desc')
      .limit(5)
      .onSnapshot((snapshot) => {
        const requests = [];
        snapshot.forEach((doc) => {
          requests.push(Object.assign({ id: doc.id }, doc.data()));
        });
        currentSupportRequests = requests;
        renderSupportRequests();
      }, (err) => {
        console.warn('Support requests listener error:', err);
        currentSupportRequests = [];
        renderSupportRequests();
      });
  }

  function renderSupportRequests() {
    if (!supportRequestsCard || !supportRequestsContainer) return;

    if (!currentDeviceId || currentSupportRequests.length === 0) {
      supportRequestsCard.style.display = 'none';
      return;
    }

    const now = Date.now();
    const activeRequests = currentSupportRequests.filter((r) => {
      if (r.status === 'open') return true;
      if (r.status === 'claimed') {
        const created = typeof r.createdAt === 'number' ? r.createdAt : 0;
        return (now - created) < 2 * 60 * 60 * 1000;
      }
      return false;
    });

    if (activeRequests.length === 0) {
      supportRequestsCard.style.display = 'none';
      return;
    }

    supportRequestsCard.style.display = 'block';

    const hasOpen = activeRequests.some((r) => r.status === 'open');
    if (supportRequestBadge) {
      if (hasOpen) {
        supportRequestBadge.textContent = 'Offen';
        supportRequestBadge.style.background = 'var(--danger-color)';
      } else {
        supportRequestBadge.textContent = 'In Bearbeitung';
        supportRequestBadge.style.background = 'var(--primary-color)';
      }
    }

    const personName = (currentSettings && currentSettings.supportPersonName) || 'Mama';

    supportRequestsContainer.innerHTML = '';
    activeRequests.forEach((req) => {
      const created = typeof req.createdAt === 'number' ? req.createdAt : now;
      const timeStr = new Date(created).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      const ageStr = agoText(Math.max(0, now - created));

      const row = document.createElement('div');
      row.className = 'support-request-item';
      row.style.marginBottom = '1.25rem';
      row.style.paddingBottom = '1.25rem';
      row.style.borderBottom = '1px solid var(--border-color)';

      let actionHtml = '';
      if (req.status === 'open') {
        actionHtml = `
          <div style="margin: 0.75rem 0; padding: 0.75rem 1rem; background: rgba(198, 40, 40, 0.08); border: 1px solid var(--danger-color); border-radius: var(--radius-md); display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 0.75rem;">
            <div>
              <strong style="color: var(--danger-color); font-size: 1.05rem;">⚠️ Offene Anfrage — Noch niemand kümmert sich!</strong>
              <div class="help-text" style="margin-top: 0.2rem;">Klicken Sie auf den Button, um der Gruppe und dem Smartphone mitzuteilen, dass Sie sich darum kümmern.</div>
            </div>
            <button type="button" class="btn btn-primary claim-btn" data-id="${escapeHtml(req.id)}" style="background-color: var(--danger-color);">
              Ich kümmere mich
            </button>
          </div>
        `;
      } else if (req.status === 'claimed') {
        const claimedAt = typeof req.claimedAt === 'number' ? req.claimedAt : created;
        const claimedAge = agoText(Math.max(0, now - claimedAt));
        const via = req.claimedVia ? ` (via ${escapeHtml(req.claimedVia)})` : '';
        actionHtml = `
          <div style="margin: 0.75rem 0; padding: 0.75rem 1rem; background: rgba(45, 90, 39, 0.08); border: 1px solid var(--primary-color); border-radius: var(--radius-md); display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 0.75rem;">
            <div>
              <strong style="color: var(--primary-color);">✅ In Bearbeitung: ${escapeHtml(req.claimedBy || 'Jemand')} kümmert sich</strong>
              <div class="help-text" style="margin-top: 0.2rem;">Übernommen vor ${claimedAge}${via}. Anrufe von Gruppenmitgliedern läuten 2 Stunden lang durch Ruhezeiten und DND.</div>
            </div>
            <button type="button" class="btn btn-secondary close-btn" data-id="${escapeHtml(req.id)}" style="font-size: 0.85rem; padding: 0.35rem 0.75rem;">
              Als erledigt markieren
            </button>
          </div>
        `;
      }

      let locationHtml = '';
      if (req.location && typeof req.location.lat === 'number' && typeof req.location.lon === 'number') {
        const lat = req.location.lat;
        const lon = req.location.lon;
        const acc = req.location.accuracyMetres ? `±${req.location.accuracyMetres} m` : '';
        const locAge = req.location.at ? agoText(Math.max(0, now - req.location.at)) : '';
        const href = mapUrl(lat, lon, req.location.accuracyMetres);
        locationHtml = `
          <p style="margin-top: 0.5rem; font-size: 0.95rem;">
            📍 <strong>Standort:</strong> <a href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer">${lat.toFixed(5)}, ${lon.toFixed(5)}</a>
            ${acc ? `(${acc}${locAge ? ', ' + locAge : ''})` : ''}
            · <a href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer">Auf der Karte anzeigen</a>
          </p>
        `;
      }

      let phoneHtml = '';
      if (req.phoneStatus) {
        const ps = req.phoneStatus;
        const parts = [];
        if (typeof ps.battery === 'number') {
          parts.push(`🔋 Akku ${ps.battery}%${ps.charging ? ' (lädt)' : ''}`);
        }
        if (ps.network) {
          parts.push(`📶 ${escapeHtml(ps.network)}${ps.roaming ? ' (Roaming)' : ''}`);
        }
        // The phone sends these as words ("verbunden"/"getrennt"). They used to be read here as
        // booleans, so the two lines that say whether her messengers were up at the moment she
        // asked for help were never shown at all — and this page is now the only place they are
        // said, because they have been taken out of the message the family receives.
        const threema = connectionWord(ps.threema);
        if (threema) parts.push(`Threema: ${threema}`);
        const whatsapp = connectionWord(ps.whatsapp);
        if (whatsapp) parts.push(`WhatsApp: ${whatsapp}`);
        if (ps.appVersion) parts.push(`App ${escapeHtml(String(ps.appVersion))}`);

        let faultsHtml = '';
        if (Array.isArray(ps.faults) && ps.faults.length > 0) {
          const named = ps.faults.map(faultLabel).join(', ');
          const n = ps.faults.length;
          faultsHtml = `<p style="margin-top: 0.3rem; font-size: 0.85rem; color: var(--text-muted);">` +
            `⚠️ ${n} ${n === 1 ? 'Störung' : 'Störungen'} an diesem Tag: ${escapeHtml(named)}</p>`;
        }
        phoneHtml = `<p style="margin-top: 0.3rem; font-size: 0.85rem; color: var(--text-muted);">${parts.join(' · ')}</p>` + faultsHtml;
      }

      let deliveriesHtml = '';
      if (Array.isArray(req.deliveries) && req.deliveries.length > 0) {
        const items = req.deliveries.map((d) => {
          const memberName = (supportMembers.find((m) => m.id === d.memberId)?.name) || d.memberId || 'Mitglied';
          return `<li>${escapeHtml(memberName)} (${escapeHtml(d.channel)}): <strong>${escapeHtml(d.state)}</strong></li>`;
        }).join('');
        deliveriesHtml = `
          <div style="margin-top: 0.5rem; font-size: 0.85rem; color: var(--text-muted);">
            <strong>Zustellung:</strong>
            <ul style="margin-left: 1.25rem; margin-top: 0.2rem;">${items}</ul>
          </div>
        `;
      }

      let repeatsHtml = '';
      if (Array.isArray(req.repeats) && req.repeats.length > 0) {
        repeatsHtml = `<p style="margin-top: 0.3rem; font-size: 0.85rem; color: var(--text-muted);">🔁 ${req.repeats.length}× erneut Bescheid gegeben.</p>`;
      }

      row.innerHTML = `
        <div style="font-size: 1rem; color: var(--text-main);">
          Um <strong>${timeStr} Uhr</strong> (${ageStr}) hat <strong>${escapeHtml(personName)}</strong> auf den Hilfe-Knopf gedrückt (Bildschirm: <em>${escapeHtml(req.screen || 'Startbildschirm')}</em>).
        </div>
        ${actionHtml}
        ${locationHtml}
        ${phoneHtml}
        ${deliveriesHtml}
        ${repeatsHtml}
      `;

      row.querySelector('.claim-btn')?.addEventListener('click', () => claimSupportRequest(req.id));
      row.querySelector('.close-btn')?.addEventListener('click', () => closeSupportRequest(req.id));

      supportRequestsContainer.appendChild(row);

      // Browser Notification for open requests
      if (req.status === 'open' && !notifiedSupportRequestIds.has(req.id)) {
        notifiedSupportRequestIds.add(req.id);
        if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
          try {
            new Notification('gentle connection — 🆘 Hilferuf!', {
              body: `${personName} hat auf den Hilfe-Knopf gedrückt! Bitte im Portal nachsehen oder anrufen.`,
              tag: 'support_req_' + req.id,
              requireInteraction: true
            });
          } catch (_) {}
        }
      }
    });
  }

  async function claimSupportRequest(requestId) {
    if (!db || !currentDeviceId || !requestId) return;
    const members = supportMembers || [];
    let claimerName = '';
    if (members.length === 1) {
      claimerName = members[0].name;
    } else if (members.length > 1) {
      const names = members.map((m) => m.name).filter(Boolean).join(', ');
      claimerName = window.prompt(`Wer übernimmt die Anfrage? (${names}):`, members[0].name);
    }
    if (!claimerName) {
      claimerName = currentUser ? (currentUser.displayName || currentUser.email || 'Betreuung') : 'Betreuung';
    }

    try {
      await db.collection('devices').doc(currentDeviceId)
        .collection('supportRequests').doc(requestId)
        .update({
          status: 'claimed',
          claimedBy: claimerName,
          claimedVia: 'portal',
          claimedAt: Date.now()
        });
      showStatus(`Anfrage übernommen von ${claimerName}!`);
    } catch (err) {
      showStatus('Fehler beim Übernehmen der Anfrage: ' + parseErrorMessage(err), false);
    }
  }

  async function closeSupportRequest(requestId) {
    if (!db || !currentDeviceId || !requestId) return;
    const confirm = window.confirm('Möchten Sie diese Hilfe-Anfrage wirklich als erledigt abschliessen?');
    if (!confirm) return;
    try {
      await db.collection('devices').doc(currentDeviceId)
        .collection('supportRequests').doc(requestId)
        .update({
          status: 'closed'
        });
      showStatus('Anfrage als erledigt markiert.');
    } catch (err) {
      showStatus('Fehler beim Schliessen der Anfrage: ' + parseErrorMessage(err), false);
    }
  }

  document.addEventListener('DOMContentLoaded', initFirebase);
})();

/**
 * An hour of the clock, or the given default.
 *
 * Anything that is not a whole hour between 0 and 23 falls back, which matches what Arrivals.kt
 * does on the phone. The two have to agree: a page showing "von 25:00" while the phone quietly
 * used 21:00 would be a caregiver certain she had changed something that had not changed.
 */
function hourOrDefault(value, fallback) {
  const hour = parseInt(value, 10);
  return Number.isInteger(hour) && hour >= 0 && hour <= 23 ? hour : fallback;
}
