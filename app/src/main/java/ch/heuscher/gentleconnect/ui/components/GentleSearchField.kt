package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R

/**
 * The box she types into to find something.
 *
 * The same object on her messages and on the View screen as the one already sitting on top of her
 * address book, deliberately: a search box that looks and behaves differently on each screen is
 * three things to learn instead of one.
 *
 * The cross on the right matters more here than it looks. Typing is the easy half; what strands
 * somebody is a list that has quietly stayed filtered — she searched for "Anna" last week, came
 * back today, and half her messages are gone with no visible reason. A large, obvious way to
 * empty the box is what stops "my messages have disappeared".
 */
@Composable
fun GentleSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.search_label)
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text(label) },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                GentleSecondaryKey(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.width(40.dp),
                    shape = CircleShape,
                    minHeight = 40.dp,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.search_clear)
                    )
                }
            }
        },
        textStyle = MaterialTheme.typography.titleMedium,
        modifier = modifier.fillMaxWidth()
    )
}
