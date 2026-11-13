package ch.heuscher.gentleconnect.model

/**
 * Which way a message went.
 *
 * Until now the app had no concept of this at all: everything it held had arrived, and what she
 * sent left the phone and was forgotten. For someone whose memory is fading that is worse than
 * untidy — having answered and having no evidence of it is exactly the situation that produces the
 * same message sent three times, or a daughter left unanswered because she could not remember
 * whether she had already written.
 */
enum class MessageDirection { INCOMING, OUTGOING }
