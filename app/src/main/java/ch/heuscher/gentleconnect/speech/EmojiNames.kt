package ch.heuscher.gentleconnect.speech

import android.content.Context
import ch.heuscher.gentleconnect.R

/**
 * What the reading-aloud voice calls each emoji.
 *
 * Kept here, in the layer that is allowed to know about string resources, rather than beside
 * [ch.heuscher.gentleconnect.model.Emoji] which does the replacing: a word for a picture is a
 * word, and words in this app live in `strings.xml` where the German is the one that matters.
 *
 * **One code point each, and no compound sequences.** A family of four is four emoji joined
 * together, and naming the whole thing would mean a table of every combination anybody might
 * send. Read one at a time it comes out as a list of people, which is clumsy and true; the
 * alternative was silence.
 *
 * The list is short on purpose. These are the ones that actually arrive from her family, and an
 * emoji that is not here is left on the screen exactly as it was rather than being guessed at —
 * see [ch.heuscher.gentleconnect.model.Emoji.spell]. The escapes rather than the characters
 * themselves are deliberate: an editor or a merge that mangles one emoji in a source file is
 * hard to see, and the glyph in the comment beside each line is what makes the table readable.
 */
object EmojiNames {

    val RESOURCE_MAP: Map<String, Int> = mapOf(
        "\uD83D\uDC4D" to R.string.emoji_thumbs_up, // 👍
        "\uD83D\uDC4E" to R.string.emoji_thumbs_down, // 👎
        "\u2764" to R.string.emoji_heart, // ❤
        "\uD83D\uDC95" to R.string.emoji_hearts, // 💕
        "\uD83D\uDE0A" to R.string.emoji_smiling_face, // 😊
        "\uD83D\uDE00" to R.string.emoji_happy_face, // 😀
        "\uD83D\uDE02" to R.string.emoji_laughing, // 😂
        "\uD83E\uDD23" to R.string.emoji_laughing_out_loud, // 🤣
        "\uD83D\uDE09" to R.string.emoji_winking, // 😉
        "\uD83D\uDE0D" to R.string.emoji_delighted, // 😍
        "\uD83D\uDE18" to R.string.emoji_kiss, // 😘
        "\uD83D\uDE22" to R.string.emoji_sad_face, // 😢
        "\uD83D\uDE2D" to R.string.emoji_crying, // 😭
        "\uD83D\uDE21" to R.string.emoji_angry_face, // 😡
        "\uD83C\uDF89" to R.string.emoji_hooray, // 🎉
        "\uD83E\uDD73" to R.string.emoji_celebrating, // 🥳
        "\uD83D\uDE4F" to R.string.emoji_thank_you, // 🙏
        "\uD83D\uDC4F" to R.string.emoji_applause, // 👏
        "\uD83D\uDC4B" to R.string.emoji_waving, // 👋
        "\uD83D\uDCAA" to R.string.emoji_strong, // 💪
        "\uD83C\uDF38" to R.string.emoji_flower, // 🌸
        "\uD83D\uDC90" to R.string.emoji_flowers, // 💐
        "\uD83C\uDF39" to R.string.emoji_rose, // 🌹
        "\u2600" to R.string.emoji_sunshine, // ☀
        "\uD83C\uDF40" to R.string.emoji_good_luck, // 🍀
        "\uD83C\uDF82" to R.string.emoji_cake, // 🎂
        "\uD83C\uDF81" to R.string.emoji_present, // 🎁
        "\uD83D\uDE34" to R.string.emoji_sleeping, // 😴
        "\uD83E\uDD17" to R.string.emoji_hug, // 🤗
        "\u2705" to R.string.emoji_done, // ✅
        "\uD83D\uDCDE" to R.string.emoji_telephone, // 📞
        "\u2709" to R.string.emoji_letter // ✉
    )

    /**
     * The table with the words filled in, resolved once against the app's own strings — the same
     * way the voice's language is decided in [TtsManager], so that what she hears and what she
     * reads cannot end up in two different languages.
     */
    fun resolve(context: Context): Map<String, String> {
        return RESOURCE_MAP.mapValues { (_, resId) -> context.getString(resId) }
    }
}
