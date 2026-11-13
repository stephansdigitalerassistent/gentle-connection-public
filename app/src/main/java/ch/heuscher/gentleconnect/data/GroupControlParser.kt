package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.GroupIdentity

/**
 * What a group control message told us.
 *
 * These are the only messages that say who is in a group and what it is called. Threema sends no
 * roster and offers no lookup: membership is knowledge you either accumulated by listening or do
 * not have.
 */
sealed interface GroupControlMessage {

    val group: GroupIdentity

    /**
     * The authoritative member list, from the creator. The only message that says who is in a
     * group, which is why [ThreemaGroupRepository] replaces the membership wholesale on one of
     * these rather than merging.
     */
    data class Setup(
        override val group: GroupIdentity,
        val members: List<String>
    ) : GroupControlMessage

    /** The group's name, when it has one. An empty name is a name being taken away. */
    data class Renamed(
        override val group: GroupIdentity,
        val name: String?
    ) : GroupControlMessage

    /** Somebody leaving. Note the leaver is the message's sender, not anything in its body. */
    data class Left(
        override val group: GroupIdentity,
        val identity: String
    ) : GroupControlMessage
}

/**
 * Reads the group control messages out of what the bridge calls "unknown".
 *
 * **The bridge cannot tell us the group identity for two of these three, and the reason is a real
 * bug one layer down.** `libthreema` decodes every message type at or above `0x40` by assuming the
 * body starts with an 8-byte creator identity followed by an 8-byte group id. That holds for a
 * group *member's* message, and for group-leave. It is wrong for group-setup and group-rename,
 * whose bodies start with the group id directly — the creator is the sender, so it is not repeated.
 *
 * What saves us is that the misread fails loudly rather than quietly: eight random group-id bytes
 * almost never spell a valid Threema ID, so the decode returns nothing and the message arrives as a
 * plain contact message with no group attached. So these are parsed here, from the sender and the
 * raw body, exactly as `MessageCoder` in the Threema app does it.
 *
 * Pure, and deliberately strict. A member list is what a reply will one day be fanned out to, and a
 * misparse would either invent people or silently drop them.
 */
object GroupControlParser {

    const val TYPE_SETUP = 0x4a
    const val TYPE_RENAME = 0x4b
    const val TYPE_LEAVE = 0x4c

    private const val IDENTITY_LENGTH = 8
    private const val GROUP_ID_LENGTH = 8

    /**
     * @param sender the identity that sent this, which for setup and rename is the group's creator
     *   and for leave is the person leaving.
     * @return null for anything that is not a group control message, or that is one and is
     *   malformed. Both are ordinary — most "unknown" messages are files and images.
     */
    fun parse(sender: String, messageType: Int, dataHex: String): GroupControlMessage? {
        val creatorFromSender = sender.trim().uppercase().takeIf(::isIdentity) ?: return null
        val data = decodeHex(dataHex) ?: return null

        return when (messageType) {
            TYPE_SETUP -> parseSetup(creatorFromSender, data)
            TYPE_RENAME -> parseRename(creatorFromSender, data)
            TYPE_LEAVE -> parseLeave(creatorFromSender, data)
            else -> null
        }
    }

    /** `groupId(8) || member identity(8) * n`, and the creator is the sender. */
    private fun parseSetup(creator: String, data: ByteArray): GroupControlMessage? {
        if (data.size < GROUP_ID_LENGTH) return null
        val remainder = data.size - GROUP_ID_LENGTH
        if (remainder % IDENTITY_LENGTH != 0) return null

        val group = GroupIdentity.of(creator, hexOf(data, 0, GROUP_ID_LENGTH)) ?: return null
        val members = (0 until remainder / IDENTITY_LENGTH).map { index ->
            val start = GROUP_ID_LENGTH + index * IDENTITY_LENGTH
            String(data, start, IDENTITY_LENGTH, Charsets.US_ASCII).trim().uppercase()
        }
        // All or nothing. A member list with one unreadable entry is a member list we do not know,
        // and half a membership presented as a whole is how a reply reaches three of five people
        // while looking exactly like it reached everybody.
        if (members.any { !isIdentity(it) }) return null

        return GroupControlMessage.Setup(group, members.distinct())
    }

    /** `groupId(8) || name(UTF-8)`, and the creator is the sender. */
    private fun parseRename(creator: String, data: ByteArray): GroupControlMessage? {
        if (data.size < GROUP_ID_LENGTH) return null
        val group = GroupIdentity.of(creator, hexOf(data, 0, GROUP_ID_LENGTH)) ?: return null
        val name = String(
            data,
            GROUP_ID_LENGTH,
            data.size - GROUP_ID_LENGTH,
            Charsets.UTF_8
        ).trim().ifEmpty { null }
        return GroupControlMessage.Renamed(group, name)
    }

    /** `creator(8) || groupId(8)`, and the sender is the one leaving. */
    private fun parseLeave(sender: String, data: ByteArray): GroupControlMessage? {
        if (data.size != IDENTITY_LENGTH + GROUP_ID_LENGTH) return null
        val creator = String(data, 0, IDENTITY_LENGTH, Charsets.US_ASCII).trim().uppercase()
        if (!isIdentity(creator)) return null
        val group = GroupIdentity.of(creator, hexOf(data, IDENTITY_LENGTH, GROUP_ID_LENGTH))
            ?: return null
        return GroupControlMessage.Left(group, sender)
    }

    /** Eight characters from Threema's own alphabet, and nothing else, ever becomes a person. */
    private fun isIdentity(value: String): Boolean =
        value.length == IDENTITY_LENGTH && value.all { it in 'A'..'Z' || it in '0'..'9' }

    private fun hexOf(data: ByteArray, offset: Int, length: Int): String {
        val builder = StringBuilder(length * 2)
        for (index in offset until offset + length) {
            builder.append(HEX_DIGITS[(data[index].toInt() shr 4) and 0xF])
            builder.append(HEX_DIGITS[data[index].toInt() and 0xF])
        }
        return builder.toString()
    }

    private fun decodeHex(hex: String): ByteArray? {
        val trimmed = hex.trim()
        if (trimmed.length % 2 != 0) return null
        val out = ByteArray(trimmed.length / 2)
        for (index in out.indices) {
            val high = Character.digit(trimmed[index * 2], 16)
            val low = Character.digit(trimmed[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
