package ch.heuscher.gentleconnect.model

/**
 * Settings synchronized remotely by a caregiver.
 */
data class RemoteSettings(
    val pairingCode: String = "",
    val isPaired: Boolean = false,
    val textSizeMultiplier: Float = 1.0f,
    val photoSizeMultiplier: Float = 1.0f,
    /**
     * How fast the read-aloud voice speaks, as a multiple of the engine's own speed.
     *
     * Here rather than in [Speech] alone because the right speed is a fact about one particular
     * person's hearing, and the person who knows it is not holding the phone. See [Speech.sane]
     * for what happens to a value this document should never have contained.
     */
    val speechRate: Float = Speech.DEFAULT_RATE,
    val isDarkMode: Boolean = false,
    val enableMessages: Boolean = true,
    val enableMail: Boolean = true,
    /**
     * Whether her Post screen leaves out letters from anybody she does not know.
     *
     * **Off unless somebody turns it on**: by default every letter in her inbox is shown, strangers
     * drawn quietly (see [MailPresentation]), because a letter that never appears is the one
     * failure she cannot notice. Spam never reaches the list either way — only the Gmail inbox is
     * read, and Gmail keeps spam out of it. Turning this on is a judgement that the noise costs her
     * more than the surgery's letter she would miss, and that judgement belongs to whoever looks
     * after her.
     */
    val mailOnlyKnownSenders: Boolean = false,
    val enableNudge: Boolean = true,
    /**
     * Whether her home screen shows what is coming in her calendar.
     *
     * On unless somebody turns it off, like the post and the messages, because the block says
     * nothing at all on a phone that has no calendar set up — see
     * [ch.heuscher.gentleconnect.data.CalendarRepository]. What the switch is for is the phone
     * that *does* have one and should not be showing it: a shared work calendar nobody thought to
     * untick, or a week where the appointments are a distress rather than a help.
     */
    val enableCalendar: Boolean = true,
    /**
     * What this phone is called on the caregiver website.
     *
     * For the caregivers, not for her — her phone never says its own name. It exists because the
     * only thing distinguishing one phone from another there was a twelve-character hexadecimal
     * id, which answers "which phone is this?" with a question. Empty means nobody has named it,
     * and the website falls back to the model the phone reports for itself.
     */
    val deviceName: String = "",
    /**
     * Whether her home screen shows the contactless payment button.
     *
     * Off by default. A caregiver turns it on when she is ready to use phone payments with PIN.
     */
    val enableWallet: Boolean = false,
    /**
     * Whether the green "card is ready to pay" notice appears under the clock while the card is lit.
     *
     * On unless somebody turns it off. The lit card says the same thing in colour; the notice
     * adds the words and the hand movement, which help most people and crowd the screen for some.
     */
    val showPaymentReadyNotice: Boolean = true,
    /**
     * How she is asked to unlock the card. See [PaymentAuthMethod]; empty is the phone's own
     * confirm screen, and anything unknown is read as that.
     */
    val paymentAuthMethod: String = PaymentAuthMethod.SYSTEM,
    /**
     * How long a missed call keeps the card at the top of her home screen, in hours.
     *
     * A day for everybody until now, written into the repository that reads the call log. That
     * suits a phone somebody checks a few times a day and suits nobody else: a card still saying
     * "missed call from your daughter" at nine in the evening, about a call at nine in the
     * morning she has since had a whole conversation about on another phone, is the loudest thing
     * on the screen and no longer true. Someone who picks the phone up hourly wants an hour.
     * Someone who might not look at it until tomorrow wants [MissedCallNotice.FOREVER], and the
     * card then waits.
     *
     * [MissedCallNotice.NEVER] switches the card off without switching off [enableNudge]: a phone
     * where messages should interrupt her and missed calls should not, which is the shape of it
     * when the calls she misses are mostly nuisance ones.
     *
     * Only the card is affected. The call is in her list of calls either way — see
     * [MissedCallNotice], which owns the rule and the values this may hold.
     */
    val missedCallHours: Int = MissedCallNotice.DEFAULT_HOURS,
    /**
     * End calls from anybody not in her address book, without letting the phone ring.
     *
     * **Off unless somebody deliberately turns it on**, and it should stay off for most phones.
     * What it stops is the caller who talks her through moving money; what it also stops is the
     * surgery ringing back from a line nobody has saved, a locum, a hospital switchboard, and the
     * plumber she arranged to come on Tuesday. That trade is a judgement about one particular
     * person's life and belongs to whoever is looking after her, which is why it is a switch on
     * the website and not a default.
     *
     * Its effect is deliberately blunt: the call is ended, not silenced. A silenced call still
     * sits in her list of calls as a missed one, and the whole point is that she is never in a
     * position to ring the number back. See
     * [ch.heuscher.gentleconnect.telecom.UnknownCallers], and note that every ended call is
     * written to the caregiver's log so that "nobody ever answers there" has an explanation.
     */
    val blockUnknownCallers: Boolean = false,
    /**
     * Whether calls ended because the number was not in the address book should be shown in her
     * list of recent calls.
     *
     * **Off unless deliberately turned on.** Keeping strangers off her list is the whole point of
     * [blockUnknownCallers]; turning this on lets somebody looking through her calls see that a
     * stranger tried, at the cost of putting the number back within one press of ringing back.
     * Listed as blocked rather than missed, and only for numbers not in her address book, so the
     * calls she declined herself stay off the list.
     */
    val showBlockedCallsInCallLog: Boolean = false,
    /**
     * Whether a notification should be posted when a call is ended because the caller is not in her
     * address book.
     *
     * **Off unless deliberately turned on.** A quiet notice with no call-back button, kept apart
     * from the missed-call notice so it can never replace or clear one.
     */
    val notifyBlockedCalls: Boolean = false,
    /**
     * The hours between which the phone stays dark and silent when somebody writes.
     *
     * On the website because a bedtime is a fact about one particular person's life, and 21:00 to
     * 07:00 is only a guess at it. Somebody who reads until midnight loses three hours of her
     * family every evening to that guess; somebody who is asleep by eight is woken by it.
     *
     * Only the *noise* is affected. The message still arrives, and the card at the top of her
     * home screen is still there in the morning — see [ch.heuscher.gentleconnect.model.Arrivals],
     * which also owns what happens when these two are equal or out of range.
     */
    val quietFromHour: Int = Arrivals.QUIET_FROM_HOUR,
    /** The morning end of the quiet hours. See [quietFromHour]. */
    val quietUntilHour: Int = Arrivals.QUIET_UNTIL_HOUR,
    /**
     * Whether she gets a search box on her messages and on the View screen.
     *
     * **Off unless somebody turns it on**, which is the opposite of most switches here and is the
     * point of it. A search box is a text field: pressing it raises the keyboard, the list she was
     * reading vanishes behind it, and getting back is not obvious. For a phone with a few dozen
     * messages that is a cost with nothing bought.
     *
     * What buys it is scale. Some of these conversations run to thousands of messages, and
     * "the address my daughter sent me in the spring" is then not reachable by scrolling at all.
     * Whether that is her situation is a judgement about one particular person, so it belongs to
     * whoever looks after her. See [ch.heuscher.gentleconnect.model.Searching].
     */
    val enableSearch: Boolean = false,
    /**
     * Whether the last of the five faces under a message opens the rest of them.
     *
     * **Off unless somebody turns it on**, like [enableSearch] and for a related reason: it trades
     * one thing she can do without thinking for a great many she has to choose between. Five faces
     * are pressed straight; a chooser is a second screen, and a second screen is where somebody
     * who has forgotten what she was answering ends up.
     *
     * What buys it is her wanting to say something the five do not say — flowers on a birthday,
     * a cake, a thank-you — and being able to. Whether that is worth the extra step is a judgement
     * about one particular person, so it belongs to whoever looks after her. See [QuickReactions],
     * which decides that the chooser costs the last face and never moves the other four.
     */
    val enableEmojiChoice: Boolean = false,
    /**
     * Whether she can take a photograph with the camera and send it to a contact.
     *
     * Off unless somebody turns it on. When off, no camera button is shown anywhere and nothing
     * about her existing messaging or calling experience changes.
     */
    val enablePhotoSending: Boolean = false,
    /**
     * Whether the camera screen offers the button to change between the back and front camera.
     *
     * Only meaningful when [enablePhotoSending] is on and the device has both cameras. Kept off
     * by default because turning the lens towards herself can startle or confuse her.
     */
    val enableCameraSwitch: Boolean = false,
    /**
     * Where the photo button sits on the home screen.
     *
     * See [PhotoButtonPlacement]. Defaults to [PhotoButtonPlacement.CARD] (full-width button
     * directly under the clock row).
     */
    val photoButtonPlacement: String = PhotoButtonPlacement.CARD,
    val ringSilenceTimeoutSeconds: Int = 0,
    val favoriteContacts: List<Contact> = emptyList(),
    /**
     * Everyone who looks after her, by their sign-in with the caregiver website.
     *
     * A list because there is more than one of them — a daughter and a son do not share a login,
     * and the one who set the phone up is not always the one on call. The website has always
     * added itself to this array; the app simply never read it, so it could say "paired" and
     * nothing more. [isPaired] is now whatever this list says.
     *
     * Owned by the website. The app reads it and must never write it back: a device that pushed
     * its own idea of the list would remove whoever it had not heard about yet.
     */
    val caregiverUids: List<String> = emptyList(),
    /**
     * The order the caregiver put her favourites in, as contact ids, most important first.
     *
     * Who is a favourite is decided on the phone by starring someone in the ordinary address
     * book; this only says what order they appear in. See [FavouriteArrangement] for why those
     * are two different decisions belonging to two different people.
     *
     * Owned by the website, like [caregiverUids]: the app reads it and never writes it back.
     */
    val favouriteOrder: List<String> = emptyList(),
    /**
     * The several-people conversations the caregiver has switched off, by [GroupVisibility.keyOf].
     *
     * Empty is the ordinary state and means every group is shown, which is what the phone did
     * before this existed. See [GroupVisibility] for why hiding is remote, per group, and never
     * something she is asked to do herself.
     *
     * Owned by the website, like [favouriteOrder]: the app reads it and never writes it back.
     */
    val hiddenGroups: List<String> = emptyList(),
    /**
     * The people whose WhatsApp statuses the caregiver has switched off, by
     * [StatusPosterVisibility.keyOf].
     *
     * Empty is the ordinary state and means every status is shown. See [StatusPosterVisibility].
     *
     * Owned by the website, like [hiddenGroups]: the app reads it and never writes it back.
     */
    val hiddenStatusPosters: List<String> = emptyList(),
    /**
     * The senders a caregiver has said are people after all, by [SenderJudgement.keyOf].
     *
     * Empty is the ordinary state and means the phone's own reading of an address stands, which is
     * what it did before this existed. What the list is for is the sender the address gets wrong:
     * her doctor's surgery texting from four digits, the pharmacy writing from a short name. Filed
     * as companies they get no face, no card at the top of her home screen and no sound at all —
     * see [SenderJudgement] for why that judgement belongs to whoever looks after her.
     *
     * Owned by the website, like [hiddenGroups]: the app reads it and never writes it back.
     */
    val sendersAsPeople: List<String> = emptyList(),
    /**
     * Whether the messages from companies are shown to her at all.
     *
     * **On unless somebody turns it off**, which is what the phone did before this switch existed:
     * a text is a text, and hiding one nobody asked to hide is the worse mistake of the two.
     *
     * What turning it off buys is a message list that is only people. Some phones receive more
     * from parking apps, pharmacies, delivery firms and marketing services than from the family,
     * and for somebody who reads slowly and takes every message seriously that is not clutter —
     * it is a list she works through, most of it addressed to nobody, before she reaches her son.
     *
     * What it costs is everything else those senders carry: the code her bank sends when she is
     * being talked through a payment on the telephone, the surgery confirming Tuesday, the
     * pharmacy saying a prescription is ready. That is why it is off by default, and why
     * [sendersAsPeople] is the other half of it — a sender corrected to a person is shown even
     * with this off, so "hide the companies, except the surgery" is one decision made in two
     * places rather than a choice between the two.
     *
     * Hidden, never blocked. The messages still arrive and are still stored, so switching this
     * back on brings them back in full — see [SenderJudgement.shown].
     */
    val showCompanyMessages: Boolean = true,
    /**
     * Whether birthdays are among the appointments she is shown, on the home screen and in the
     * list alike.
     *
     * **On unless somebody turns it off**, which is what the phone did before this switch existed.
     * What turning it off is for is a calendar that is mostly other people's birthdays. On her
     * phone they are yearly entries typed into her own calendar, not a calendar of their own, so
     * there is nothing to untick that would lose them — and on a quiet week they are all "what is
     * coming" has left to say.
     *
     * Hidden, never removed: the calendar is not touched, and switching this back on brings them
     * back at once. Which entries count as a birthday is [Agenda.isBirthday]'s decision.
     */
    val showBirthdays: Boolean = true,
    /**
     * Whether her home screen shows how many steps she has taken today and yesterday.
     *
     * On unless somebody turns it off, because the block is only shown when the sensor and
     * permission are available and stays below the fold at the foot of the screen. Turning it off
     * is a judgement for whoever looks after her — for instance, if seeing the count causes worry
     * rather than encouragement.
     */
    val showSteps: Boolean = true,
    /**
     * Whether a WhatsApp call is turned away the moment it rings, instead of ringing in the
     * WhatsApp app.
     *
     * **Off unless somebody turns it on**, like [blockUnknownCallers], because it ends calls. What
     * turning it on is for is the phone where the WhatsApp app is only there to hold the link: its
     * ringing screen is one she was never taught, the call cannot be taken in this app — see
     * [CallCourtesy] — and whoever picks it up there is not her. Turned away, the call becomes a
     * missed call on her home screen, and a caller her address book knows is told, once, to ring
     * the telephone number or on Threema instead.
     *
     * Strangers and groups are turned away too, and told nothing, as with any missed WhatsApp call.
     */
    val rejectWhatsAppCalls: Boolean = false,
    /**
     * Whether, while the phone is roaming abroad, calls go only over Threema.
     *
     * **On unless somebody turns it off** (2026-09-16). Abroad an ordinary call is paid
     * by the minute and a Threema call over Wi-Fi or data is not, and she cannot be asked to weigh
     * that. With it on and the phone roaming, pressing a face with no Threema behind it does not
     * ring at all, and she is told the call could not be made — the price of never running up a
     * roaming bill without anyone deciding to. Emergency numbers are dialled regardless. At home
     * the switch changes nothing. See [ch.heuscher.gentleconnect.data.CallRouter].
     */
    val threemaOnlyWhenRoaming: Boolean = true,
    /**
     * Whether incoming Threema calls from identities not in her address book are blocked.
     *
     * **On unless somebody turns it off.** No legitimate stranger reaches her on Threema; a
     * scammer who does is who she cannot defend against. When on, an offer from an identity
     * that does not resolve to a known contact is rejected silently and recorded in the caregiver
     * log, without ever ringing or waking her screen.
     */
    val blockUnknownThreemaCallers: Boolean = true,
    /**
     * Whether the sentence shown when a call cannot be made at all is also read aloud.
     *
     * **Off unless somebody turns it on** (2026-09-17). The large card with the person's
     * photo and the sentence always appears; a voice suddenly speaking can startle somebody who
     * gets nervous quickly, and whether it helps or frightens is a judgement about one person.
     * See [ch.heuscher.gentleconnect.voip.CallFailedActivity].
     */
    val speakCallFailures: Boolean = false,
    /**
     * The members of her support group, configured on the caregiver portal.
     * Order is display order; the first member's face appears on the support button.
     */
    val supportGroup: List<SupportMember> = emptyList(),
    /** The group name used in dialogs and messages (e.g. "Familie"). Empty means default. */
    val supportGroupName: String = "",
    /** How messages name her (e.g. "Mama"). Empty means default. */
    val supportPersonName: String = "",
    /** Whether location coordinates are attached to support alert messages. */
    val supportShareLocation: Boolean = true,
    /**
     * Bluetooth devices attributed to a place, a person or an own device by a caregiver.
     *
     * Owned by the website, like [hiddenGroups] and [sendersAsPeople]: the app reads it and never
     * writes it back. Used by [LocatedWith.resolve] on each heartbeat to say where and with whom
     * the phone is.
     */
    val bluetoothAttributions: List<BluetoothAttribution> = emptyList()
) {
    /** Somebody is looking after this phone. */
    val hasCaregiver: Boolean get() = caregiverUids.isNotEmpty() || isPaired

    /** Whether a support group is configured so the support button can be shown. */
    val hasSupportGroup: Boolean get() = supportGroup.isNotEmpty()
}
