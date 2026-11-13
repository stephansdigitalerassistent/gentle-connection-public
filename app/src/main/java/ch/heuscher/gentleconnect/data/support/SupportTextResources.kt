package ch.heuscher.gentleconnect.data.support

import android.content.Context
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.SupportTexts

/**
 * The help button's sentences, read out of the resources for whichever language the phone is in.
 *
 * The bridge between `strings.xml` and `model/`, which may not import `android.*`. Read fresh for
 * each message rather than kept: it is a handful of resource lookups, and a phone whose language
 * was changed this morning must not still be sending yesterday's.
 */
fun supportTextsOf(context: Context): SupportTexts = SupportTexts(
    alertHeadline = context.getString(R.string.support_msg_alert_headline),
    alertPressedAt = context.getString(R.string.support_msg_alert_pressed),
    accuracy = context.getString(R.string.support_msg_accuracy),
    ageMinutes = context.getString(R.string.support_msg_age_minutes),
    ageOneMinute = context.getString(R.string.support_msg_age_one_minute),
    phoneLine = context.getString(R.string.support_msg_phone_line),
    battery = context.getString(R.string.support_msg_battery),
    charging = context.getString(R.string.support_msg_charging),
    roaming = context.getString(R.string.support_msg_roaming),
    sentToNames = context.getString(R.string.support_msg_sent_to_names),
    sentToGroup = context.getString(R.string.support_msg_sent_to_group),
    groupFallbackName = context.getString(R.string.support_msg_group_fallback),
    and = context.getString(R.string.support_names_and),
    closing = context.getString(R.string.support_msg_closing),
    smsAlert = context.getString(R.string.support_sms_alert),
    smsLocation = context.getString(R.string.support_sms_location),
    smsAccuracy = context.getString(R.string.support_sms_accuracy),
    smsAgeMinutes = context.getString(R.string.support_sms_age_minutes),
    smsAgeOneMinute = context.getString(R.string.support_sms_age_one_minute),
    smsBattery = context.getString(R.string.support_sms_battery),
    smsClosing = context.getString(R.string.support_sms_closing),
    claimByReply = context.getString(R.string.support_msg_claim_reply),
    claimByCall = context.getString(R.string.support_msg_claim_call),
    secondClaim = context.getString(R.string.support_msg_second_claim),
    locationFollowUp = context.getString(R.string.support_msg_location_follow_up)
)
