package ch.heuscher.gentleconnect.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.PersonName

/**
 * A person's name as it goes on her screen, with a word for when there is no name.
 *
 * Every place that prints who somebody is goes through here, so that a person is called the same
 * thing on the home screen, in the list, at the top of a conversation and inside it. The rule
 * itself is in [PersonName]; this is only the part that needs to know which language the phone is
 * speaking.
 */
@Composable
fun personName(rawName: String?): String =
    PersonName.forHer(rawName) ?: stringResource(R.string.person_unknown)
