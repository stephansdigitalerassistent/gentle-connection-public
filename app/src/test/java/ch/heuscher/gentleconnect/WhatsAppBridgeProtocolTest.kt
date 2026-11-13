package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.whatsapp.CallState
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppBridgeProtocol
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppEvent
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pipe between the app and the WhatsApp bridge.
 *
 * Every line here was taken from the bridge running on her actual phone, not invented: the
 * `state` line is the first thing it printed, and the `error` line is the DNS failure that the
 * first device run produced — Android has no /etc/resolv.conf, so a Go binary built without cgo
 * has no name servers at all.
 */
class WhatsAppBridgeProtocolTest {

    @Test
    fun `the first line the bridge really printed on her phone`() {
        val event = WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"linked":false,"type":"state"}""")

        assertEquals(WhatsAppEvent.State(linked = false), event)
    }

    @Test
    fun `the DNS failure from the first device run is an error, not a crash`() {
        val line = """BRIDGE-JSON: {"message":"failed to dial whatsapp web websocket: """ +
            """lookup web.whatsapp.com on [::1]:53: connection refused","stage":"connect","type":"error"}"""

        val event = WhatsAppBridgeProtocol.parse(line)

        assertTrue(event is WhatsAppEvent.Failure)
        assertEquals("connect", (event as WhatsAppEvent.Failure).stage)
    }

    @Test
    fun `a line without the prefix is not an event`() {
        assertNull(WhatsAppBridgeProtocol.parse("some library logging on stdout"))
        assertNull(WhatsAppBridgeProtocol.parse(""))
        assertNull(WhatsAppBridgeProtocol.parse(null))
    }

    @Test
    fun `a bare object is still read`() {
        assertEquals(
            WhatsAppEvent.Connected,
            WhatsAppBridgeProtocol.parse("""{"type":"connected"}""")
        )
    }

    @Test
    fun `rubbish on the pipe is ignored rather than thrown`() {
        assertNull(WhatsAppBridgeProtocol.parse("BRIDGE-JSON: not json at all"))
        assertNull(WhatsAppBridgeProtocol.parse("BRIDGE-JSON: {"))
    }

    @Test
    fun `an event kind from a later bridge degrades to silence`() {
        // The phone must survive a bridge that knows more than it does. Null, never an exception:
        // this parses on a background thread in a loop, and a throw there is a crash loop on the
        // phone somebody's mother depends on.
        assertNull(WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"type":"call_offer"}"""))
    }

    @Test
    fun `a message becomes something with a sender and words`() {
        val line = """BRIDGE-JSON: {"type":"message","messageId":"3EB0","sender":""" +
            """"41791234567@s.whatsapp.net","chat":"41791234567@s.whatsapp.net","isGroup":false,""" +
            """"pushName":"Anna","text":"Bis morgen","timestamp":1786200000000,"direction":"in"}"""

        val event = WhatsAppBridgeProtocol.parse(line)

        assertTrue(event is WhatsAppEvent.Incoming)
        event as WhatsAppEvent.Incoming
        assertEquals("3EB0", event.messageId)
        assertEquals("Anna", event.pushName)
        assertEquals("Bis morgen", event.text)
        assertEquals(1786200000000L, event.timestamp)
        assertFalse(event.outgoing)
        assertFalse(event.isGroup)
        assertEquals("41791234567", WhatsAppNumber.numberFromJid(event.senderJid))
    }

    @Test
    fun `her own message echoed back is marked as hers`() {
        val line = """BRIDGE-JSON: {"type":"message","messageId":"X","sender":"41790000000@s.whatsapp.net",""" +
            """"text":"ja gerne","timestamp":1,"direction":"out"}"""

        val event = WhatsAppBridgeProtocol.parse(line) as WhatsAppEvent.Incoming

        assertTrue(event.outgoing)
    }

    @Test
    fun `a message with no words is not put on her screen`() {
        val noText = """BRIDGE-JSON: {"type":"message","messageId":"X","sender":"a@s.whatsapp.net","text":""}"""
        val noId = """BRIDGE-JSON: {"type":"message","sender":"a@s.whatsapp.net","text":"hallo"}"""
        val noSender = """BRIDGE-JSON: {"type":"message","messageId":"X","text":"hallo"}"""

        assertNull(WhatsAppBridgeProtocol.parse(noText))
        assertNull(WhatsAppBridgeProtocol.parse(noId))
        assertNull(WhatsAppBridgeProtocol.parse(noSender))
    }

    @Test
    fun `a message with no chat falls back to the sender`() {
        val line = """BRIDGE-JSON: {"type":"message","messageId":"X","sender":"a@s.whatsapp.net","text":"hi"}"""

        val event = WhatsAppBridgeProtocol.parse(line) as WhatsAppEvent.Incoming

        assertEquals("a@s.whatsapp.net", event.chatJid)
    }

    @Test
    fun `losing the session is told apart from an ordinary disconnection`() {
        // The distinction the caregiver log depends on. Disconnections happen constantly and are
        // not faults; being logged out is a fault that needs a person at the phone.
        assertEquals(
            WhatsAppEvent.Disconnected,
            WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"type":"disconnected"}""")
        )
        assertEquals(
            WhatsAppEvent.LoggedOut("stream_replaced"),
            WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"type":"logged_out","reason":"stream_replaced"}""")
        )
    }

    @Test
    fun `a logout with no reason still says it happened`() {
        assertEquals(
            WhatsAppEvent.LoggedOut("unknown"),
            WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"type":"logged_out"}""")
        )
    }

    @Test
    fun `an unlink we asked for is told apart from one done to us`() {
        // Found on her phone: pressing Unlink cleared the session and disconnected, and whatsmeow
        // said nothing — a requested logout raises no events.LoggedOut, only a server-side one
        // does. The bridge now says it itself, and this reason is what stops the caregiver log
        // from reporting a caregiver's own button press as a failure to every other caregiver.
        val requested = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"logged_out","reason":"unlinked"}"""
        )

        assertEquals(WhatsAppEvent.LoggedOut(WhatsAppBridgeProtocol.REASON_UNLINKED), requested)
        assertEquals("unlinked", WhatsAppBridgeProtocol.REASON_UNLINKED)

        // Anything WhatsApp itself decides keeps its own reason, and stays a fault.
        val takenAway = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"logged_out","reason":"401"}"""
        ) as WhatsAppEvent.LoggedOut
        assertFalse(takenAway.reason == WhatsAppBridgeProtocol.REASON_UNLINKED)
    }

    @Test
    fun `an attachment arrives after the message it belongs to`() {
        val photo = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"media","messageId":"3EB0","path":"/data/x/media/3EB0.jpg","kind":"photo"}"""
        )
        assertEquals(WhatsAppEvent.Media("3EB0", "/data/x/media/3EB0.jpg", "photo"), photo)

        val voice = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"media","messageId":"3EB1","path":"/data/x/media/3EB1.ogg","kind":"voice"}"""
        )
        assertEquals(WhatsAppEvent.Media("3EB1", "/data/x/media/3EB1.ogg", "voice"), voice)

        val film = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"media","messageId":"3EB2","path":"/data/x/media/3EB2.mp4","kind":"video"}"""
        )
        assertEquals(WhatsAppEvent.Media("3EB2", "/data/x/media/3EB2.mp4", "video"), film)
    }

    @Test
    fun `a video message brings its still and its film separately`() {
        // The two halves of one video message, and the whole reason they are two events: the
        // still is inside the message and arrives at once, the film has to be downloaded. They
        // name the same message and must both be kept — the picture is what she sees while the
        // film is coming, and what she is left with when it never does.
        val still = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"media","messageId":"3EB2","path":"/data/x/media/3EB2.jpg","kind":"photo"}"""
        ) as WhatsAppEvent.Media
        val film = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"media","messageId":"3EB2","path":"/data/x/media/3EB2.mp4","kind":"video"}"""
        ) as WhatsAppEvent.Media

        assertEquals(still.messageId, film.messageId)
        assertEquals(WhatsAppBridgeProtocol.KIND_PHOTO, still.kind)
        assertEquals(WhatsAppBridgeProtocol.KIND_VIDEO, film.kind)
        // Different files, so that storing one cannot overwrite the other.
        assertFalse(still.path == film.path)
    }

    @Test
    fun `an attachment with no kind is a photograph`() {
        // What the first version of the bridge sent, before voice messages existed. A phone that
        // has been updated while its bridge has not must not lose its pictures.
        val event = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"media","messageId":"3EB0","path":"/data/x/media/3EB0.jpg"}"""
        )

        assertEquals(WhatsAppBridgeProtocol.KIND_PHOTO, (event as WhatsAppEvent.Media).kind)
    }

    @Test
    fun `an attachment this build cannot draw is dropped rather than stored`() {
        // A file recorded as an attachment that nothing knows how to render is a message with an
        // invisible hole in it: something is there, and her screen shows no sign of it.
        assertNull(
            WhatsAppBridgeProtocol.parse(
                """BRIDGE-JSON: {"type":"media","messageId":"X","path":"/x.mp4","kind":"film"}"""
            )
        )
    }

    @Test
    fun `half an attachment is not an attachment`() {
        // Naming no message leaves nothing to attach it to; naming no file would blank a picture
        // that is already on her screen.
        assertNull(WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"type":"media","path":"/x.jpg"}"""))
        assertNull(WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"type":"media","messageId":"3EB0"}"""))
    }

    @Test
    fun `a blank pairing code is not a pairing code`() {
        assertNull(WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"type":"pairing_code","code":""}"""))
        assertEquals(
            WhatsAppEvent.PairingCode("ABCD1234"),
            WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"type":"pairing_code","code":"ABCD1234"}""")
        )
    }

    @Test
    fun `a failed send is reported with the recipient it failed for`() {
        val event = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"send_failed","to":"41791234567@s.whatsapp.net","message":"not connected"}"""
        )

        assertEquals(
            WhatsAppEvent.SendFailed("41791234567@s.whatsapp.net", "not connected"),
            event
        )
    }

    @Test
    fun `the pair command carries digits and never a plus`() {
        val json = JSONObject(WhatsAppBridgeProtocol.pairCommand("+41 79 123 45 67"))

        assertEquals("pair", json.getString("cmd"))
        assertEquals("41791234567", json.getString("phone"))
    }

    @Test
    fun `the send command round trips through the bridge's own reader`() {
        val json = JSONObject(WhatsAppBridgeProtocol.sendCommand("41791234567@s.whatsapp.net", "Hallo"))

        assertEquals("send", json.getString("cmd"))
        assertEquals("41791234567@s.whatsapp.net", json.getString("to"))
        assertEquals("Hallo", json.getString("text"))
    }

    @Test
    fun `a reaction says whose message it answers`() {
        val json = JSONObject(
            WhatsAppBridgeProtocol.reactCommand("a@s.whatsapp.net", "❤️", "3EB0", fromMe = false)
        )

        assertEquals("react", json.getString("cmd"))
        assertEquals("❤️", json.getString("emoji"))
        assertEquals("3EB0", json.getString("messageId"))
        assertFalse(json.getBoolean("fromMe"))
        assertFalse(json.has("participant"))
    }

    @Test
    fun `a reaction with no participant produces JSON with no participant key`() {
        val jsonDefault = JSONObject(
            WhatsAppBridgeProtocol.reactCommand("a@s.whatsapp.net", "❤️", "3EB0", fromMe = false)
        )
        assertFalse(jsonDefault.has("participant"))

        val jsonNull = JSONObject(
            WhatsAppBridgeProtocol.reactCommand("a@s.whatsapp.net", "❤️", "3EB0", fromMe = false, participant = null)
        )
        assertFalse(jsonNull.has("participant"))

        val jsonBlank = JSONObject(
            WhatsAppBridgeProtocol.reactCommand("a@s.whatsapp.net", "❤️", "3EB0", fromMe = false, participant = "   ")
        )
        assertFalse(jsonBlank.has("participant"))
    }

    @Test
    fun `a reaction with a participant produces JSON with the participant present and correct`() {
        val json = JSONObject(
            WhatsAppBridgeProtocol.reactCommand(
                toJid = "1234567890-1234567890@g.us",
                emoji = "❤️",
                messageId = "3EB0",
                fromMe = false,
                participant = "41791234567@s.whatsapp.net"
            )
        )

        assertEquals("react", json.getString("cmd"))
        assertEquals("1234567890-1234567890@g.us", json.getString("to"))
        assertEquals("❤️", json.getString("emoji"))
        assertEquals("3EB0", json.getString("messageId"))
        assertFalse(json.getBoolean("fromMe"))
        assertEquals("41791234567@s.whatsapp.net", json.getString("participant"))
    }

    @Test
    fun `a message containing a quote or a newline survives the pipe`() {
        // Built and parsed with the same JSON library the bridge uses on the other side, so this
        // is the round trip that matters: she writes what she likes and it arrives unchanged.
        val awkward = "Sie sagte \"ja\"\nund ging {nach} Hause \\ 100%"
        val command = WhatsAppBridgeProtocol.sendCommand("a@s.whatsapp.net", awkward)

        assertEquals(awkward, JSONObject(command).getString("text"))
    }

    @Test
    fun `a group arrives with a name to put on her screen`() {
        val event = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"group","jid":"1234567890-1234567890@g.us","name":"Familie","participants":6}"""
        )

        assertEquals(
            WhatsAppEvent.Group("1234567890-1234567890@g.us", "Familie", 6),
            event
        )
    }

    @Test
    fun `a group arrives with the people in it`() {
        // Sent since 2026-09-20: the phone draws a group as its members, and without them it drew
        // everybody who had ever written — including whoever had left.
        val event = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"group","jid":"1234567890-1234567890@g.us","name":"Familie",""" +
                """"participants":3,"members":["41791234567@s.whatsapp.net","2345678901@lid"]}"""
        ) as WhatsAppEvent.Group

        assertEquals(
            listOf("41791234567@s.whatsapp.net", "2345678901@lid"),
            event.members
        )
    }

    @Test
    fun `a group from a bridge that does not send members is still a group`() {
        // The bridge on the phone is updated by an APK and the app by the same APK, but a line
        // that has been sitting in the pipe is older than both.
        val event = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"group","jid":"1234567890-1234567890@g.us","name":"Familie","participants":6}"""
        ) as WhatsAppEvent.Group

        assertEquals(emptyList<String>(), event.members)
    }

    @Test
    fun `something that is not a group address is not a group`() {
        // A person's JID arriving as a group would put a group name on a private conversation
        // and offer a reply that goes to the wrong place.
        assertNull(
            WhatsAppBridgeProtocol.parse(
                """BRIDGE-JSON: {"type":"group","jid":"41791234567@s.whatsapp.net","name":"Anna"}"""
            )
        )
        assertNull(WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"type":"group","name":"Familie"}"""))
    }

    @Test
    fun `a group with no name yet still arrives`() {
        // Named later by events.GroupInfo. An unnamed group is better than no group: the app can
        // still tell that a message came from several people rather than from one.
        val event = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"group","jid":"1-2@g.us"}"""
        ) as WhatsAppEvent.Group

        assertEquals("", event.name)
        assertEquals(0, event.participants)
    }

    @Test
    fun `a code that went stale is told apart from a code that worked`() {
        // The failure this whole event exists for: whatsmeow will not reconnect while unpaired
        // (autoReconnect returns early when Store.ID is nil), so the socket a code was issued on
        // dies while somebody is still fighting WhatsApp's fingerprint prompt.
        assertEquals(
            WhatsAppEvent.PairingExpired,
            WhatsAppBridgeProtocol.parse("""BRIDGE-JSON: {"type":"pairing_expired"}""")
        )
        assertEquals(
            WhatsAppEvent.Paired("41791234567@s.whatsapp.net"),
            WhatsAppBridgeProtocol.parse(
                """BRIDGE-JSON: {"type":"paired","jid":"41791234567@s.whatsapp.net"}"""
            )
        )
    }

    @Test
    fun `a message with quote fields parses them into Incoming event`() {
        val line = """BRIDGE-JSON: {"type":"message","messageId":"3EB0","sender":""" +
            """"41791234567@s.whatsapp.net","chat":"41791234567@s.whatsapp.net","isGroup":false,""" +
            """"pushName":"Anna","text":"Ja, gerne!","timestamp":1786200000000,"direction":"in",""" +
            """"quotedId":"STANZA_99","quotedSender":"41799999999@s.whatsapp.net","quotedText":"Kommst du am Sonntag zum Essen?"}"""

        val event = WhatsAppBridgeProtocol.parse(line) as WhatsAppEvent.Incoming

        assertEquals("3EB0", event.messageId)
        assertEquals("Ja, gerne!", event.text)
        assertEquals("STANZA_99", event.quotedId)
        assertEquals("41799999999@s.whatsapp.net", event.quotedSender)
        assertEquals("Kommst du am Sonntag zum Essen?", event.quotedText)
    }

    @Test
    fun `a message without quote fields defaults them to null`() {
        val line = """BRIDGE-JSON: {"type":"message","messageId":"3EB0","sender":""" +
            """"41791234567@s.whatsapp.net","chat":"41791234567@s.whatsapp.net","text":"Hallo"}"""

        val event = WhatsAppBridgeProtocol.parse(line) as WhatsAppEvent.Incoming

        assertNull(event.quotedId)
        assertNull(event.quotedSender)
        assertNull(event.quotedText)
    }

    @Test
    fun `an avatar event parses jid and local path`() {
        val line = """BRIDGE-JSON: {"type":"avatar","jid":"41791234567@s.whatsapp.net","path":"/data/x/avatars/41791234567.jpg"}"""
        val event = WhatsAppBridgeProtocol.parse(line)

        assertEquals(
            WhatsAppEvent.Avatar("41791234567@s.whatsapp.net", "/data/x/avatars/41791234567.jpg"),
            event
        )

        val profilePicLine = """BRIDGE-JSON: {"type":"profile_picture","jid":"41791234567@s.whatsapp.net","path":"/data/x/avatars/41791234567.jpg"}"""
        val event2 = WhatsAppBridgeProtocol.parse(profilePicLine)
        assertEquals(
            WhatsAppEvent.Avatar("41791234567@s.whatsapp.net", "/data/x/avatars/41791234567.jpg"),
            event2
        )
    }

    @Test
    fun `avatar command creates valid json payload`() {
        val command = WhatsAppBridgeProtocol.avatarCommand("41791234567@s.whatsapp.net")
        val json = JSONObject(command)

        assertEquals("avatar", json.getString("cmd"))
        assertEquals("41791234567@s.whatsapp.net", json.getString("jid"))
    }

    @Test
    fun `a call that starts ringing`() {
        val line = """BRIDGE-JSON: {"type":"call","state":"ringing","callId":"A1B2","from":""" +
            """"41791234567@s.whatsapp.net","timestamp":1786200000000,"reason":"","fromMe":false,"group":false}"""

        val event = WhatsAppBridgeProtocol.parse(line)

        assertTrue(event is WhatsAppEvent.Call)
        event as WhatsAppEvent.Call
        assertEquals(CallState.RINGING, event.state)
        assertEquals("A1B2", event.callId)
        assertEquals("41791234567@s.whatsapp.net", event.callerJid)
        assertFalse(event.fromMe)
        assertFalse(event.group)
    }

    @Test
    fun `a call that ends carries WhatsApp's own word for why`() {
        val line = """BRIDGE-JSON: {"type":"call","state":"ended","callId":"A1B2","from":""" +
            """"41791234567@s.whatsapp.net","timestamp":1786200045000,"reason":"timeout"}"""

        val event = WhatsAppBridgeProtocol.parse(line)

        assertTrue(event is WhatsAppEvent.Call)
        event as WhatsAppEvent.Call
        assertEquals(CallState.ENDED, event.state)
        assertEquals("timeout", event.reason)
    }

    @Test
    fun `a call she placed herself says so`() {
        val line = """BRIDGE-JSON: {"type":"call","state":"ringing","callId":"C3","from":""" +
            """"41790000000@s.whatsapp.net","timestamp":1786200000000,"fromMe":true}"""

        val event = WhatsAppBridgeProtocol.parse(line)

        assertTrue((event as WhatsAppEvent.Call).fromMe)
    }

    @Test
    fun `a call naming nobody, no call, or a state this build has no use for is nothing`() {
        val noCaller = """BRIDGE-JSON: {"type":"call","state":"ringing","callId":"A1B2"}"""
        val noId = """BRIDGE-JSON: {"type":"call","state":"ringing","from":"41791234567@s.whatsapp.net"}"""
        val unknownState = """BRIDGE-JSON: {"type":"call","state":"relaylatency","callId":"A1B2",""" +
            """"from":"41791234567@s.whatsapp.net"}"""

        assertNull(WhatsAppBridgeProtocol.parse(noCaller))
        assertNull(WhatsAppBridgeProtocol.parse(noId))
        assertNull(WhatsAppBridgeProtocol.parse(unknownState))
    }

    @Test
    fun `turning a call away names only the call`() {
        val json = JSONObject(WhatsAppBridgeProtocol.rejectCallCommand("CALL-1"))

        assertEquals("reject_call", json.getString("cmd"))
        assertEquals("CALL-1", json.getString("callId"))
        // The bridge supplies the creator from the offer itself; an address from here could be
        // the resolved number rather than the hidden id the offer came from.
        assertFalse(json.has("to"))
    }

    @Test
    fun `a new network's dns servers go across as --dns does`() {
        val json = JSONObject(WhatsAppBridgeProtocol.dnsCommand(listOf("213.55.128.104", "fe80::1%wlan0")))

        assertEquals("dns", json.getString("cmd"))
        // Comma-separated, the one format the bridge already parses for --dns.
        assertEquals("213.55.128.104,fe80::1%wlan0", json.getString("servers"))
    }

    @Test
    fun `an answer is matched to its request by name, not by who it was for`() {
        val sentForPhoto = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"sent","to":"41791234567@s.whatsapp.net","messageId":"A","timestamp":1,"ref":"photo"}"""
        )!!
        val failedForText = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"send_failed","to":"41791234567@s.whatsapp.net","message":"x","ref":"text"}"""
        )!!

        // The photo going out to the same person says nothing about the text behind it.
        assertNull(WhatsAppBridgeProtocol.answers(sentForPhoto, "text"))
        assertEquals(true, WhatsAppBridgeProtocol.answers(sentForPhoto, "photo"))
        assertEquals(false, WhatsAppBridgeProtocol.answers(failedForText, "text"))
    }

    @Test
    fun `an answer without a name answers nothing`() {
        val event = WhatsAppBridgeProtocol.parse(
            """BRIDGE-JSON: {"type":"sent","to":"41791234567@s.whatsapp.net","messageId":"A","timestamp":1}"""
        )!!

        assertNull(WhatsAppBridgeProtocol.answers(event, "text"))
    }

    @Test
    fun `every send command carries its request's name`() {
        val to = "41791234567@s.whatsapp.net"
        assertEquals("r1", JSONObject(WhatsAppBridgeProtocol.sendCommand(to, "Hallo", ref = "r1")).getString("ref"))
        assertEquals("r2", JSONObject(WhatsAppBridgeProtocol.sendMediaCommand(to, "/p.jpg", "image/jpeg", ref = "r2")).getString("ref"))
        assertEquals("r3", JSONObject(WhatsAppBridgeProtocol.reactCommand(to, "❤️", "M", false, ref = "r3")).getString("ref"))
        assertFalse(JSONObject(WhatsAppBridgeProtocol.sendCommand(to, "Hallo")).has("ref"))
    }
}
