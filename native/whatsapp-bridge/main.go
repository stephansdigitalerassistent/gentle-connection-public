// Command whatsapp-bridge links this phone to WhatsApp as a companion device and speaks JSON
// over stdio, so that the Kotlin side never has to know what a JID is.
//
// It is shipped, launched and driven exactly like the Threema bridge next door: cross-compiled
// for aarch64, packed into the APK as `libwhatsapp_bridge.so`, extracted by the installer into
// the app's native library directory — the one place Android still allows an app to execute a
// file it shipped — and started with ProcessBuilder. Everything it needs lives in app-private
// storage and nothing it holds is ever passed in argv, for the same reason as there: a process's
// command line is world-readable through /proc and its session is the whole of her identity on
// WhatsApp.
//
// Why Go rather than more Rust: whatsmeow is the only maintained implementation of the WhatsApp
// multi-device protocol outside Meta's own clients, and the session storage it wants is SQLite
// through modernc's pure-Go driver — which means no CGO, and therefore a cross-compile that has
// no NDK sysroot to get wrong.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"go.mau.fi/whatsmeow"
	waBinary "go.mau.fi/whatsmeow/binary"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"

	_ "modernc.org/sqlite"
)

// Every line the Kotlin side is meant to read carries this prefix. Anything without it is log
// noise and is ignored there, which keeps a stray library print from being parsed as an event.
const jsonPrefix = "BRIDGE-JSON: "

// What she is shown in place of something this app cannot display. German, because her phone is
// German and these strings reach her screen unchanged — the app does not translate them.
//
// A photograph she cannot see is still a photograph somebody sent her: "[Foto]" tells her that
// her daughter wrote, and that there is something to ask about. Dropping the message entirely
// would make the sender vanish, which is the failure this app avoids everywhere else.
var mediaPlaceholders = map[string]string{
	"image":    "[Foto]",
	"video":    "[Video]",
	"audio":    "[Sprachnachricht]",
	"document": "[Dokument]",
	"sticker":  "[Sticker]",
	"location": "[Standort]",
	"contact":  "[Kontakt]",
}

// Where downloaded attachments are written, and how many are kept.
//
// Three kinds earn the disk and the bandwidth, for three different reasons.
//
// A photograph is the message. Her daughter sending a picture of the garden is not "[Foto]".
//
// A voice message needs no reading at all, which on a phone for somebody who is losing her
// sight makes it the most valuable thing WhatsApp carries. It was the emptiest before this:
// "[Sprachnachricht]" is a rendering of somebody's actual voice as four bracketed syllables.
//
// A video is fetched twice over, and the order matters more than either half. The still that
// WhatsApp packs inside the message itself costs no network at all, so it is written
// immediately and is on her screen with the words; the film is downloaded behind it and shows
// up as a control under the picture whenever it lands, which on a bad connection is minutes
// later and for expired media is never. Doing it the other way round — waiting for the film so
// that the message arrives complete — would trade the picture she reliably gets for a film she
// sometimes gets.
//
// The budget is deliberately small. Her phone holds four years of history, most of it other
// people's holiday photographs, and filling its storage would break the app in a way nobody
// would connect to WhatsApp. Newest wins: when a budget is exceeded the oldest files go, and a
// message whose attachment has been pruned falls back to showing its text, which is what it
// showed before this existed.
const (
	mediaDirName  = "media"
	maxMediaBytes = 8 << 20 // Anything larger is not a snapshot; it is somebody's film.
	// And a film is allowed to be one, within reason. A minute of what her family actually
	// sends — a grandchild in a garden, filmed on a telephone and compressed by WhatsApp on the
	// way — is a few tens of megabytes. Past this it is somebody's holiday in full, which is
	// not worth the mobile data of a woman who does not know she is paying for it.
	maxVideoBytes = 48 << 20
	// Voice messages are small — a minute of Opus is well under a megabyte — and they are worth
	// more per byte than anything else here, so they are allowed to be older before pruning by
	// being counted against the same budget as everything else.
	maxMediaFiles = 400
	// The second budget, and the one films made necessary. Counting files was enough while
	// everything here was a photograph or a few seconds of speech; four hundred of those is a
	// few hundred megabytes at worst. Four hundred films is not — it is most of the storage of
	// her phone, taken by an app she thinks of as the telephone. Whichever budget bites first
	// wins.
	maxMediaDiskBytes = 512 << 20
	maxMediaFetchers  = 3 // Concurrent downloads. History arrives in one burst; this paces it.

	// What the Kotlin side switches on. A picture is drawn; a voice message gets a play control;
	// a film gets one that opens it on the whole screen.
	kindPhoto = "photo"
	kindVoice = "voice"
	kindVideo = "video"

	avatarDirName = "avatars"
)

var (
	mediaDir       string
	mediaFetch     = make(chan struct{}, maxMediaFetchers)
	mediaPruneMu   sync.Mutex
	avatarDir      string
	avatarMu       sync.Mutex
	avatarFetching = map[string]bool{}

	// What the app was last told about each group and each picture — see [alreadySaid].
	saidMu sync.Mutex
	said   = map[string]string{}

	// When the list of her groups may next be asked for — see [groupListDue].
	groupListMu   sync.Mutex
	groupListNext time.Time

	// When the socket last came up, in Unix nanoseconds; zero while it is down.
	connectedSince atomic.Int64
)

type outEvent map[string]any

var (
	stdoutMu sync.Mutex
	out      = bufio.NewWriter(os.Stdout)

	// Whether a pairing code has been issued and not yet used. Only read and written from the
	// event goroutine and the stdin reader, both of which are serialised by this mutex.
	pairingMu        sync.Mutex
	pairingRequested bool
)

// emit writes one event. Serialised, because the event handler runs on whatsmeow's goroutines
// and two half-written JSON lines interleaved is a parse error on the other side that looks
// exactly like a protocol bug.
func emit(e outEvent) {
	stdoutMu.Lock()
	defer stdoutMu.Unlock()
	encoded, err := json.Marshal(e)
	if err != nil {
		return
	}
	fmt.Fprint(out, jsonPrefix)
	out.Write(encoded)
	fmt.Fprintln(out)
	out.Flush()
}

// note writes a diagnostic line to stderr.
//
// Safe to log, and deliberately the only thing that is. whatsmeow's own logger is waLog.Noop
// precisely because it prints message text and JIDs, and that is her social graph going into
// logcat on a phone anybody might pick up. These notes carry protocol state and nothing else:
// no addresses, no names, no message content, ever.
func note(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "[wa] "+format+"\n", args...)
}

func emitError(stage, message string) {
	emit(outEvent{"type": "error", "stage": stage, "message": message})
}

// command is one line of stdin: what the Kotlin side wants done.
type command struct {
	Cmd   string `json:"cmd"`
	To    string `json:"to"`
	Text  string `json:"text"`
	Emoji string `json:"emoji"`
	// The message being answered, as this bridge reported it. Required for a real reaction;
	// without it a heart can only be sent as a text message.
	MessageID string `json:"messageId"`
	// The sender of the message being reacted to. Required in a group because the key must name
	// whose message it is; omitted for a 1:1 reaction.
	Participant string `json:"participant"`
	FromMe      bool   `json:"fromMe"`
	Phone       string `json:"phone"`
	JID         string `json:"jid"`
	// An attachment of hers being sent on: where it is on this phone, and what it is. Both are
	// the app's to decide — it is the app that wrote the file in the first place.
	Path string `json:"path"`
	Mime string `json:"mime"`
	// The call to turn away, by the id this bridge reported it under. The rest of what rejecting
	// needs is the bridge's own memory of the offer — see [ringing].
	CallID string `json:"callId"`
	// The DNS servers of the network the phone has just moved to, comma-separated as --dns is.
	Servers string `json:"servers"`
	// The app's name for this one request, echoed on the "sent" or "send_failed" that answers it.
	// Matching an answer by recipient alone let a photo's or a reaction's "sent" be taken for the
	// text queued behind it, so a text that later failed had already been reported as gone.
	Ref string `json:"ref"`
}

// How long one send may take before it is reported as failed. Without a limit, a send stuck on a
// dead socket held its recipient's queue for good.
const (
	sendTimeout      = 60 * time.Second
	sendMediaTimeout = 5 * time.Minute
)

// sendQueues runs sends one recipient at a time, in the order they were asked for, and every
// recipient independently of the others.
//
// The commands used to run on the stdin reader itself, so a film being uploaded for one person
// held up everything behind it: a text to somebody else, turning away a ringing call, a change of
// network. Order still matters within one conversation — a photo and the sentence typed under it
// must arrive in that order — so each recipient has a queue of its own.
type sendQueues struct {
	mu     sync.Mutex
	queues map[string]chan func()
}

// sendQueueDepth bounds how much may wait behind one recipient. A full queue is reported as a
// failure rather than blocking the reader, which is the thing this type exists to keep free.
const sendQueueDepth = 64

func newSendQueues() *sendQueues {
	return &sendQueues{queues: map[string]chan func(){}}
}

// enqueue runs job after every earlier job for the same key. False when that queue is full.
func (s *sendQueues) enqueue(key string, job func()) bool {
	s.mu.Lock()
	q, ok := s.queues[key]
	if !ok {
		q = make(chan func(), sendQueueDepth)
		s.queues[key] = q
		go func() {
			for run := range q {
				run()
			}
		}()
	}
	s.mu.Unlock()

	select {
	case q <- job:
		return true
	default:
		return false
	}
}

var sends = newSendQueues()

// sendResult is the answer to one send: "sent" or "send_failed", always carrying the request's ref.
func sendResult(cmd command, fields outEvent) outEvent {
	fields["to"] = cmd.To
	fields["ref"] = cmd.Ref
	return fields
}

// queueSend puts one send behind the earlier ones to the same recipient, under its own deadline.
func queueSend(ctx context.Context, cmd command, timeout time.Duration, send func(context.Context, command)) {
	queued := sends.enqueue(cmd.To, func() {
		sendCtx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		send(sendCtx, cmd)
	})
	if !queued {
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": "too many sends waiting"}))
	}
}

func main() {
	storePath := flag.String("store", "", "path to the session database (app-private)")
	displayName := flag.String("display-name", "gentle connection", "name shown in WhatsApp's linked devices list")
	dnsServers := flag.String("dns", "", "comma-separated DNS server addresses for this network")
	flag.Parse()

	configureResolver(*dnsServers)

	if strings.TrimSpace(*storePath) == "" {
		emitError("startup", "--store is required")
		os.Exit(2)
	}

	// Beside the session database, so it inherits app-private storage rather than being given it.
	// Everything under files/ is readable by this uid alone; a photograph her family sent must not
	// be one directory move away from the shared card.
	mediaDir = filepath.Join(filepath.Dir(*storePath), mediaDirName)
	if err := os.MkdirAll(mediaDir, 0o700); err != nil {
		// Not fatal. Without a media directory the app is exactly what it was yesterday: text and
		// placeholders. Losing photographs is worth reporting; refusing to run is not.
		note("no media directory: %v", err)
		mediaDir = ""
	}

	avatarDir = filepath.Join(filepath.Dir(*storePath), avatarDirName)
	if err := os.MkdirAll(avatarDir, 0o700); err != nil {
		note("no avatar directory: %v", err)
		avatarDir = ""
	}

	ctx := context.Background()
	// The store keeps the silent logger; the client gets one that passes on warnings and errors
	// with everything personal taken out — see [cautiousLog].
	logger := waLog.Noop

	// `_pragma=foreign_keys(1)` matters: whatsmeow's schema relies on cascade deletes to clear a
	// session out, and SQLite has foreign keys off by default. Without it, unlinking leaves rows
	// behind that a later link trips over.
	address := fmt.Sprintf("file:%s?_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)", *storePath)
	container, err := sqlstore.New(ctx, "sqlite", address, logger)
	if err != nil {
		emitError("store", err.Error())
		os.Exit(1)
	}

	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		emitError("store", err.Error())
		os.Exit(1)
	}

	store.DeviceProps.Os = proto.String(*displayName)
	askForTheWholeHistory()
	client := whatsmeow.NewClient(device, cautiousLog{})
	takeDetourIfAsked(client, *storePath)
	answerServerQuestions(client)

	client.AddEventHandler(func(evt any) { handleEvent(client, evt) })

	// Two states, and the difference decides everything the Kotlin side does next. A device with
	// an ID has been linked before and should just connect; one without needs a pairing code,
	// which cannot be requested until the socket is up.
	linked := client.Store.ID != nil
	emit(outEvent{"type": "state", "linked": linked})

	note("connecting, linked=%v", linked)
	if err := client.Connect(); err != nil {
		emitError("connect", err.Error())
		os.Exit(1)
	}

	go readCommands(ctx, client)
	go keepTheLinkAlive(ctx, client)

	// Nothing to do on the main goroutine but stay alive: the work happens on whatsmeow's event
	// handlers and on the stdin reader. When stdin closes — the app was killed, or the parent
	// process went away — readCommands exits the process, which is the only shutdown path.
	select {}
}

// readCommands drives the bridge from stdin, one JSON object per line.
func readCommands(ctx context.Context, client *whatsmeow.Client) {
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		var cmd command
		if err := json.Unmarshal([]byte(line), &cmd); err != nil {
			emitError("command", "unparseable command")
			continue
		}

		switch cmd.Cmd {
		case "pair":
			requestPairingCode(ctx, client, cmd.Phone)
		// Sends go to a queue per recipient rather than running here, so that a film still
		// uploading cannot hold up a ringing call being turned away or a text to somebody else.
		case "send":
			queueSend(ctx, cmd, sendTimeout, func(c context.Context, cmd command) { sendText(c, client, cmd) })
		case "send_media":
			queueSend(ctx, cmd, sendMediaTimeout, func(c context.Context, cmd command) { sendMedia(c, client, cmd) })
		case "react":
			queueSend(ctx, cmd, sendTimeout, func(c context.Context, cmd command) { sendReaction(c, client, cmd) })
		case "reject_call":
			rejectCall(ctx, client, cmd)
		case "dns":
			changeDNSServers(client, cmd.Servers)
		case "avatar", "profile_picture":
			target := cmd.To
			if target == "" {
				target = cmd.Phone
			}
			if target == "" {
				target = cmd.JID
			}
			if target != "" {
				if parsed, err := types.ParseJID(target); err == nil {
					fetchAvatar(ctx, client, parsed)
				} else if digits := onlyDigits(target); len(digits) >= 6 {
					fetchAvatar(ctx, client, types.NewJID(digits, types.DefaultUserServer))
				}
			}
		case "logout":
			if err := client.Logout(ctx); err != nil {
				emitError("logout", err.Error())
				break
			}
			// Say so ourselves. whatsmeow dispatches events.LoggedOut only when the *server*
			// ends the session; a logout we asked for returns quietly, having already cleared
			// the store and disconnected. Without this line the app never learns, so the
			// caregiver dialog goes on saying "linked and connected" about a session that no
			// longer exists — and the caregiver presses Unlink again, and again.
			note("logged out on request")
			emit(outEvent{"type": "logged_out", "reason": "unlinked"})
			// Logout closes the socket, and closing it deliberately raises no events.Disconnected,
			// so the usual unpaired-reconnect path is never triggered. Without this the process
			// sits there with no connection and PairPhone has nothing to ask, which turns
			// "unlink, then link again" — the one repair a caregiver can actually perform — into
			// a restart of the app.
			go reconnectWhileUnpaired(client)
		default:
			emitError("command", "unknown command: "+cmd.Cmd)
		}
	}

	// stdin closed. The parent is gone, so this process has nobody to report to and no reason to
	// keep a socket open on her account.
	client.Disconnect()
	os.Exit(0)
}

// reconnectWhileUnpaired keeps a socket open until somebody has had time to use the code.
//
// Gives up once paired — from then on whatsmeow's own reconnection takes over, which works
// because Store.ID is no longer nil.
func reconnectWhileUnpaired(client *whatsmeow.Client) {
	for attempt := 0; attempt < unpairedReconnectAttempts; attempt++ {
		time.Sleep(unpairedReconnectDelay)

		if client.Store.ID != nil || client.IsConnected() {
			note("reconnect not needed: paired=%v connected=%v", client.Store.ID != nil, client.IsConnected())
			return
		}
		if err := client.Connect(); err != nil {
			note("reconnect attempt %d failed: %v", attempt, err)
			continue
		}
		note("reconnected while unpaired after %d attempts", attempt+1)

		// The code issued before this reconnection is dead: it was bound to the socket that
		// went away. Saying so is the difference between "request a new code" and a caregiver
		// typing an eight-character code that cannot possibly work, twice, and concluding the
		// app is broken.
		pairingMu.Lock()
		expired := pairingRequested
		pairingRequested = false
		pairingMu.Unlock()
		if expired {
			emit(outEvent{"type": "pairing_expired"})
		}
		return
	}
}

const (
	unpairedReconnectDelay    = 3 * time.Second
	unpairedReconnectAttempts = 20
)

// keepTheLinkAlive stops WhatsApp from logging her phone out for having done nothing.
//
// A linked device is dropped after about a month of inactivity, and "activity" is not the socket.
// This bridge reconnects several times an hour, and the primary phone still said "Last active
// 9 August" a month later — the day the link was made — with a banner that it would be logged out
// that evening. The timestamp comes from a `unified_session` node, which whatsmeow sends in
// exactly two places: when pairing succeeds, and inside SendPresence(available). The bridge never
// sent a presence, so after the handshake WhatsApp never heard from it again, and the link she
// depends on was quietly counting down to nothing.
//
// Available, and then straight back to unavailable, because presence is not only telemetry:
// staying available would show her as online to every contact, for ever, including all night.
// A few seconds of it twice a day is what an ordinary linked device looks like anyway.
func keepTheLinkAlive(ctx context.Context, client *whatsmeow.Client) {
	var announced time.Time

	for {
		if client.IsLoggedIn() && time.Since(announced) >= activityAnnounceInterval {
			if announceActivity(ctx, client) {
				announced = time.Now()
			}
		}
		time.Sleep(activityAnnounceRetry)
	}
}

// announceActivity sends the pair of presences, and says whether the first one — the half that
// carries the timestamp — reached WhatsApp.
func announceActivity(ctx context.Context, client *whatsmeow.Client) bool {
	if err := client.SendPresence(ctx, types.PresenceAvailable); err != nil {
		// Nobody at her phone can act on this and the next attempt is a minute away, so it is a
		// note rather than an error on the caregiver log. ErrNoPushName is the likely one, and
		// it cures itself as soon as WhatsApp has told us what she is called.
		note("activity not announced: %v", err)
		return false
	}

	time.Sleep(activityOnlineWindow)

	if err := client.SendPresence(ctx, types.PresenceUnavailable); err != nil {
		// Worth a line, because the failure that matters is the one that leaves her showing as
		// online until the socket drops.
		note("presence not withdrawn: %v", err)
	}
	note("announced activity")
	return true
}

const (
	activityAnnounceInterval = 12 * time.Hour
	activityAnnounceRetry    = time.Minute
	activityOnlineWindow     = 5 * time.Second
)

func requestPairingCode(ctx context.Context, client *whatsmeow.Client, phone string) {
	digits := onlyDigits(phone)
	if len(digits) < 6 {
		emitError("pair", "a telephone number in international form is required")
		return
	}

	code, err := client.PairPhone(ctx, digits, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		emitError("pair", err.Error())
		return
	}
	// The code is the whole of the linking secret and it is short-lived. It goes to the caregiver
	// dialog on the phone and nowhere else — never to logcat, never to Firestore.
	pairingMu.Lock()
	pairingRequested = true
	pairingMu.Unlock()
	note("pairing code issued, connected=%v", client.IsConnected())
	emit(outEvent{"type": "pairing_code", "code": code})
}

func sendText(ctx context.Context, client *whatsmeow.Client, cmd command) {
	jid, err := types.ParseJID(cmd.To)
	if err != nil {
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": "unusable recipient"}))
		return
	}
	if strings.TrimSpace(cmd.Text) == "" {
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": "refusing to send an empty message"}))
		return
	}

	msg := &waE2E.Message{Conversation: proto.String(cmd.Text)}
	// The app names its own messages. Without that it cannot tell, later, which row a reaction
	// refers to: WhatsApp reports the id it chose, but only in passing, and the messages people
	// react to are overwhelmingly the ones she wrote.
	var options []whatsmeow.SendRequestExtra
	if cmd.MessageID != "" {
		options = append(options, whatsmeow.SendRequestExtra{ID: types.MessageID(cmd.MessageID)})
	}
	resp, err := client.SendMessage(ctx, jid, msg, options...)
	if err != nil {
		// Reported, never swallowed: the Kotlin side turns this into "it did not go", which is
		// the one thing she is told about a failure.
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": err.Error()}))
		return
	}
	emit(sendResult(cmd, outEvent{"type": "sent", "messageId": resp.ID, "timestamp": resp.Timestamp.UnixMilli()}))
}

// sendMedia sends a photograph, film or voice message that is already on this phone.
//
// The mirror of downloadMedia next door, and the same shape as the Threema bridge's upload: the
// bytes are encrypted and put on WhatsApp's media servers, and the message that goes out names
// what came back. Nothing is sent if the upload fails — a message pointing at a blob that does
// not exist arrives as an attachment the recipient can never open, which is worse for them than
// nothing arriving at all.
//
// What kind of message it becomes is decided by the mime type, because that is what decides how
// it is drawn at the other end: a photograph must arrive as a photograph and not as a file
// somebody has to tap twice to see.
func sendMedia(ctx context.Context, client *whatsmeow.Client, cmd command) {
	jid, err := types.ParseJID(cmd.To)
	if err != nil {
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": "unusable recipient"}))
		return
	}

	data, err := os.ReadFile(cmd.Path)
	if err != nil || len(data) == 0 {
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": "nothing to send"}))
		return
	}

	mime := strings.TrimSpace(cmd.Mime)
	if mime == "" {
		mime = "application/octet-stream"
	}

	var mediaType whatsmeow.MediaType
	switch {
	case strings.HasPrefix(mime, "image/"):
		mediaType = whatsmeow.MediaImage
	case strings.HasPrefix(mime, "video/"):
		mediaType = whatsmeow.MediaVideo
	case strings.HasPrefix(mime, "audio/"):
		mediaType = whatsmeow.MediaAudio
	default:
		mediaType = whatsmeow.MediaDocument
	}

	uploaded, err := client.Upload(ctx, data, mediaType)
	if err != nil {
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": err.Error()}))
		return
	}

	length := uint64(len(data))
	var msg *waE2E.Message
	switch mediaType {
	case whatsmeow.MediaImage:
		msg = &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
			Mimetype:      proto.String(mime),
			URL:           proto.String(uploaded.URL),
			DirectPath:    proto.String(uploaded.DirectPath),
			MediaKey:      uploaded.MediaKey,
			FileEncSHA256: uploaded.FileEncSHA256,
			FileSHA256:    uploaded.FileSHA256,
			FileLength:    proto.Uint64(length),
		}}
	case whatsmeow.MediaVideo:
		msg = &waE2E.Message{VideoMessage: &waE2E.VideoMessage{
			Mimetype:      proto.String(mime),
			URL:           proto.String(uploaded.URL),
			DirectPath:    proto.String(uploaded.DirectPath),
			MediaKey:      uploaded.MediaKey,
			FileEncSHA256: uploaded.FileEncSHA256,
			FileSHA256:    uploaded.FileSHA256,
			FileLength:    proto.Uint64(length),
		}}
	case whatsmeow.MediaAudio:
		// PTT: somebody talking, drawn as a voice message with a play button rather than as a
		// music file. Everything this app forwards as audio is somebody talking — it is what
		// arrived as a voice message in the first place.
		msg = &waE2E.Message{AudioMessage: &waE2E.AudioMessage{
			Mimetype:      proto.String(mime),
			URL:           proto.String(uploaded.URL),
			DirectPath:    proto.String(uploaded.DirectPath),
			MediaKey:      uploaded.MediaKey,
			FileEncSHA256: uploaded.FileEncSHA256,
			FileSHA256:    uploaded.FileSHA256,
			FileLength:    proto.Uint64(length),
			PTT:           proto.Bool(true),
		}}
	default:
		msg = &waE2E.Message{DocumentMessage: &waE2E.DocumentMessage{
			Mimetype:      proto.String(mime),
			FileName:      proto.String(filepath.Base(cmd.Path)),
			URL:           proto.String(uploaded.URL),
			DirectPath:    proto.String(uploaded.DirectPath),
			MediaKey:      uploaded.MediaKey,
			FileEncSHA256: uploaded.FileEncSHA256,
			FileSHA256:    uploaded.FileSHA256,
			FileLength:    proto.Uint64(length),
		}}
	}

	// The app names its own messages, for the same reason as in sendText: without it a reaction
	// to something she sent names a message the app has no record of.
	var options []whatsmeow.SendRequestExtra
	if cmd.MessageID != "" {
		options = append(options, whatsmeow.SendRequestExtra{ID: types.MessageID(cmd.MessageID)})
	}

	resp, err := client.SendMessage(ctx, jid, msg, options...)
	if err != nil {
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": err.Error()}))
		return
	}
	emit(sendResult(cmd, outEvent{"type": "sent", "messageId": resp.ID, "timestamp": resp.Timestamp.UnixMilli()}))
}

func sendReaction(ctx context.Context, client *whatsmeow.Client, cmd command) {
	jid, err := types.ParseJID(cmd.To)
	if err != nil {
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": "unusable recipient"}))
		return
	}
	if cmd.MessageID == "" {
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": "a reaction needs the message it answers"}))
		return
	}

	key := &waCommon.MessageKey{
		RemoteJID: proto.String(jid.String()),
		FromMe:    proto.Bool(cmd.FromMe),
		ID:        proto.String(cmd.MessageID),
	}
	if jid.Server == types.GroupServer {
		if cmd.Participant == "" {
			emit(sendResult(cmd, outEvent{"type": "send_failed", "message": "a group reaction needs the participant who sent the message"}))
			return
		}
		participantJID, err := types.ParseJID(cmd.Participant)
		if err != nil || participantJID.IsEmpty() {
			emit(sendResult(cmd, outEvent{"type": "send_failed", "message": "unusable participant"}))
			return
		}
		key.Participant = proto.String(participantJID.String())
	}

	msg := &waE2E.Message{
		ReactionMessage: &waE2E.ReactionMessage{
			Key:               key,
			Text:              proto.String(cmd.Emoji),
			SenderTimestampMS: proto.Int64(time.Now().UnixMilli()),
		},
	}

	resp, err := client.SendMessage(ctx, jid, msg)
	if err != nil {
		emit(sendResult(cmd, outEvent{"type": "send_failed", "message": err.Error()}))
		return
	}
	emit(sendResult(cmd, outEvent{"type": "sent", "messageId": resp.ID, "timestamp": resp.Timestamp.UnixMilli()}))
}

func handleEvent(client *whatsmeow.Client, evt any) {
	switch v := evt.(type) {
	case *events.Message:
		emitMessage(client, v)

	case *events.Connected:
		connectedSince.Store(time.Now().UnixNano())
		emit(outEvent{"type": "connected"})
		// The groups she is in, once there is a socket to ask over. Without this a group
		// message arrives addressed to a JID and her screen has no name to put on it — and a
		// JID is not something anybody can recognise as "the family".
		//
		// Not on every connection, though — see [groupListDue].
		if groupListDue(time.Now()) {
			go emitJoinedGroups(client)
		}

	case *events.PairSuccess:
		note("pair success")
		// Possibly a different account from the one linked before: nothing said about the old
		// one counts as said about this one.
		forgetWhatWasSaid()
		pairingMu.Lock()
		pairingRequested = false
		pairingMu.Unlock()
		emit(outEvent{"type": "paired", "jid": v.ID.String()})

	case *events.PairError:
		// Pairing was attempted and refused. Reported rather than swallowed: it is the only
		// thing that distinguishes a wrong code from a code that arrived too late.
		pairingMu.Lock()
		pairingRequested = false
		pairingMu.Unlock()
		emitError("pair", v.Error.Error())

	case *events.Disconnected:
		// Ordinary and constant — see the Threema client, which makes the same distinction. This
		// is not a fault and must not be reported as one, or the caregiver log fills with it.
		emit(outEvent{"type": "disconnected"})
		noteHowLongItLasted()

		// Except before pairing, where it is fatal and silent, and whatsmeow will not help:
		//
		//	func (cli *Client) autoReconnect(ctx context.Context) {
		//		if !cli.EnableAutoReconnect || cli.Store.ID == nil {
		//			return
		//
		// Store.ID is nil until pairing completes, so an unpaired socket that drops stays
		// dropped for ever. That is the whole of why linking kept failing on the phone: the
		// bridge connects when the app starts, then waits — while somebody opens the caregiver
		// dialog, reads a code, opens WhatsApp, gets past a fingerprint prompt and types eight
		// characters. Minutes. The idle unauthenticated socket is closed long before that, and
		// by the time the code is entered there is no connection left to finish the handshake
		// over. WhatsApp says "Logging in…" and then nothing happens, on both sides, for ever.
		note("disconnected, paired=%v", client.Store.ID != nil)
		if client.Store.ID == nil {
			go reconnectWhileUnpaired(client)
		}

	case *events.LoggedOut:
		note("logged out")
		// This one is the fault. The session is gone and cannot come back on its own: somebody
		// has to be at her phone, or at a remote-control session on it, to link again.
		emit(outEvent{"type": "logged_out", "reason": v.Reason.String()})

	case *events.HistorySync:
		// What WhatsApp sends a newly linked companion: recent chats, so a device that has just
		// been linked is not blank. Without it her screen shows nothing until somebody happens
		// to write, which can be days — the app would look broken on the day it was set up.
		go emitHistory(client, v.Data)

	case *events.GroupInfo:
		// A rename, or somebody joining or leaving. Re-read rather than trying to apply the
		// delta: the whole point of a name on her screen is that it is right.
		go emitGroup(client, v.JID)

	case *events.JoinedGroup:
		emitGroupInfo(&v.GroupInfo, ownJID(client), lidLookup(client))

	// Somebody's phone saying a message of ours arrived, was read or (a voice note) was played.
	// Reported, never acted on: see [receiptEvent].
	case *events.Receipt:
		if e := receiptEvent(v, lidLookup(client)); e != nil {
			emit(e)
		}

	// A call. Everything WhatsApp says about one is reported and nothing about one is acted
	// on here: this bridge cannot carry a call's audio, and the app decides what to do about
	// that — see [emitCall].
	case *events.CallOffer:
		ringing.remember(v.BasicCallMeta)
		emitCall(client, "ringing", v.BasicCallMeta, "")

	case *events.CallOfferNotice:
		ringing.remember(v.BasicCallMeta)
		emitCall(client, "ringing", v.BasicCallMeta, "")

	case *events.CallAccept:
		ringing.forget(v.CallID)
		emitCall(client, "answered", v.BasicCallMeta, "")

	case *events.CallTerminate:
		ringing.forget(v.CallID)
		emitCall(client, "ended", v.BasicCallMeta, v.Reason)

	case *events.CallReject:
		ringing.forget(v.CallID)
		emitCall(client, "ended", v.BasicCallMeta, "reject")

	case *events.StreamReplaced:
		emit(outEvent{"type": "logged_out", "reason": "stream_replaced"})

	// Why a connection ended or is about to. None of these is the app's to act on — whatsmeow
	// reconnects by itself — but a socket that drops once a minute for a week (an Android phone on mobile
	// data, found 2026-10-12) could not be explained because nothing here ever said why.
	case *events.StreamError:
		note("stream error code=%q node=%s", scrub(v.Code), nodeShape(v.Raw))

	case *events.ConnectFailure:
		note("connect failure %d: %s", int(v.Reason), scrub(v.Message))

	case *events.TemporaryBan:
		note("temporary ban: %s, for %s", v.Code, v.Expire)

	case *events.ClientOutdated:
		note("client outdated: WhatsApp refuses this version of the protocol")

	case *events.KeepAliveTimeout:
		note("keepalive unanswered, %d in a row, last answer %s ago",
			v.ErrorCount, time.Since(v.LastSuccess).Round(time.Second))

	case *events.KeepAliveRestored:
		note("keepalive answered again")
	}
}

// noteHowLongItLasted records the life of the connection that has just ended. The length is the
// evidence: a socket that always dies at the same age is being cut by something with a timer.
func noteHowLongItLasted() {
	since := connectedSince.Swap(0)
	if since == 0 {
		wire.summary(time.Now()) // a socket that never logged in: its traffic is not the next one's
		return
	}
	note("connection lasted %s", time.Since(time.Unix(0, since)).Round(time.Second))
	note("connection traffic: %s", wire.summary(time.Now()))
}

// nodeShape describes a protocol node by its tags alone: what kind of thing WhatsApp sent, with
// none of the attributes or content, which is where addresses would be.
func nodeShape(node *waBinary.Node) string {
	if node == nil {
		return "none"
	}
	var b strings.Builder
	b.WriteString(node.Tag)
	for _, child := range node.GetChildren() {
		b.WriteString(">" + child.Tag)
	}
	return scrub(b.String())
}

// cautiousLog is whatsmeow's logger for the client: warnings and errors only, as notes, with
// everything that could be personal removed first.
//
// The logger used to be waLog.Noop outright, for the reason given at [note] — whatsmeow prints
// JIDs and whole protocol nodes. That kept her social graph out of logcat and also kept out the
// one line that says why a socket closed. So the levels that describe faults are let through,
// and their arguments are not trusted: see [cautious].
type cautiousLog struct{ module string }

func (l cautiousLog) Warnf(msg string, args ...any)  { l.say("warn", msg, args) }
func (l cautiousLog) Errorf(msg string, args ...any) { l.say("error", msg, args) }
func (l cautiousLog) Infof(string, ...any)           {}
func (l cautiousLog) Debugf(_ string, args ...any) {
	// whatsmeow's Recv and Send loggers are handed every node; only its kind is kept — see [wire].
	if l.module != "Recv" && l.module != "Send" || len(args) == 0 {
		return
	}
	if node, ok := args[0].(*waBinary.Node); ok {
		wire.saw(l.module == "Recv", node, time.Now())
		if l.module == "Recv" && serverAsks != nil {
			serverAsks(node)
		}
	}
}
func (l cautiousLog) Sub(module string) waLog.Logger { return cautiousLog{module: module} }

func (l cautiousLog) say(level, msg string, args []any) {
	where := level
	if l.module != "" {
		where = level + " " + l.module
	}
	note("%s", cautiousLine(where, msg, args))
}

func cautiousLine(where, msg string, args []any) string {
	return "whatsmeow " + where + ": " + fmt.Sprintf(msg, cautious(args)...)
}

// cautious makes a log call's arguments safe to print.
//
// Numbers, durations and times say nothing about anybody and pass unchanged. Text and errors are
// scrubbed — see [scrub]. Everything else is replaced by the name of its type: that is where
// protocol nodes and JIDs arrive, and "a node took too long" is all a log needs of one.
func cautious(args []any) []any {
	safe := make([]any, len(args))
	for i, arg := range args {
		switch v := arg.(type) {
		case nil:
			safe[i] = "nil"
		case error:
			safe[i] = scrub(v.Error())
		case string:
			safe[i] = scrub(v)
		case time.Time, time.Duration:
			safe[i] = v
		default:
			switch reflect.ValueOf(arg).Kind() {
			case reflect.Bool,
				reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
				reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64,
				reflect.Float32, reflect.Float64:
				safe[i] = arg
			default:
				safe[i] = fmt.Sprintf("<%T>", arg)
			}
		}
	}
	return safe
}

var (
	looksLikeAnAddress = regexp.MustCompile(`\S*@\S+`)
	looksLikeANumber   = regexp.MustCompile(`\d{5,}`)
)

// scrubbedLength is as much of one piece of text as a note carries. Long enough for any error
// message, short enough that a stack trace or a dumped node does not arrive whole.
const scrubbedLength = 200

// scrub removes what could identify somebody from a piece of text: anything shaped like an
// address, and any run of digits long enough to be a telephone number or a LID. One line, and
// not much of it.
func scrub(text string) string {
	text = looksLikeAnAddress.ReplaceAllString(text, "<address>")
	text = looksLikeANumber.ReplaceAllString(text, "<number>")
	text = strings.Join(strings.Fields(text), " ")
	if runes := []rune(text); len(runes) > scrubbedLength {
		text = string(runes[:scrubbedLength]) + "…"
	}
	return text
}

// alreadySaid reports whether the app was last told exactly this about that, and remembers it if
// not.
//
// The app is this process's parent and keeps what it is told, so saying the same thing twice
// tells it nothing — and costs a great deal. Every group and every picture used to be reported
// again on every connection, and each report made the home screen redraw every face. On a phone
// whose socket dropped once a minute that was four hours of processor a day (2026-10-12), for
// seventeen groups that had not changed.
func alreadySaid(about, content string) bool {
	saidMu.Lock()
	defer saidMu.Unlock()
	if previous, ok := said[about]; ok && previous == content {
		return true
	}
	said[about] = content
	return false
}

func forgetWhatWasSaid() {
	saidMu.Lock()
	said = map[string]string{}
	saidMu.Unlock()

	groupListMu.Lock()
	groupListNext = time.Time{}
	groupListMu.Unlock()
}

const (
	// How long a list of her groups is good for. Renames, arrivals and departures in between
	// come as events of their own — see events.GroupInfo and events.JoinedGroup — so the list is
	// only a safety net for one that was missed.
	groupListInterval = 6 * time.Hour
	// How soon to ask again after WhatsApp refused. Asking on every connection got
	// "429 rate-overlimit" back about every other time.
	groupListRetry = 10 * time.Minute
)

// groupListDue reports whether her groups should be listed now, and if so books the next retry.
// The caller reports a success with [groupListDone], which pushes the next listing out to the
// full interval.
func groupListDue(now time.Time) bool {
	groupListMu.Lock()
	defer groupListMu.Unlock()
	if now.Before(groupListNext) {
		return false
	}
	groupListNext = now.Add(groupListRetry)
	return true
}

func groupListDone(now time.Time) {
	groupListMu.Lock()
	groupListNext = now.Add(groupListInterval)
	groupListMu.Unlock()
}

// emitJoinedGroups reports every group she is in, so her screen has names rather than addresses.
func emitJoinedGroups(client *whatsmeow.Client) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	groups, err := client.GetJoinedGroups(ctx)
	if err != nil {
		emitError("groups", err.Error())
		return
	}
	groupListDone(time.Now())
	lookup := lidLookup(client)
	me := ownJID(client)
	for _, group := range groups {
		emitGroupInfo(group, me, lookup)
		fetchAvatar(context.Background(), client, group.JID)
	}
}

func emitGroup(client *whatsmeow.Client, jid types.JID) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	info, err := client.GetGroupInfo(ctx, jid)
	if err != nil {
		emitError("groups", err.Error())
		return
	}
	emitGroupInfo(info, ownJID(client), lidLookup(client))
	fetchAvatar(context.Background(), client, info.JID)
}

// emitGroupInfo reports one group: what it is called, how many people are in it, and who they are.
//
// The member list used to be withheld, deliberately. The reasoning was that Threema needs one
// because a group message there is encrypted separately to every member, so replying means
// knowing who they all are, while a WhatsApp group is a real address — one message, delivered by
// WhatsApp to everybody — so the membership is not needed to answer, and a list of everyone she
// talks to is the kind of thing this app tries hardest never to copy anywhere.
//
// It is needed for something else, and that was missed: the phone *draws* a group as the people
// in it. With no member list the faces fell back to everybody who had ever written in the chat,
// which is not the same set — it keeps whoever has since left, for ever, and it leaves out
// whoever has not spoken. She found it from the other end on 2026-09-20 and said so plainly:
// names in group chats that do not belong to the group. Nothing on the phone could fix that,
// because the phone was never told who was in the group.
//
// So the members are sent, by design the same day. What travels is the same address
// the messages themselves already carry, to the same place they already go — the app's own
// database on her phone, which is where her conversations live anyway. Nothing leaves the device
// that was not already on it, and the count stays for the screens that only say "and 5 others".
//
// Telephone numbers by preference, LIDs only where WhatsApp offers nothing else: the phone
// matches people to her address book by number, and a member it cannot place is drawn as somebody
// with no name rather than as an address — see [personJID], which makes the same choice for the
// sender of every message.
func emitGroupInfo(info *types.GroupInfo, own types.JID, lidToPN func(types.JID) (types.JID, bool)) {
	if info == nil {
		return
	}
	event := outEvent{
		"type":         "group",
		"jid":          info.JID.String(),
		"name":         info.GroupName.Name,
		"participants": len(info.Participants),
		"members":      groupMembers(info.Participants, own, lidToPN),
	}
	if encoded, err := json.Marshal(event); err == nil && alreadySaid("group "+info.JID.String(), string(encoded)) {
		return
	}
	emit(event)
}

// groupMembers is everybody in a group, each by the address the phone can place them under.
//
// One entry per person, not per address. A participant arrives with up to three — the one to send
// to, their number and their LID — and the same person can hold two of them; emitted twice, they
// would be drawn as two people and counted as two in "und 5 weitere".
//
// Order is WhatsApp's own and is not sorted here. The phone puts whoever wrote last in front and
// the rest by name, so any order this chose would be thrown away — and a stable one at least
// makes two reads of the same group comparable in the log.
//
// She herself is left out, because the phone draws the members as the people she is writing *to*.
// It is taken out here rather than on the phone for the plain reason that the phone does not know
// her WhatsApp number — nothing on it ever needed to — while the bridge is logged in as her.
func groupMembers(
	participants []types.GroupParticipant,
	own types.JID,
	lidToPN func(types.JID) (types.JID, bool),
) []string {
	mine := ""
	if !own.IsEmpty() {
		mine = own.ToNonAD().String()
	}
	members := make([]string, 0, len(participants))
	seen := make(map[string]struct{}, len(participants))
	for _, participant := range participants {
		address := personJID(participant.JID, participant.PhoneNumber, lidToPN)
		if mine != "" && address == mine {
			continue
		}
		// A JID with no user part is a server name, not a person. WhatsApp has sent one for a
		// participant it could not fill in, and it would be drawn as a member with no face.
		if !strings.Contains(address, "@") {
			continue
		}
		if _, already := seen[address]; already {
			continue
		}
		seen[address] = struct{}{}
		members = append(members, address)
	}
	return members
}

// emitHistory reports everything WhatsApp sends a newly linked companion.
//
// All of it, both directions. The first version of this took only the newest incoming message per
// conversation, on the reasoning that her screen shows a handful of things and the rest would bury
// it — but that reasoning confuses the two screens. Her Messages list does show a handful; her
// conversations show a thread, and a thread with one line in it is not a conversation. Importing
// everything is what makes opening a chat show what was actually said.
//
// Her own messages are included, as they are in live traffic. Leaving them out would show her
// half of every conversation — everyone else's words, and no evidence that she ever answered.
// See MessageDirection, which exists for exactly this reason.
func emitHistory(client *whatsmeow.Client, data *waHistorySync.HistorySync) {
	if data == nil {
		return
	}

	conversations := data.GetConversations()
	total := 0

	for _, conv := range conversations {
		chat := historicChatAddress(conv.GetID(), lidLookup(client))
		if chat == "" {
			continue
		}
		for _, entry := range conv.GetMessages() {
			if emitHistoricMessage(client, chat, entry.GetMessage()) {
				total++
			}
		}
	}

	// Counts only: how many conversations and how many messages. Never what was in them.
	note("history sync: %d conversations, %d messages imported", len(conversations), total)

	// And then ask for what came before it. Already on a goroutine of its own, so this is allowed
	// to take its time — which it does, deliberately.
	requestOlderHistory(client)
}

// askForTheWholeHistory tells WhatsApp, at the moment this phone is linked, to send everything it
// has rather than the recent slice it sends by default.
//
// **Registration-time and only registration-time.** HistorySyncConfig travels in the companion
// registration payload, so it describes what *this link* asked for and cannot be changed
// afterwards. A phone that is already linked keeps whatever depth it was linked with, and the
// only way to widen it is to unlink and link again — which is the whole reason
// [requestOlderHistory] exists as well, for a session that is already running.
//
// The defaults whatsmeow ships are a browser's, and a browser is a second screen for a phone that
// already holds everything. This phone is the only screen. What she looks for is a conversation
// from some weeks ago that she half remembers, so a history that stops a few months back stops in
// the middle of what she is looking for.
func askForTheWholeHistory() {
	config := store.DeviceProps.GetHistorySyncConfig()
	if config == nil {
		// A whatsmeow that no longer ships one. Nothing to widen, and building a config here
		// would mean carrying the proto type for a case that has never happened.
		return
	}

	store.DeviceProps.RequireFullSync = proto.Bool(true)
	// Ten years rather than "no limit": the field is a number, and a number is what WhatsApp is
	// entitled to clamp. Comfortably past anything her account holds.
	config.FullSyncDaysLimit = proto.Uint32(3650)
	// Thumbnails as far back as the messages. Sixty days was the default, and this is the field
	// that decides whether a photograph from last winter arrives as a picture or as the bare word
	// "[Foto]" — which for somebody who is losing her sight is most of the message.
	config.ThumbnailSyncDaysLimit = proto.Uint32(3650)
	// Whether WhatsApp will answer a request for messages older than what it sent unprompted.
	// Asked for here so that a phone linked by this build can be deepened afterwards without
	// being unlinked; [requestOlderHistory] is what does the asking.
	config.OnDemandReady = proto.Bool(true)
	config.CompleteOnDemandReady = proto.Bool(true)
}

const (
	// How many older messages to ask for, per conversation, per round. WhatsApp answers an
	// on-demand request with one chunk; asking for a thousand at once is not faster, it is
	// refused.
	onDemandChunk = 50
	// A hard stop. Each round is a peer message per conversation and a history sync coming back,
	// and a bridge that kept asking for ever would be a background process on her phone doing
	// work nobody asked for. Fifty rounds of fifty is two and a half thousand messages per
	// conversation, which is past what four years of family WhatsApp holds.
	maxBackfillRounds = 50
	// Between requests, so that a hundred conversations do not become a hundred simultaneous peer
	// messages on a mobile connection.
	backfillSpacing = 400 * time.Millisecond
)

// historyAnchor is the oldest message known in one conversation: where "older than this" starts.
type historyAnchor struct {
	chat   types.JID
	id     string
	fromMe bool
	at     time.Time
}

var (
	backfillMu     sync.Mutex
	backfillOldest = map[string]historyAnchor{}
	// What was last asked for, per conversation. A round that asks from the same message twice
	// has reached the beginning of that conversation and must stop, or it loops for ever.
	backfillAsked  = map[string]string{}
	backfillRounds int
)

// rememberOldest keeps the earliest message seen in a conversation, which is what a request for
// older ones has to be anchored to.
func rememberOldest(chat string, id string, fromMe bool, at time.Time) {
	if chat == "" || id == "" || at.IsZero() {
		return
	}
	parsed, err := types.ParseJID(chat)
	if err != nil {
		return
	}

	backfillMu.Lock()
	defer backfillMu.Unlock()
	if known, seen := backfillOldest[chat]; seen && !at.Before(known.at) {
		return
	}
	backfillOldest[chat] = historyAnchor{chat: parsed, id: id, fromMe: fromMe, at: at}
}

// requestOlderHistory asks WhatsApp for the messages before the oldest one this phone has.
//
// What WhatsApp sends a newly linked companion unprompted is a slice, not an archive — and the
// size of that slice was fixed when the phone was linked. This is the other half: for every
// conversation, "send me fifty more from before this message", answered as another history sync,
// which lands in [emitHistory] and comes straight back here for the next fifty. So the import
// walks backwards a chunk at a time until each conversation runs out or the round limit bites.
//
// Every failure here is silent and ordinary. An account linked before this build asked for
// OnDemandReady may simply be refused, and there is nothing a caregiver could do about that; what
// has already been imported is unaffected, and the alternative — telling her something failed
// about messages she has never seen and does not know exist — would be noise.
func requestOlderHistory(client *whatsmeow.Client) {
	if client == nil || client.Store == nil || client.Store.ID == nil {
		return
	}

	backfillMu.Lock()
	if backfillRounds >= maxBackfillRounds {
		backfillMu.Unlock()
		return
	}
	backfillRounds++
	round := backfillRounds
	targets := make([]historyAnchor, 0, len(backfillOldest))
	for chat, anchor := range backfillOldest {
		if backfillAsked[chat] == anchor.id {
			continue
		}
		backfillAsked[chat] = anchor.id
		targets = append(targets, anchor)
	}
	backfillMu.Unlock()

	if len(targets) == 0 {
		note("history backfill: every conversation is back to its beginning")
		return
	}

	me := client.Store.ID.ToNonAD()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()

	asked := 0
	for _, anchor := range targets {
		request := client.BuildHistorySyncRequest(&types.MessageInfo{
			MessageSource: types.MessageSource{Chat: anchor.chat, IsFromMe: anchor.fromMe},
			ID:            anchor.id,
			Timestamp:     anchor.at,
		}, onDemandChunk)
		if request == nil {
			continue
		}

		if _, err := client.SendMessage(ctx, me, request, whatsmeow.SendRequestExtra{Peer: true}); err != nil {
			// One refusal is the whole account refusing, not this conversation: stop rather than
			// send the same rejected request another ninety times.
			note("history backfill: refused after %d of %d", asked, len(targets))
			return
		}
		asked++
		time.Sleep(backfillSpacing)
	}

	note("history backfill round %d: asked %d conversations for %d older each", round, asked, onDemandChunk)
}

// historicChatAddress is [chatAddress] for a conversation out of a history sync, which is a JID
// as a string and carries no alternative addresses at all.
//
// It has to reach the same answer as the live path or the same message arrives twice under two
// different names: once when she sends it, and again in the next history sync.
func historicChatAddress(chat string, lidToPN func(types.JID) (types.JID, bool)) string {
	parsed, err := types.ParseJID(chat)
	if err != nil {
		return chat
	}
	return chatAddress(types.MessageSource{Chat: parsed}, lidToPN)
}

func emitHistoricMessage(client *whatsmeow.Client, chat string, info *waWeb.WebMessageInfo) bool {
	if info == nil {
		return false
	}
	text := extractText(info.GetMessage())
	if text == "" {
		return false
	}

	isGroup := strings.HasSuffix(chat, "@g.us")
	fromMe := info.GetKey().GetFromMe()

	// Whose message it is, in the sense the app files by. Incoming: the person who wrote — in a
	// group that is a member, not the group, and getting it backwards loses who spoke. Outgoing:
	// the person or group she wrote to, which is the chat.
	sender := chat
	if !fromMe && isGroup {
		sender = info.GetParticipant()
		if sender == "" {
			sender = info.GetKey().GetParticipant()
		}
	}
	if sender == "" {
		return false
	}

	direction := "in"
	if fromMe {
		direction = "out"
	}

	lookup := lidLookup(client)

	ev := outEvent{
		"type":      "message",
		"messageId": info.GetKey().GetID(),
		"sender":    sender,
		"chat":      chat,
		"isGroup":   isGroup,
		"pushName":  info.GetPushName(),
		"text":      text,
		// Seconds on the wire here, unlike events.Message, which is already milliseconds.
		"timestamp": int64(info.GetMessageTimestamp()) * 1000,
		"direction": direction,
		// Old news. Shown, never announced: a fortnight of unread badges arriving at once on the
		// day her phone is set up teaches her within a day that the badge means nothing.
		"historical": true,
	}
	populateQuote(ev, info.GetMessage(), lookup)
	emit(ev)

	// Most of these will fail: WhatsApp expires media, and a four-year history is mostly beyond
	// it. The ones that survive are the recent ones, which are the ones she is likely to be
	// asked about.
	fetchMedia(context.Background(), client, info.GetKey().GetID(), info.GetMessage())
	if !fromMe {
		if parsed, err := types.ParseJID(sender); err == nil {
			fetchAvatar(context.Background(), client, parsed)
		}
	}

	// Where "older than this" starts, for the next round of the import — see
	// [requestOlderHistory]. Recorded from the message rather than the conversation because the
	// oldest one in a chunk is the only thing WhatsApp will accept as an anchor.
	rememberOldest(
		chat,
		info.GetKey().GetID(),
		fromMe,
		time.Unix(int64(info.GetMessageTimestamp()), 0),
	)
	return true
}

func emitMessage(client *whatsmeow.Client, evt *events.Message) {
	// Her own messages, echoed back from her phone or another companion — anything written in
	// the native WhatsApp app arrives here and nowhere else. The app keeps them and drops only
	// the echo of what it sent itself, which it recognises by the id it chose; the caregiver
	// side needs them too, to show a whole conversation rather than half of one.
	direction := "in"
	if evt.Info.IsFromMe {
		direction = "out"
	}

	// A reaction belongs to a message that is already on her screen, so it is reported against
	// that message rather than as one of its own.
	if reaction := evt.Message.GetReactionMessage(); reaction != nil {
		emitReaction(evt, reaction, lidLookup(client))
		return
	}

	text := extractText(evt.Message)
	if text == "" {
		// Nothing displayable and no placeholder matched: a receipt, a protocol message, an edit
		// of something already delivered. Silently dropped rather than shown as an empty line.
		return
	}

	lookup := lidLookup(client)
	chat := chatAddress(evt.Info.MessageSource, lookup)
	sender := messageSender(evt.Info.IsFromMe, evt.Info.Sender, evt.Info.SenderAlt, chat, lookup)

	ev := outEvent{
		"type":      "message",
		"messageId": evt.Info.ID,
		"sender":    sender,
		"chat":      chat,
		"isGroup":   evt.Info.IsGroup,
		"pushName":  evt.Info.PushName,
		"text":      text,
		"timestamp": evt.Info.Timestamp.UnixMilli(),
		"direction": direction,
	}
	populateQuote(ev, evt.Message, lookup)
	emit(ev)

	fetchMedia(context.Background(), client, evt.Info.ID, evt.Message)
	if !evt.Info.IsFromMe {
		if parsed, err := types.ParseJID(sender); err == nil {
			fetchAvatar(context.Background(), client, parsed)
		}
	}
}

const maxQuotedTextLength = 240

// populateQuote adds quote metadata to an emitted event when a message is replying to an earlier one.
//
// WhatsApp puts reply context in ContextInfo beside the message — the stanza id of what is being
// answered, who wrote it, and a copy of the quoted message itself. When present, these allow the
// phone to draw a quiet citation above the bubble so she can see which question or photograph is
// being answered.
//
// quotedSender goes through [personJID] and the LID directory for the same reason every other
// sender does: her phone matches people by telephone number, and captioning a quote of her
// daughter with a LID would make her look like a stranger. If Participant fails to parse, it is
// left out rather than emitting machine rubbish.
//
// quotedText is capped at [maxQuotedTextLength] so a quoted essay does not travel with every reply.
// When there is no quote or no stanza id, nothing is added to the event — omitting empty strings
// keeps the wire byte-for-byte identical to what it was before quotes existed.
func populateQuote(ev outEvent, msg *waE2E.Message, lookup func(types.JID) (types.JID, bool)) {
	ctx := contextInfoOf(msg)
	if ctx == nil || ctx.GetStanzaID() == "" {
		return
	}
	ev["quotedId"] = ctx.GetStanzaID()
	if part := ctx.GetParticipant(); part != "" {
		if parsed, err := types.ParseJID(part); err == nil && !parsed.IsEmpty() {
			if sender := personJID(parsed, types.EmptyJID, lookup); sender != "" {
				ev["quotedSender"] = sender
			}
		}
	}
	if text := truncateText(extractText(ctx.GetQuotedMessage()), maxQuotedTextLength); text != "" {
		ev["quotedText"] = text
	}
}

// contextInfoOf returns the ContextInfo carried by a message, or nil when there is none.
//
// Six kinds of message carry reply context, each on its own field: ExtendedTextMessage,
// ImageMessage, VideoMessage, AudioMessage, DocumentMessage, StickerMessage.
func contextInfoOf(msg *waE2E.Message) *waE2E.ContextInfo {
	if msg == nil {
		return nil
	}
	if ctx := msg.GetExtendedTextMessage().GetContextInfo(); ctx != nil {
		return ctx
	}
	if ctx := msg.GetImageMessage().GetContextInfo(); ctx != nil {
		return ctx
	}
	if ctx := msg.GetVideoMessage().GetContextInfo(); ctx != nil {
		return ctx
	}
	if ctx := msg.GetAudioMessage().GetContextInfo(); ctx != nil {
		return ctx
	}
	if ctx := msg.GetDocumentMessage().GetContextInfo(); ctx != nil {
		return ctx
	}
	if ctx := msg.GetStickerMessage().GetContextInfo(); ctx != nil {
		return ctx
	}
	return nil
}

// truncateText caps a string to at most maxRunes Unicode code points.
func truncateText(s string, maxRunes int) string {
	r := []rune(s)
	if len(r) <= maxRunes {
		return s
	}
	return string(r[:maxRunes])
}

// personJID is the telephone-number address of whoever sent something, in preference to their
// hidden one.
//
// WhatsApp now addresses people two ways: the old `41791234567@s.whatsapp.net`, and a "LID"
// `2345678901@lid` that deliberately says nothing about their telephone number. Which of the two
// arrives in Sender depends on the chat, and status updates arrive with the LID.
//
// The phone matches everybody to her address book by telephone number, so a LID is a person it
// has never heard of: her daughter's status came through as a stranger, with the name her
// daughter chose for herself on WhatsApp rather than the one on her mother's phone. whatsmeow
// hands us the other address in SenderAlt, and one of the two is usually the number.
//
// Usually, not always — and on statuses, hardly ever. SenderAlt is filled in from what the
// message itself carried, and a status update carries a LID and nothing else. Checked on her
// phone on 2026-08-16: all 207 statuses in the database had come through with no number at all,
// so not one of them had been matched to her address book and every one of them was showing
// whatever the poster calls themselves on WhatsApp. That is the "strange names" — they are real
// names, chosen by real people, and none of them is the name she knows that person by.
//
// So the LID directory is asked as well, exactly as [chatAddress] already asks it. It is a table
// on disk holding the mapping for everybody she has ever exchanged a message with, which for
// somebody posting a status she can see is nearly always true.
//
// Falls back to whatever Sender was. A LID is a poor name for somebody, but it is stable, so
// messages from one person still gather in one conversation.
func personJID(sender types.JID, alt types.JID, lidToPN func(types.JID) (types.JID, bool)) string {
	if sender.Server == types.DefaultUserServer {
		return sender.ToNonAD().String()
	}
	if alt.Server == types.DefaultUserServer {
		return alt.ToNonAD().String()
	}
	if lidToPN != nil {
		if pn, ok := lidToPN(sender.ToNonAD()); ok && pn.Server == types.DefaultUserServer {
			return pn.ToNonAD().String()
		}
	}
	return sender.ToNonAD().String()
}

// chatAddress is the address the conversation itself is filed under.
//
// [personJID] fixed this for the *sender* of a message and left the chat alone, which was only
// half the problem: WhatsApp addresses whole conversations by LID too, and then Chat is
// `2345678901@lid` with the telephone number nowhere in it. The app matches people to her address
// book by telephone number and drops what it cannot place, so for a LID-addressed chat every
// message of hers vanished on the way in — the one path a message written in the native WhatsApp
// app ever travels. It went unnoticed for as long as it did because incoming messages are filed
// by their sender, which was already being resolved.
//
// The number is usually in the message itself. Whose alternative address it is depends on who
// wrote: for a message of hers the counterpart is the recipient, for anybody else's it is the
// sender. When neither carries it, whatsmeow's own LID directory is asked — it holds the mapping
// for everybody she has ever exchanged a message with.
//
// Groups keep their `@g.us` address unchanged: a group has no telephone number, and the app
// already knows how to file one.
func chatAddress(info types.MessageSource, lidToPN func(types.JID) (types.JID, bool)) string {
	chat := info.Chat.ToNonAD()
	if chat.Server != types.HiddenUserServer {
		return chat.String()
	}

	alt := info.SenderAlt
	if info.IsFromMe {
		alt = info.RecipientAlt
	}
	if alt.Server == types.DefaultUserServer {
		return alt.ToNonAD().String()
	}

	if lidToPN != nil {
		if pn, ok := lidToPN(chat); ok && pn.Server == types.DefaultUserServer {
			return pn.ToNonAD().String()
		}
	}

	// A LID and nothing to turn it into. Kept rather than dropped: she is shown a number she does
	// not recognise instead of nothing at all, and everything from that chat still gathers in one
	// conversation.
	return chat.String()
}

// ownJID is her own WhatsApp address, or an empty JID before she has linked the phone.
//
// Empty is an ordinary answer, not a failure: the bridge runs from the moment the app starts and
// is asked about groups only after pairing, but nothing here may assume that order.
func ownJID(client *whatsmeow.Client) types.JID {
	if client == nil || client.Store == nil || client.Store.ID == nil {
		return types.JID{}
	}
	return client.Store.ID.ToNonAD()
}

// lidLookup asks whatsmeow for the telephone number behind a LID.
//
// Separated from [chatAddress] so that the choice of address is pure and can be tested, and
// because the answer comes from a database on disk: a failed lookup is an ordinary outcome here
// (an unknown LID) and must read as "no number" rather than as an error worth reporting.
func lidLookup(client *whatsmeow.Client) func(types.JID) (types.JID, bool) {
	return func(lid types.JID) (types.JID, bool) {
		if client == nil || client.Store == nil || client.Store.LIDs == nil {
			return types.JID{}, false
		}
		pn, err := client.Store.LIDs.GetPNForLID(context.Background(), lid)
		if err != nil || pn.IsEmpty() {
			return types.JID{}, false
		}
		return pn, true
	}
}

// messageSender is whose message this is, in the sense the app files by.
//
// Incoming: whoever wrote it. Outgoing: the person or the group she wrote *to*, which is the
// chat — WhatsApp puts her own address in Sender for her own messages, and filing by that would
// gather everything she has ever written into a conversation with herself.
//
// The same choice emitHistoricMessage already makes, and it has to stay the same one: a message
// she sends from the native app arrives here live and again in the next history sync, and the
// two copies must land in one conversation rather than two.
func messageSender(fromMe bool, sender types.JID, alt types.JID, chat string, lidToPN func(types.JID) (types.JID, bool)) string {
	if fromMe {
		return chat
	}
	return personJID(sender, alt, lidToPN)
}

// fetchMedia downloads whatever in a message is worth downloading, and says where it landed.
//
// Asynchronous on purpose. The words arrive on her screen the moment the message does; the
// picture or the voice message appears with them when it has been fetched, which for a history
// import of four years may be minutes later. Making the message wait would mean a burst of
// several hundred downloads standing between her and everything anybody has ever written to her.
//
// Every failure here is silent by design. Media on WhatsApp's servers expires, and most of an
// old history sync cannot be downloaded at all — that is not a fault of this phone, and a
// caregiver log full of "could not fetch a photograph from 2023" would bury the entries that
// mean something. The message keeps its text, which is what it had before.
func fetchMedia(ctx context.Context, client *whatsmeow.Client, messageID string, msg *waE2E.Message) {
	if mediaDir == "" || messageID == "" || msg == nil {
		return
	}

	if image := msg.GetImageMessage(); image != nil {
		if image.GetFileLength() <= maxMediaBytes {
			downloadMedia(ctx, client, messageID, kindPhoto, "jpg", image)
		}
		return
	}

	if audio := msg.GetAudioMessage(); audio != nil {
		if audio.GetFileLength() <= maxMediaBytes {
			// Kept as .ogg whether or not WhatsApp called it a voice note. Both are Opus in an
			// Ogg container, and both are somebody talking to her.
			downloadMedia(ctx, client, messageID, kindVoice, "ogg", audio)
		}
		return
	}

	if video := msg.GetVideoMessage(); video != nil {
		// The still first, and unconditionally. It is inside the message already, so it costs
		// one file write and no network at all, and on its own it turns "[Video]" into a picture
		// of whoever is in it — which is what she gets when the film is too large, has expired,
		// or the download never finishes.
		writeMedia(messageID, kindPhoto, "jpg", video.GetJPEGThumbnail())

		// Then the film, behind it. Written under the same message id but a different extension,
		// so the two land as two files and two events; the app keeps both, and the picture stays
		// where it was while this is still coming.
		if video.GetFileLength() <= maxVideoBytes {
			downloadMedia(ctx, client, messageID, kindVideo, "mp4", video)
		}
		return
	}
}

// downloadMedia fetches an encrypted attachment and writes it out.
func downloadMedia(
	ctx context.Context,
	client *whatsmeow.Client,
	messageID string,
	kind string,
	extension string,
	source whatsmeow.DownloadableMessage,
) {
	go func() {
		mediaFetch <- struct{}{}
		defer func() { <-mediaFetch }()

		data, err := client.Download(ctx, source)
		if err != nil {
			return
		}
		writeMedia(messageID, kind, extension, data)
	}()
}

// writeMedia puts bytes on disk and tells the app where they are.
//
// Named by message id, which is what the app knows it by, and what makes a second delivery of
// the same message overwrite rather than accumulate. The name is sanitised because the id
// arrives from the network: a "/" in it would otherwise write outside this directory.
func writeMedia(messageID, kind, extension string, data []byte) {
	if mediaDir == "" || len(data) == 0 {
		return
	}

	path := filepath.Join(mediaDir, safeFileName(messageID)+"."+extension)
	// Written beside its name and renamed onto it, so that a file under its real name is always
	// a whole one. The app attaches a film it finds on disk even when the event saying so was
	// lost (StrandedFilms), and a bridge killed halfway through a 40 MB film would otherwise
	// leave half of one there to be attached. No real name can be mistaken for the partial one:
	// every real name ends in its extension.
	partial := path + ".part"
	if err := os.WriteFile(partial, data, 0o600); err != nil {
		note("could not write %s: %v", kind, err)
		os.Remove(partial)
		return
	}
	if err := os.Rename(partial, path); err != nil {
		note("could not write %s: %v", kind, err)
		os.Remove(partial)
		return
	}

	emit(outEvent{"type": "media", "messageId": messageID, "path": path, "kind": kind})
	pruneMedia()
}

// safeFileName reduces an identifier from the network to something that can only name a file
// inside the directory it is joined to.
func safeFileName(id string) string {
	var b strings.Builder
	for _, r := range id {
		switch {
		case r >= 'A' && r <= 'Z', r >= 'a' && r <= 'z', r >= '0' && r <= '9', r == '-', r == '_':
			b.WriteRune(r)
		default:
			b.WriteByte('.')
		}
	}
	return b.String()
}

// pruneMedia keeps the newest attachments that fit in both budgets and deletes the rest.
//
// Her phone is not an archive. What the app owes her is the picture her daughter sent this
// morning, not every photograph in four years of group chats — and a launcher that fills the
// storage of the phone it is the launcher for fails at everything, not just at photographs.
//
// Two budgets rather than one, since films started being kept: [maxMediaFiles] is what stops a
// history import filling the directory with thumbnails, and [maxMediaDiskBytes] is what stops a
// dozen films doing far more damage with far fewer files. A file is kept only if it is inside
// both, so the tighter of the two is always the one that decides.
func pruneMedia() {
	mediaPruneMu.Lock()
	defer mediaPruneMu.Unlock()

	entries, err := os.ReadDir(mediaDir)
	if err != nil {
		return
	}

	now := time.Now()
	files := make([]storedFile, 0, len(entries))
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil {
			continue
		}
		path := filepath.Join(mediaDir, entry.Name())
		// A .part file older than 5 minutes was left behind by a killed bridge process; remove it
		// so half-written downloads do not eat the media budget.
		if strings.HasSuffix(entry.Name(), ".part") {
			if now.Sub(info.ModTime()) > 5*time.Minute {
				os.Remove(path)
			}
			continue
		}
		files = append(files, storedFile{
			path:  path,
			at:    info.ModTime(),
			bytes: info.Size(),
		})
	}

	for _, path := range overBudget(files, maxMediaFiles, maxMediaDiskBytes) {
		os.Remove(path)
	}
}

// An attachment on disk, as the pruner sees it.
type storedFile struct {
	path  string
	at    time.Time
	bytes int64
}

// overBudget names the files that have to go, newest kept first.
//
// Split out from the deleting, and given its budgets rather than reading the constants, because
// this is the part worth testing: getting it wrong deletes a photograph her daughter sent this
// morning, and the only way anybody finds out is that her screen quietly stops having it.
func overBudget(files []storedFile, maxFiles int, maxBytes int64) []string {
	sorted := make([]storedFile, len(files))
	copy(sorted, files)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].at.After(sorted[j].at) })

	var doomed []string
	var kept int64
	for index, file := range sorted {
		kept += file.bytes
		// Newest first, so this is "everything from here on is older than what we are keeping".
		// The file that breaks the budget goes too: keeping it because it happens to be the one
		// that crossed the line would make the budget depend on the order files arrived in.
		if index >= maxFiles || kept > maxBytes {
			doomed = append(doomed, file.path)
		}
	}
	return doomed
}

// emitCall reports one moment of a WhatsApp call: it is ringing, somebody answered it, or it is
// over.
//
// whatsmeow speaks the signalling half of WhatsApp calling and no more: these events arrive, and
// the only thing that can be sent back is a rejection. There is no way to answer one — the audio
// runs over Meta's own relay protocol, which no client outside their own implements — so nothing
// here tries to. What the app does with this is tell her that somebody rang, and tell the caller,
// if it is somebody she knows, to use the telephone number instead.
//
// The caller goes through [personJID] and the LID directory for the reason every other sender
// does: a call that arrives as a LID is a call from a stranger, and the stranger is her daughter.
//
// Her own outgoing calls come through here too — placed in the native WhatsApp app on this same
// phone — and are marked rather than dropped, because the app would otherwise tell her she had
// missed a call she made herself. Both of her addresses are checked: WhatsApp names the creator
// of a call by either one.
//
// The reason a call ended is protocol state, so it is safe to log and worth logging: which
// strings WhatsApp actually uses is what decides whether a call was missed or picked up, and it
// is not written down anywhere outside its own clients.
func emitCall(client *whatsmeow.Client, state string, meta types.BasicCallMeta, reason string) {
	caller := personJID(meta.CallCreator, meta.CallCreatorAlt, lidLookup(client))

	fromMe := false
	if client != nil && client.Store != nil {
		if own := client.Store.ID; own != nil && own.User != "" && meta.CallCreator.User == own.User {
			fromMe = true
		}
		if lid := client.Store.LID; lid.User != "" && meta.CallCreator.User == lid.User {
			fromMe = true
		}
	}

	note("call %s reason=%q fromMe=%v group=%v", state, reason, fromMe, !meta.GroupJID.IsEmpty())
	emit(outEvent{
		"type":      "call",
		"state":     state,
		"callId":    meta.CallID,
		"from":      caller,
		"timestamp": meta.Timestamp.UnixMilli(),
		"reason":    reason,
		"fromMe":    fromMe,
		"group":     !meta.GroupJID.IsEmpty(),
	})
}

// receiptStates are the receipts worth passing on, by the word the other side sees for each.
//
// Retry, server-error, inactive, peer and history-sync receipts are protocol housekeeping
// whatsmeow already deals with, and "sender" receipts come from her own other devices.
var receiptStates = map[types.ReceiptType]string{
	types.ReceiptTypeDelivered: "delivered",
	types.ReceiptTypeRead:      "read",
	types.ReceiptTypePlayed:    "played",
}

// receiptEvent describes somebody's phone confirming messages she sent, or nil when the
// receipt is not one to report.
//
// Without this the bridge knew a message had left ("sent") and never whether it arrived or was
// read: whatsmeow raises the receipt and nothing listened. Receipts for her own reading
// (read-self, played-self, IsFromMe) are left out: they say what she did, which she knows.
// A contact who switched read receipts off only ever sends "delivered", so a missing "read"
// means "not known", not "unread".
//
// The reader is resolved through [personJID] and the chat through [chatAddress], so a receipt
// arriving under a LID names the same telephone number the message was sent to.
func receiptEvent(v *events.Receipt, lookup func(types.JID) (types.JID, bool)) outEvent {
	state, ok := receiptStates[v.Type]
	if !ok || v.IsFromMe || len(v.MessageIDs) == 0 {
		return nil
	}
	ids := make([]string, 0, len(v.MessageIDs))
	for _, id := range v.MessageIDs {
		if id != "" {
			ids = append(ids, string(id))
		}
	}
	if len(ids) == 0 {
		return nil
	}
	return outEvent{
		"type":       "receipt",
		"state":      state,
		"messageIds": ids,
		"chat":       chatAddress(v.MessageSource, lookup),
		"from":       personJID(v.Sender, v.SenderAlt, lookup),
		"timestamp":  v.Timestamp.UnixMilli(),
		"group":      v.IsGroup,
	}
}

// emitReaction reports somebody reacting to a message, against the message they reacted to.
//
// WhatsApp sends a reaction as an ordinary message whose body names another message. Passing it
// through as a message put a bare "❤️" in her conversation as a line from nobody, sitting below
// whatever happened to arrive next and pointing at nothing.
//
// Removing a reaction is the same message with empty text, which is reported as a withdrawal
// rather than dropped: a heart that stays on her screen after her daughter took it back is a
// small untruth that nobody can correct from her end.
//
// Senders are routed through [personJID] and the LID directory so that a person keyed as a LID
// on one reaction and a telephone number on another resolves to the same sender.
func emitReaction(evt *events.Message, reaction *waE2E.ReactionMessage, lookup func(types.JID) (types.JID, bool)) {
	target := reaction.GetKey().GetID()
	if target == "" {
		return
	}
	sender := personJID(evt.Info.Sender, evt.Info.SenderAlt, lookup)
	emit(outEvent{
		"type":            "reaction",
		"targetMessageId": target,
		"sender":          sender,
		"chat":            evt.Info.Chat.ToNonAD().String(),
		"emoji":           reaction.GetText(),
		"withdrawn":       reaction.GetText() == "",
		"timestamp":       evt.Info.Timestamp.UnixMilli(),
		"fromMe":          evt.Info.IsFromMe,
	})
}

// extractText reduces a WhatsApp message to the one line she will read.
func extractText(msg *waE2E.Message) string {
	if msg == nil {
		return ""
	}
	if t := msg.GetConversation(); t != "" {
		return t
	}
	if t := msg.GetExtendedTextMessage().GetText(); t != "" {
		return t
	}

	// Media. The caption is the message where there is one — somebody writing "schau mal" under a
	// photograph meant the words — and the placeholder stands in where there is not.
	if m := msg.GetImageMessage(); m != nil {
		return withCaption(m.GetCaption(), mediaPlaceholders["image"])
	}
	if m := msg.GetVideoMessage(); m != nil {
		return withCaption(m.GetCaption(), mediaPlaceholders["video"])
	}
	if m := msg.GetDocumentMessage(); m != nil {
		return withCaption(m.GetCaption(), mediaPlaceholders["document"])
	}
	if msg.GetAudioMessage() != nil {
		return mediaPlaceholders["audio"]
	}
	if msg.GetStickerMessage() != nil {
		return mediaPlaceholders["sticker"]
	}
	if msg.GetLocationMessage() != nil || msg.GetLiveLocationMessage() != nil {
		return mediaPlaceholders["location"]
	}
	if msg.GetContactMessage() != nil || msg.GetContactsArrayMessage() != nil {
		return mediaPlaceholders["contact"]
	}
	// Deliberately no reaction here. A reaction is not a message and must never become one:
	// returning its emoji from this function is what put bare hearts in her conversation as
	// lines from nobody, with nothing to say what they were about. See emitReaction.
	return ""
}

func withCaption(caption, placeholder string) string {
	if strings.TrimSpace(caption) == "" {
		return placeholder
	}
	return placeholder + " " + caption
}

// configureResolver teaches this process how to look up a hostname on Android.
//
// This was found by running the binary on her actual phone, where it started correctly, opened
// its database, and then failed every connection with
//
//	lookup web.whatsapp.com on [::1]:53: connection refused
//
// A Go binary built without cgo uses Go's own DNS resolver, which reads /etc/resolv.conf. Android
// has no /etc/resolv.conf — name resolution goes through netd, reachable only through bionic's
// getaddrinfo, which is to say only through cgo. The `net.dns1` system properties that used to
// carry the servers have been empty since Android 8. So on this phone the resolver had no servers
// at all, fell back to localhost, and found nothing listening.
//
// The fix is to be told. Android's ConnectivityManager exposes the active network's DNS servers
// through public API (LinkProperties.getDnsServers), so the Kotlin side reads them and passes
// them in here. That keeps the build free of cgo and the NDK, and — unlike hardcoding a public
// resolver — it uses whatever the flat's own network uses, so it still works behind a router that
// answers for local names and on a mobile network that blocks outbound port 53.
//
// With no servers given, the default resolver is left alone: that is the correct behaviour
// everywhere that does have /etc/resolv.conf, including a desktop and a CI runner.
//
// The servers belong to a network, not to the process, and she does not stay on one network:
// leaving the flat moves the phone from the router to the mobile operator. The list given at
// start used to be kept for the life of the process, so on 2026-09-17 the bridge went on asking
// 192.168.1.1 from the street, every reconnect failed on a lookup that could never answer, and
// WhatsApp stayed silently offline until the app was restarted. So the list is swappable — see
// [setDNSServers], which the app calls whenever the phone's network changes.
func configureResolver(servers string) {
	setDNSServers(servers)
}

// dnsAddresses is the list the resolver dials, replaced whole whenever the network changes.
var dnsAddresses atomic.Pointer[[]string]

// resolverInstalled guards the one-time replacement of Go's resolver.
var resolverInstalled sync.Once

// setDNSServers replaces the servers lookups go to, and reports whether anything changed. An
// empty list changes nothing: a network briefly reporting no servers mid-switch is not a reason
// to forget the last ones that worked.
func setDNSServers(servers string) bool {
	addresses := parseDNSServers(servers)
	if len(addresses) == 0 {
		return false
	}

	if previous := dnsAddresses.Load(); previous != nil && sameAddresses(*previous, addresses) {
		return false
	}
	dnsAddresses.Store(&addresses)

	resolverInstalled.Do(func() {
		var next uint32
		net.DefaultResolver = &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
				current := *dnsAddresses.Load()
				// Each attempt starts at the next server, so a first one that has gone away does
				// not cost every lookup a timeout for the lifetime of the process.
				start := int(atomic.AddUint32(&next, 1))
				var lastErr error
				dialer := net.Dialer{Timeout: dnsTimeout}
				for i := range current {
					address := current[(start+i)%len(current)]
					conn, err := dialer.DialContext(ctx, network, address)
					if err == nil {
						return conn, nil
					}
					lastErr = err
				}
				return nil, lastErr
			},
		}
	})
	return true
}

// parseDNSServers turns "a,b" into dialable host:port addresses.
func parseDNSServers(servers string) []string {
	addresses := make([]string, 0, 2)
	for _, raw := range strings.Split(servers, ",") {
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			continue
		}
		// Accept a bare address; add the port DNS actually listens on.
		if !strings.Contains(trimmed, ":") || strings.Count(trimmed, ":") > 1 {
			// IPv6 needs brackets before a port can be appended.
			if strings.Count(trimmed, ":") > 1 {
				trimmed = "[" + trimmed + "]"
			}
			trimmed += ":53"
		}
		addresses = append(addresses, trimmed)
	}
	return addresses
}

func sameAddresses(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// changeDNSServers is the "dns" command: the phone has moved to a network with other servers.
//
// A linked bridge that is offline is by then usually deep in whatsmeow's reconnect loop, whose
// wait grows by two seconds with every failure — after half an hour of dead lookups, well over a
// minute. So it is not left to find the new servers on its own: a connection is attempted at
// once. If the loop gets there first, one of the two is told it is already connected, which is
// harmless in either direction.
func changeDNSServers(client *whatsmeow.Client, servers string) {
	if !setDNSServers(servers) {
		return
	}
	// The count, not the addresses, as in the app's own log line.
	note("dns servers changed, %d now", len(*dnsAddresses.Load()))
	if client.Store.ID == nil || client.IsConnected() {
		return
	}
	go func() {
		if err := client.Connect(); err != nil && !errors.Is(err, whatsmeow.ErrAlreadyConnected) {
			note("reconnect after dns change failed: %v", err)
		}
	}()
}

const dnsTimeout = 5 * time.Second

func onlyDigits(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// fetchAvatar downloads a contact's or group's profile picture if not already cached and emits an
// avatar event. A group's is fetched when the group is reported, so its row can wear its own picture.
//
// Like fetchMedia, this is asynchronous and all failures are silent. Three cases are ordinary:
// the user has no picture set, their privacy settings forbid it, or the phone is offline.
// None of these is an error worth showing or logging.
func fetchAvatar(ctx context.Context, client *whatsmeow.Client, jid types.JID) {
	if avatarDir == "" || client == nil || jid.IsEmpty() {
		return
	}

	targetJID := jid.ToNonAD()
	if targetJID.Server != types.DefaultUserServer && targetJID.Server != types.GroupServer {
		return
	}

	jidStr := targetJID.String()
	userDigits := onlyDigits(targetJID.User)
	safeName := safeFileName(jidStr)
	if userDigits != "" {
		safeName = userDigits
	}
	path := filepath.Join(avatarDir, safeName+".jpg")

	// Disk cache check: if already on disk and non-empty, emit and skip network
	if stat, err := os.Stat(path); err == nil && stat.Size() > 0 {
		if time.Since(stat.ModTime()) < 24*time.Hour {
			emitAvatar(jidStr, path)
			return
		}
	}

	// Deduplicate in-flight fetches for the same JID
	avatarMu.Lock()
	if avatarFetching[jidStr] {
		avatarMu.Unlock()
		return
	}
	avatarFetching[jidStr] = true
	avatarMu.Unlock()

	go func() {
		defer func() {
			avatarMu.Lock()
			delete(avatarFetching, jidStr)
			avatarMu.Unlock()
		}()

		if !client.IsConnected() {
			return
		}

		fetchCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
		defer cancel()

		info, err := client.GetProfilePictureInfo(fetchCtx, targetJID, &whatsmeow.GetProfilePictureParams{
			Preview: false,
		})
		if err != nil || info == nil || info.URL == "" {
			return
		}

		req, err := http.NewRequestWithContext(fetchCtx, http.MethodGet, info.URL, nil)
		if err != nil {
			return
		}

		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			return
		}
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			return
		}

		data, err := io.ReadAll(io.LimitReader(resp.Body, maxMediaBytes))
		if err != nil || len(data) == 0 {
			return
		}

		if err := os.WriteFile(path, data, 0o600); err != nil {
			return
		}

		emitAvatar(jidStr, path)
	}()
}

// emitAvatar tells the app a picture is on disk, once per version of the file.
//
// This is called for the sender of every message and for every group that is reported, and the
// picture is nearly always the one the app already drew: telling it again makes the home screen
// redraw every face for nothing. A picture fetched afresh has a new modification time and is
// announced; the same file is not — see [alreadySaid].
func emitAvatar(jid, path string) {
	version := path
	if stat, err := os.Stat(path); err == nil {
		version = fmt.Sprintf("%s %d %d", path, stat.ModTime().UnixNano(), stat.Size())
	}
	if alreadySaid("avatar "+jid, version) {
		return
	}
	emit(outEvent{
		"type": "avatar",
		"jid":  jid,
		"path": path,
	})
}

// callRejectedHere is the reason reported for a call this bridge turned away itself. Not one of
// WhatsApp's words, so the app counts it as a call nobody took — which is what it is.
const callRejectedHere = "rejected_by_phone"

// callBook remembers the calls that are ringing, as WhatsApp offered them.
//
// Rejecting a call has to name its creator exactly as the offer did — by the hidden id, where it
// was one — and the app only ever sees the number that id resolves to. So the offer's own words
// are kept here until the call is taken, turned down or over. Offers that never hear another word
// are dropped after an hour, the same horizon the app uses for its own record of ringing calls.
type callBook struct {
	mu    sync.Mutex
	calls map[string]types.BasicCallMeta
}

var ringing = &callBook{calls: map[string]types.BasicCallMeta{}}

func (b *callBook) remember(meta types.BasicCallMeta) {
	if meta.CallID == "" {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	for id, old := range b.calls {
		if !old.Timestamp.IsZero() && time.Since(old.Timestamp) > time.Hour {
			delete(b.calls, id)
		}
	}
	b.calls[meta.CallID] = meta
}

// take removes a call and returns it, so that only one rejection is ever sent for it.
func (b *callBook) take(callID string) (types.BasicCallMeta, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	meta, ok := b.calls[callID]
	if ok {
		delete(b.calls, callID)
	}
	return meta, ok
}

func (b *callBook) forget(callID string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	delete(b.calls, callID)
}

// rejectCall turns a ringing call away, when a caregiver has asked for WhatsApp calls not to ring
// on her phone — see RemoteSettings.rejectWhatsAppCalls in the app, which decides; this only does.
//
// Success is reported as the call ending, with [callRejectedHere] as the reason: the app already
// knows what to do with a call that ended without being taken — a missed call on her screen, and
// a word to the caller — and needs no second path for one it ended itself. A rejection that fails
// leaves the call remembered, since it is still ringing.
func rejectCall(ctx context.Context, client *whatsmeow.Client, cmd command) {
	meta, ok := ringing.take(cmd.CallID)
	if !ok {
		emitError("reject_call", "no such call is ringing")
		return
	}
	if err := client.RejectCall(ctx, meta.CallCreator, meta.CallID); err != nil {
		ringing.remember(meta)
		emitError("reject_call", err.Error())
		return
	}
	meta.Timestamp = time.Now()
	emitCall(client, "ended", meta, callRejectedHere)
}
