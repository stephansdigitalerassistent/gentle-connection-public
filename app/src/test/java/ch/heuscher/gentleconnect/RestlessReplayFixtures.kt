package ch.heuscher.gentleconnect

/**
 * Real touch trails from a foldable device, for [RestlessReplayTest]. Names and senders are replaced by
 * placeholders; coordinates, timings, targets and screens are exactly as the phone wrote them.
 *
 * The first three are the trail sidecars of the three restless bursts the old detector raised in
 * the week of 2026-10-24; [CALM] is two ordinary minutes of reading and navigating from
 * 2026-10-22, which no detector may fire on.
 */
object RestlessReplayFixtures {

    /**
     * 2026-10-24 15:52, raised as `burst_taps`. In a conversation she taps the same spot near a
     * message (~430,344dp) four times and hits nothing: she takes something there for a button.
     */
    val SAME_SPOT = """
15:51:49.523 down [Conversation/main] 488,389dp -> (nothing)
15:51:49.641 up   [Conversation/main] drag 118ms 261dp
15:51:51.204 down [Conversation/main] 70,84dp -> click "Zurück nach Hause"
15:51:51.293 up   [Conversation/main] drag 91ms 20dp
15:51:51.398 screen Messages
15:52:01.437 down [Messages/main] 487,321dp -> click "Kontakt A Gestern Deine Nachricht: …"
15:52:01.473 up   [Messages/main] tap 38ms 0dp
15:52:01.772 screen Conversation
15:52:03.534 down [Conversation/main] 499,393dp -> (nothing)
15:52:03.641 up   [Conversation/main] drag 108ms 154dp
15:52:04.319 down [Conversation/main] 406,260dp -> (nothing)
15:52:04.901 up   [Conversation/main] drag 583ms 283dp
15:52:05.208 down [Conversation/main] 382,327dp -> (nothing)
15:52:05.305 up   [Conversation/main] drag 97ms 120dp
15:52:05.808 down [Conversation/main] 471,319dp -> click "▶ Ansehen"
15:52:06.050 up   [Conversation/main] drag 244ms 196dp
15:52:06.357 down [Conversation/main] 478,144dp -> view "8 Sekunden"
15:52:06.904 up   [Conversation/main] drag 549ms 312dp
15:52:07.908 down [Conversation/main] 509,236dp -> (nothing)
15:52:07.971 up   [Conversation/main] drag 66ms 25dp
15:52:09.094 down [Conversation/main] 468,149dp -> (nothing)
15:52:09.878 up   [Conversation/main] drag 787ms 280dp
15:52:10.700 down [Conversation/main] 392,357dp -> (nothing)
15:52:10.781 up   [Conversation/main] tap 84ms 5dp
15:52:11.758 down [Conversation/main] 433,348dp -> (nothing)
15:52:11.771 up   [Conversation/main] tap 15ms 0dp
15:52:12.393 down [Conversation/main] 430,344dp -> (nothing)
15:52:12.412 up   [Conversation/main] tap 21ms 0dp
15:52:13.090 down [Conversation/main] 419,341dp -> (nothing)
15:52:13.145 up   [Conversation/main] tap 56ms 4dp""".trimIndent().lines()

    /**
     * 2026-10-26 20:25, raised as `unanswered_touches`. On the Look screen she taps the top-left
     * corner three times, where every other screen has its green back arrow: she is looking for
     * the way out.
     */
    val EXIT_HUNTING = """
20:24:48.480 up   [Look/main] drag 728ms 29dp
20:24:49.704 down [Look/main] 197,424dp -> click ""
20:24:49.955 up   [Look/main] drag 252ms 140dp
20:24:50.705 down [Look/main] 185,495dp -> click "D Dienst 24. Okt. no-reply@dienst.example…"
20:24:50.973 up   [Look/main] drag 270ms 154dp
20:24:51.372 down [Look/main] 150,321dp -> click ""
20:24:51.598 up   [Look/main] drag 227ms 128dp
20:24:51.974 down [Look/main] 171,358dp -> click ""
20:24:52.216 up   [Look/main] drag 244ms 110dp
20:24:53.043 down [Look/main] 236,421dp -> click ""
20:24:53.367 up   [Look/main] drag 325ms 98dp
20:24:54.046 down [Look/main] 251,506dp -> click ""
20:24:54.318 up   [Look/main] drag 273ms 98dp
20:24:57.462 down [Look/main] 273,482dp -> click ""
20:24:57.654 up   [Look/main] drag 193ms 153dp
20:24:58.289 down [Look/main] 246,516dp -> click ""
20:24:58.462 up   [Look/main] drag 176ms 142dp
20:24:59.098 down [Look/main] 237,535dp -> click ""
20:24:59.314 up   [Look/main] drag 218ms 161dp
20:24:59.839 down [Look/main] 132,544dp -> click ""
20:25:00.073 up   [Look/main] drag 235ms 159dp
20:25:01.378 down [Look/main] 104,487dp -> click "D Dienst 24. Okt. no-reply@dienst.example…"
20:25:01.591 up   [Look/main] drag 215ms 165dp
20:25:01.975 down [Look/main] 125,498dp -> click "D Dienst 24. Okt. no-reply@dienst.example…"
20:25:02.241 up   [Look/main] drag 268ms 175dp
20:25:02.624 down [Look/main] 113,503dp -> click "D Dienst 24. Okt. no-reply@dienst.example…"
20:25:02.809 up   [Look/main] drag 187ms 116dp
20:25:03.202 down [Look/main] 249,583dp -> click "D Dienst 24. Okt. no-reply@dienst.example…"
20:25:03.392 up   [Look/main] drag 193ms 92dp
20:25:03.660 down [Look/main] 209,557dp -> click "D Dienst 24. Okt. no-reply@dienst.example…"
20:25:03.836 up   [Look/main] drag 178ms 56dp
20:25:04.228 down [Look/main] 195,270dp -> click ""
20:25:04.435 up   [Look/main] drag 209ms 209dp
20:25:04.728 down [Look/main] 198,344dp -> click ""
20:25:04.911 up   [Look/main] drag 185ms 115dp
20:25:05.306 down [Look/main] 314,152dp -> click ""
20:25:05.394 up   [Look/main] tap 90ms 11dp
20:25:08.657 down [Look/main] 59,228dp -> click ""
20:25:08.799 up   [Look/main] tap 144ms 16dp
20:25:09.548 down [Look/main] 30,187dp -> click "Kontakt B vor 17 Min. 1 neu "
20:25:09.632 up   [Look/main] tap 85ms 4dp
20:25:11.156 down [Look/main] 87,94dp -> (nothing)
20:25:11.291 up   [Look/main] tap 136ms 6dp
20:25:12.274 down [Look/main] 91,105dp -> (nothing)
20:25:12.359 up   [Look/main] tap 85ms 4dp
20:25:13.975 down [Look/main] 12,86dp -> (nothing)
20:25:14.053 up   [Look/main] tap 78ms 4dp
20:25:16.656 down [Look/main] 262,484dp -> (nothing)
20:25:16.797 up   [Look/main] drag 142ms 208dp
20:25:17.828 down [Look/main] 255,372dp -> (nothing)
20:25:17.882 up   [Look/main] tap 55ms 1dp""".trimIndent().lines()

    /**
     * 2026-10-27 00:28, raised as `burst_taps`. Each tap at the bottom of the screen lands on the
     * next screen's button at the same place within half a second of it appearing (Anrufen →
     * Nachricht → Ansehen): taps that carried through, not agitation. Half an hour after midnight.
     */
    val CLICK_THROUGH = """
00:28:53.942 down [Home/main] 151,676dp -> (nothing)
00:28:54.036 up   [Home/main] drag 100ms 359dp
00:28:56.084 down [Home/main] 58,61dp -> click "Karte zum Bezahlen freischalten"
00:28:56.173 up   [Home/main] tap 91ms 3dp
00:28:58.930 down [Home/main] 155,78dp -> view "00:28"
00:28:59.058 up   [Home/main] drag 133ms 741dp
00:28:59.421 down [Home/main] 231,710dp -> (nothing)
00:28:59.566 up   [Home/main] drag 148ms 353dp
00:29:21.412 down [Home/main] 150,789dp -> click "Anrufen"
00:29:21.534 up   [Home/main] tap 125ms 4dp
00:29:21.653 screen PhoneBook
00:29:22.131 down [PhoneBook/main] 206,790dp -> click "Nachricht"
00:29:22.233 up   [PhoneBook/main] tap 107ms 0dp
00:29:22.331 screen Messages
00:29:22.771 down [Messages/main] 295,784dp -> click "Ansehen"
00:29:22.887 up   [Messages/main] tap 121ms 0dp
00:29:22.962 screen Look
00:29:23.866 down [Look/main] 47,816dp -> (nothing)
00:29:23.880 up   [Look/main] tap 19ms 0dp""".trimIndent().lines()

    /** 2026-10-22 19:14–19:16: reading, scrolling, going back and opening the next message. */
    val CALM = """
19:14:14.557 down [Home/main] 165,596dp -> click "Kontakt C hat geschrieben: Fo…"
19:14:14.648 up   [Home/main] tap 91ms 0dp
19:14:14.797 screen Conversation
19:14:21.046 down [Conversation/main] 264,566dp -> (nothing)
19:14:21.363 up   [Conversation/main] drag 316ms 190dp
19:14:21.919 down [Conversation/main] 158,534dp -> click "🔊 Vorlesen"
19:14:22.056 up   [Conversation/main] drag 138ms 147dp
19:14:22.464 down [Conversation/main] 208,294dp -> (nothing)
19:14:24.150 up   [Conversation/main] drag 1687ms 315dp
19:14:24.946 down [Conversation/main] 243,262dp -> (nothing)
19:14:25.496 up   [Conversation/main] drag 551ms 290dp
19:14:26.084 down [Conversation/main] 244,332dp -> (nothing)
19:14:26.780 up   [Conversation/main] drag 696ms 308dp
19:14:27.493 down [Conversation/main] 231,450dp -> (nothing)
19:14:27.861 up   [Conversation/main] drag 368ms 181dp
19:14:28.345 down [Conversation/main] 241,387dp -> (nothing)
19:14:28.674 up   [Conversation/main] drag 329ms 209dp
19:14:29.253 down [Conversation/main] 251,408dp -> (nothing)
19:14:29.675 up   [Conversation/main] drag 423ms 262dp
19:14:39.505 down [Conversation/main] 210,415dp -> (nothing)
19:14:40.163 up   [Conversation/main] drag 659ms 38dp
19:14:40.747 down [Conversation/main] 79,500dp -> (nothing)
19:14:41.856 up   [Conversation/main] drag 1109ms 156dp
19:14:42.697 down [Conversation/main] 285,301dp -> view "Kontakt C"
19:14:42.999 up   [Conversation/main] drag 303ms 164dp
19:14:44.191 down [Conversation/main] 248,512dp -> (nothing)
19:14:44.318 up   [Conversation/main] drag 127ms 95dp
19:14:45.208 down [Conversation/main] 249,499dp -> (nothing)
19:14:45.426 up   [Conversation/main] drag 219ms 149dp
19:14:46.142 down [Conversation/main] 243,479dp -> (nothing)
19:14:46.421 up   [Conversation/main] drag 279ms 173dp
19:14:48.189 down [Conversation/main] 233,491dp -> (nothing)
19:14:48.374 up   [Conversation/main] drag 185ms 202dp
19:14:49.231 down [Conversation/main] 225,502dp -> (nothing)
19:14:49.514 up   [Conversation/main] drag 282ms 256dp
19:14:50.949 down [Conversation/main] 277,475dp -> (nothing)
19:14:51.240 up   [Conversation/main] drag 291ms 187dp
19:15:31.939 down [Conversation/main] 182,696dp -> click "Antworten"
19:15:32.174 up   [Conversation/main] drag 235ms 704dp
19:15:34.173 down [Conversation/main] 65,81dp -> click "Zurück nach Hause"
19:15:34.242 up   [Conversation/main] tap 70ms 4dp
19:15:34.288 screen Messages
19:15:43.508 down [Messages/main] 38,742dp -> (nothing)
19:15:45.164 up   [Messages/main] drag 1656ms 88dp
19:15:46.179 down [Messages/main] 76,696dp -> click "Zuhause"
19:15:46.289 up   [Messages/main] tap 111ms 10dp
19:15:46.360 screen Home
19:15:49.144 down [Home/main] 252,570dp -> click "Kontakt D hat geschrieben: Wow!!! Ö…"
19:15:49.173 up   [Home/main] tap 30ms 0dp
19:15:49.318 screen Conversation
19:15:51.749 down [Conversation/main] 548,419dp -> (nothing)
19:15:51.922 up   [Conversation/main] drag 174ms 130dp
19:15:55.344 down [Conversation/main] 64,80dp -> click "Zurück nach Hause"
19:15:55.439 up   [Conversation/main] tap 96ms 4dp
19:15:55.475 screen Messages
19:15:58.303 down [Messages/main] 86,698dp -> click "Zuhause"
19:15:58.353 up   [Messages/main] tap 51ms 10dp
19:15:58.434 screen Home
19:15:59.846 down [Home/main] 258,556dp -> click "Familie Gerade eben Kontakt D: Wo…"
19:15:59.980 up   [Home/main] drag 135ms 223dp
19:16:01.031 down [Home/main] 236,536dp -> click "E ePost Gestern Frau M., Ihr Termi…"
19:16:01.167 up   [Home/main] drag 136ms 225dp
19:16:02.873 down [Home/main] 545,471dp -> (nothing)
19:16:02.923 up   [Home/main] tap 50ms 3dp
19:16:03.603 down [Home/main] 170,197dp -> click "E ePost Gestern Frau M., Ihr Termi…"
19:16:03.664 up   [Home/main] tap 62ms 19dp
19:16:03.990 screen Conversation
19:16:04.168 down [Conversation/main] 177,203dp -> click "🔊 Vorlesen"
19:16:04.282 up   [Conversation/main] drag 115ms 160dp
19:16:12.537 down [Conversation/main] 212,744dp -> (nothing)
""".trimIndent().lines()

    /**
     * 2026-10-31 16:51, raised as `burst_taps`. In a conversation after sending a message she
     * taps the laughing emoji 😂 four times in under a second: normal repeated emoji reaction,
     * not agitation.
     */
    val REACTION_BURST = """
16:51:03.911 down [Conversation/main] 592,310dp -> click "Senden"
16:51:03.990 up   [Conversation/main] tap 80ms 0dp
16:51:04.022 event [Conversation] goal message sent
16:51:10.952 down [Conversation/main] 291,618dp -> click "😂"
16:51:11.091 up   [Conversation/main] tap 139ms 0dp
16:51:14.783 down [Conversation/main] 299,605dp -> click "😂"
16:51:14.876 up   [Conversation/main] tap 94ms 0dp
16:51:15.140 down [Conversation/main] 294,606dp -> click "😂"
16:51:15.201 up   [Conversation/main] tap 61ms 0dp
16:51:15.381 down [Conversation/main] 296,606dp -> click "😂"
16:51:15.457 up   [Conversation/main] tap 76ms 0dp
16:51:15.594 down [Conversation/main] 295,604dp -> click "😂"
16:51:15.674 up   [Conversation/main] tap 81ms 0dp
""".trimIndent().lines()

    /**
     * 2026-11-05 23:38 on the unfolded Fold (640 dp wide), raised as `unanswered_touches` and
     * judged a false alarm: reading the home screen, she rests her thumb and palm on the rims
     * while holding the phone, then presses "Foto machen". Four of the five dead taps lie within
     * [ch.heuscher.gentleconnect.model.AgitationPolicy.EDGE_GRIP_DP] of an edge.
     */
    val EDGE_GRIP = """
23:38:27.824 down [Home/main] 237,425dp -> click "Kontakt A 2. Nov."
23:38:27.899 up   [Home/main] drag 77ms 244dp to -22,+243dp
23:38:28.889 down [Home/main] 257,389dp -> click "Heute Kontakt B hat Geburtstag"
23:38:29.131 up   [Home/main] drag 243ms 299dp to -68,+291dp
23:38:29.562 down [Home/main] 181,678dp -> click "Anrufen"
23:38:29.705 up   [Home/main] drag 144ms 284dp to +57,-278dp
23:38:30.130 down [Home/main] 173,584dp -> click "Morgen Geburtstag Kontakt C (1943)"
23:38:30.222 up   [Home/main] drag 93ms 209dp to -3,-209dp
23:38:47.731 down [Home/main] 636,369dp -> (nothing)
23:38:47.734 up   [Home/main] tap 5ms 0dp
23:38:48.778 down [Home/main] 637,350dp -> (nothing)
23:38:48.785 up   [Home/main] tap 8ms 4dp
23:38:49.076 down [Home/main] 34,744dp -> (nothing)
23:38:49.251 up   [Home/main] tap 176ms 0dp
23:38:52.406 down [Home/main] 9,107dp -> (nothing)
23:38:52.583 up   [Home/main] tap 178ms 11dp
23:38:52.908 down [Home/main] 2,229dp -> (nothing)
23:38:55.709 up   [Home/main] drag 2802ms 56dp to -2,-56dp
23:38:56.978 down [Home/main] 88,308dp -> click "Foto machen"
23:38:56.980 down [Home/photo-home] 88,308dp -> click "Foto machen"
23:38:57.295 up   [Home/photo-home] drag 316ms 112dp to +13,+111dp
23:38:57.296 up   [Home/main] drag 320ms 112dp to +13,+111dp
23:38:57.334 down [Home/main] 16,515dp -> (nothing)
23:38:57.484 up   [Home/main] tap 151ms 11dp
""".trimIndent().lines()
}
