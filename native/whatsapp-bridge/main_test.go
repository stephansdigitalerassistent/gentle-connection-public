package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	waBinary "go.mau.fi/whatsmeow/binary"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

// The pruner is the one thing in this bridge that deletes something of hers, and it started
// mattering much more when films began being kept: an attachment used to be a photograph or a
// few seconds of speech, and can now be forty megabytes on its own.

func at(minutes int) time.Time {
	return time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC).Add(time.Duration(minutes) * time.Minute)
}

func names(files []string) map[string]bool {
	set := make(map[string]bool, len(files))
	for _, file := range files {
		set[file] = true
	}
	return set
}

func TestNothingIsDeletedWhileBothBudgetsAreKept(t *testing.T) {
	files := []storedFile{
		{path: "new.jpg", at: at(30), bytes: 500},
		{path: "old.jpg", at: at(10), bytes: 500},
	}

	if doomed := overBudget(files, 400, 512<<20); len(doomed) != 0 {
		t.Fatalf("nothing should have been pruned, got %v", doomed)
	}
}

func TestTheOldestGoFirstWhenThereAreTooManyFiles(t *testing.T) {
	// What the count budget has always been for: a history import arriving as several hundred
	// thumbnails at once. Newest wins, because what the app owes her is the picture her daughter
	// sent this morning, not one from four years ago.
	files := []storedFile{
		{path: "oldest.jpg", at: at(1), bytes: 10},
		{path: "newest.jpg", at: at(3), bytes: 10},
		{path: "middle.jpg", at: at(2), bytes: 10},
	}

	doomed := names(overBudget(files, 2, 512<<20))

	if !doomed["oldest.jpg"] {
		t.Error("the oldest file should have been pruned")
	}
	if doomed["newest.jpg"] || doomed["middle.jpg"] {
		t.Errorf("only the oldest should have gone, got %v", doomed)
	}
}

func TestAFewFilmsCanBreakTheBudgetTheCountWouldNotNotice(t *testing.T) {
	// The reason the byte budget exists. Three files is nowhere near the count budget, and on
	// disk they are most of the storage of her phone — which is the failure nobody would connect
	// to WhatsApp, because what breaks is the camera, or the next system update.
	files := []storedFile{
		{path: "newest.mp4", at: at(3), bytes: 40 << 20},
		{path: "middle.mp4", at: at(2), bytes: 40 << 20},
		{path: "oldest.mp4", at: at(1), bytes: 40 << 20},
	}

	doomed := names(overBudget(files, 400, 100<<20))

	if doomed["newest.mp4"] || doomed["middle.mp4"] {
		t.Errorf("the two newest films fit in the budget and should have been kept, got %v", doomed)
	}
	if !doomed["oldest.mp4"] {
		t.Error("the third film does not fit and should have been pruned")
	}
}

func TestOneFilmDoesNotTakeThePhotographsWithIt(t *testing.T) {
	// A single film larger than the whole budget. It goes, and so does everything older — but a
	// newer photograph is never dragged down with it, because the newest is always kept first.
	files := []storedFile{
		{path: "photo.jpg", at: at(5), bytes: 1 << 20},
		{path: "huge.mp4", at: at(4), bytes: 200 << 20},
		{path: "older-photo.jpg", at: at(3), bytes: 1 << 20},
	}

	doomed := names(overBudget(files, 400, 100<<20))

	if doomed["photo.jpg"] {
		t.Error("the newest photograph fits the budget and must survive a film that does not")
	}
	if !doomed["huge.mp4"] || !doomed["older-photo.jpg"] {
		t.Errorf("the film and everything older should have gone, got %v", doomed)
	}
}

func TestAnEmptyDirectoryIsNotAnError(t *testing.T) {
	if doomed := overBudget(nil, 400, 512<<20); len(doomed) != 0 {
		t.Fatalf("nothing to prune, got %v", doomed)
	}
}

func TestASafeFileNameCannotClimbOutOfTheMediaDirectory(t *testing.T) {
	// The id comes off the network. A "/" in it would otherwise write outside the directory.
	escaped := safeFileName("../../etc/passwd")
	if strings.ContainsRune(escaped, '/') {
		t.Fatalf("%q can still name a path", escaped)
	}
	if escaped != "......etc.passwd" {
		t.Fatalf("unexpected sanitisation: %q", escaped)
	}

	// An ordinary WhatsApp id is left alone, so a redelivery overwrites its own file rather than
	// accumulating a second one.
	if got := safeFileName("3EB0A1B2C3"); got != "3EB0A1B2C3" {
		t.Fatalf("an ordinary id should be untouched, got %q", got)
	}
}

// Whose message it is decides which conversation it lands in, and her own messages are the case
// that was wrong: WhatsApp puts her own address in Sender for anything she sends, so filing by
// the sender gathered everything she ever wrote into a conversation with herself. Nothing on the
// phone would have said so — the messages were being dropped before anybody could see where they
// had been filed.

func TestHerOwnMessageIsFiledUnderWhoeverSheWroteTo(t *testing.T) {
	hers, err := types.ParseJID("41799999999@s.whatsapp.net")
	if err != nil {
		t.Fatalf("unusable test jid: %v", err)
	}
	chat := "41791234567@s.whatsapp.net"

	if got := messageSender(true, hers, types.JID{}, chat, nil); got != chat {
		t.Fatalf("her own message should be filed under %q, got %q", chat, got)
	}
}

func TestHerOwnGroupMessageIsFiledUnderTheGroup(t *testing.T) {
	hers, err := types.ParseJID("41799999999@s.whatsapp.net")
	if err != nil {
		t.Fatalf("unusable test jid: %v", err)
	}
	chat := "1234567890-1234567890@g.us"

	if got := messageSender(true, hers, types.JID{}, chat, nil); got != chat {
		t.Fatalf("her own group message should be filed under %q, got %q", chat, got)
	}
}

func TestSomebodyElsesMessageIsStillFiledUnderThem(t *testing.T) {
	sender, err := types.ParseJID("41791234567@s.whatsapp.net")
	if err != nil {
		t.Fatalf("unusable test jid: %v", err)
	}

	// The chat is the group; the sender is the member who spoke, and losing that loses who said
	// it.
	got := messageSender(false, sender, types.JID{}, "1234567890-1234567890@g.us", nil)
	if got != "41791234567@s.whatsapp.net" {
		t.Fatalf("a message from somebody else should be filed under them, got %q", got)
	}
}

func TestAHiddenSenderIsStillMatchedByTheirNumber(t *testing.T) {
	lid, err := types.ParseJID("2345678901@lid")
	if err != nil {
		t.Fatalf("unusable test jid: %v", err)
	}
	alt, err := types.ParseJID("41791234567@s.whatsapp.net")
	if err != nil {
		t.Fatalf("unusable test jid: %v", err)
	}

	// Her address book knows telephone numbers and nothing else, so a LID is a stranger.
	got := messageSender(false, lid, alt, "2345678901@lid", nil)
	if got != "41791234567@s.whatsapp.net" {
		t.Fatalf("a hidden sender should be filed under their number, got %q", got)
	}
}

// A status update is the case where the message carries no alternative address at all, so the
// directory is the only thing that can name the person. Checked against her phone: every one of
// the 207 statuses stored on it had come through unmatched, showing whatever the poster calls
// themselves on WhatsApp rather than the name she knows them by.
func TestAStatusPosterIsNamedFromTheDirectory(t *testing.T) {
	lid, err := types.ParseJID("2345678901@lid")
	if err != nil {
		t.Fatalf("unusable test jid: %v", err)
	}
	number, err := types.ParseJID("41791234567@s.whatsapp.net")
	if err != nil {
		t.Fatalf("unusable test jid: %v", err)
	}

	directory := func(query types.JID) (types.JID, bool) {
		if query.String() == "2345678901@lid" {
			return number, true
		}
		return types.JID{}, false
	}

	// No SenderAlt, which is what a status actually arrives with.
	got := messageSender(false, lid, types.JID{}, "status@broadcast", directory)
	if got != "41791234567@s.whatsapp.net" {
		t.Fatalf("a status poster should be named from the directory, got %q", got)
	}
}

// And an unknown LID must stay a LID rather than becoming an empty sender: a status from somebody
// the directory has never heard of is still worth showing under a name she does not recognise.
func TestAnUnknownHiddenSenderKeepsTheirLid(t *testing.T) {
	lid, err := types.ParseJID("2345678901@lid")
	if err != nil {
		t.Fatalf("unusable test jid: %v", err)
	}

	nothing := func(types.JID) (types.JID, bool) { return types.JID{}, false }

	got := messageSender(false, lid, types.JID{}, "status@broadcast", nothing)
	if got != "2345678901@lid" {
		t.Fatalf("an unknown hidden sender should keep their LID, got %q", got)
	}
}

// The chat, as opposed to the sender, was still being taken raw — and a LID-addressed chat is
// exactly the case where her own messages have nowhere else to get an address from. Tested on a
// real phone: a message written in the native WhatsApp app disappeared, and this is why.

func jid(t *testing.T, raw string) types.JID {
	t.Helper()
	parsed, err := types.ParseJID(raw)
	if err != nil {
		t.Fatalf("unusable test jid %q: %v", raw, err)
	}
	return parsed
}

func noLIDs(types.JID) (types.JID, bool) {
	return types.JID{}, false
}

func TestAChatAddressedByNumberIsLeftAlone(t *testing.T) {
	source := types.MessageSource{Chat: jid(t, "41791234567@s.whatsapp.net")}

	if got := chatAddress(source, noLIDs); got != "41791234567@s.whatsapp.net" {
		t.Fatalf("a chat with a number should keep it, got %q", got)
	}
}

func TestHerOwnMessageInAHiddenChatFindsTheNumberInTheRecipient(t *testing.T) {
	source := types.MessageSource{
		Chat:         jid(t, "2345678901@lid"),
		IsFromMe:     true,
		SenderAlt:    jid(t, "41799999999@s.whatsapp.net"), // hers, and the wrong answer
		RecipientAlt: jid(t, "41791234567@s.whatsapp.net"),
	}

	if got := chatAddress(source, noLIDs); got != "41791234567@s.whatsapp.net" {
		t.Fatalf("her own message should be filed under the person she wrote to, got %q", got)
	}
}

func TestSomebodyElsesMessageInAHiddenChatFindsTheNumberInTheSender(t *testing.T) {
	source := types.MessageSource{
		Chat:      jid(t, "2345678901@lid"),
		SenderAlt: jid(t, "41791234567@s.whatsapp.net"),
	}

	if got := chatAddress(source, noLIDs); got != "41791234567@s.whatsapp.net" {
		t.Fatalf("their message should be filed under their number, got %q", got)
	}
}

func TestAHiddenChatWithNoNumberOnItAsksTheDirectory(t *testing.T) {
	source := types.MessageSource{Chat: jid(t, "2345678901@lid"), IsFromMe: true}
	directory := func(lid types.JID) (types.JID, bool) {
		if lid.String() != "2345678901@lid" {
			return types.JID{}, false
		}
		return jid(t, "41791234567@s.whatsapp.net"), true
	}

	if got := chatAddress(source, directory); got != "41791234567@s.whatsapp.net" {
		t.Fatalf("the directory should have supplied the number, got %q", got)
	}
}

func TestAHiddenChatNobodyCanPlaceIsKeptRatherThanLost(t *testing.T) {
	source := types.MessageSource{Chat: jid(t, "2345678901@lid"), IsFromMe: true}

	// The whole bug in one line: returning "" here, or dropping the message, is what made her
	// half of those conversations vanish. A LID is a poor name and an honest one.
	if got := chatAddress(source, noLIDs); got != "2345678901@lid" {
		t.Fatalf("an unplaceable chat should keep its address, got %q", got)
	}
}

func TestAGroupKeepsItsOwnAddress(t *testing.T) {
	source := types.MessageSource{
		Chat:     jid(t, "1234567890-1234567890@g.us"),
		IsGroup:  true,
		IsFromMe: true,
	}

	if got := chatAddress(source, noLIDs); got != "1234567890-1234567890@g.us" {
		t.Fatalf("a group has no telephone number and should keep its address, got %q", got)
	}
}

func TestAHistoricConversationIsPlacedTheSameWayAsALiveOne(t *testing.T) {
	directory := func(types.JID) (types.JID, bool) {
		return jid(t, "41791234567@s.whatsapp.net"), true
	}

	// Live and history have to agree, or the message she sent this morning arrives a second time
	// tonight under a name she does not recognise.
	if got := historicChatAddress("2345678901@lid", directory); got != "41791234567@s.whatsapp.net" {
		t.Fatalf("a historic LID conversation should be placed by number, got %q", got)
	}
	if got := historicChatAddress("41791234567@s.whatsapp.net", noLIDs); got != "41791234567@s.whatsapp.net" {
		t.Fatalf("a historic conversation with a number should keep it, got %q", got)
	}
}

func TestAnAddressThatIsNotAJIDIsKeptAsItArrived(t *testing.T) {
	// ParseJID accepts almost anything, so this comes back through the ordinary path rather than
	// the error branch. Either way nothing may be invented: an address the app made up is a
	// message sent to nobody, reported as sent.
	if got := historicChatAddress("not a jid", noLIDs); got != "not a jid" {
		t.Fatalf("nothing should be invented for an address that is not a JID, got %q", got)
	}
}

// Quote context tests.
//
// A message answering an earlier message carries ContextInfo with the stanza id of the quoted
// message, who wrote it, and a copy of what they said. These tests verify that the bridge extracts
// context across all message kinds that support it, resolves hidden LIDs to phone numbers, and
// leaves events byte-for-byte untouched when there is no quote.

func TestContextInfoOfReturnsNilWhenAbsent(t *testing.T) {
	if got := contextInfoOf(nil); got != nil {
		t.Fatalf("nil message should return nil ContextInfo, got %v", got)
	}
	msg := &waE2E.Message{Conversation: proto.String("Hallo")}
	if got := contextInfoOf(msg); got != nil {
		t.Fatalf("plain conversation message has no ContextInfo, got %v", got)
	}
}

func TestContextInfoOfExtractsFromAllSixMessageKinds(t *testing.T) {
	ctx := &waE2E.ContextInfo{
		StanzaID:    proto.String("STANZA-001"),
		Participant: proto.String("41791234567@s.whatsapp.net"),
	}

	kinds := []*waE2E.Message{
		{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String("Ja"), ContextInfo: ctx}},
		{ImageMessage: &waE2E.ImageMessage{Caption: proto.String("Bild"), ContextInfo: ctx}},
		{VideoMessage: &waE2E.VideoMessage{Caption: proto.String("Film"), ContextInfo: ctx}},
		{AudioMessage: &waE2E.AudioMessage{ContextInfo: ctx}},
		{DocumentMessage: &waE2E.DocumentMessage{FileName: proto.String("doc.pdf"), ContextInfo: ctx}},
		{StickerMessage: &waE2E.StickerMessage{ContextInfo: ctx}},
	}

	for i, msg := range kinds {
		extracted := contextInfoOf(msg)
		if extracted == nil {
			t.Fatalf("kind index %d should have returned ContextInfo", i)
		}
		if extracted.GetStanzaID() != "STANZA-001" {
			t.Fatalf("kind index %d returned stanza id %q, wanted STANZA-001", i, extracted.GetStanzaID())
		}
	}
}

func TestPopulateQuoteNoQuoteLeavesEventUntouched(t *testing.T) {
	ev := outEvent{"type": "message", "text": "Hallo"}
	msg := &waE2E.Message{Conversation: proto.String("Hallo")}

	populateQuote(ev, msg, noLIDs)

	if _, ok := ev["quotedId"]; ok {
		t.Error("quotedId should not be set when there is no quote")
	}
	if _, ok := ev["quotedSender"]; ok {
		t.Error("quotedSender should not be set when there is no quote")
	}
	if _, ok := ev["quotedText"]; ok {
		t.Error("quotedText should not be set when there is no quote")
	}

	// Also when ContextInfo is present but has an empty StanzaID.
	ev2 := outEvent{"type": "message", "text": "Hallo"}
	msg2 := &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text:        proto.String("Hallo"),
			ContextInfo: &waE2E.ContextInfo{StanzaID: proto.String("")},
		},
	}
	populateQuote(ev2, msg2, noLIDs)
	if len(ev2) != 2 {
		t.Fatalf("empty stanza id should leave event untouched, got %v", ev2)
	}
}

func TestPopulateQuoteExtractsQuoteWithResolvedSenderAndText(t *testing.T) {
	msg := &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: proto.String("Ja, gerne!"),
			ContextInfo: &waE2E.ContextInfo{
				StanzaID:    proto.String("3EB0A1B2C3"),
				Participant: proto.String("41791234567@s.whatsapp.net"),
				QuotedMessage: &waE2E.Message{
					Conversation: proto.String("Kommst du am Sonntag zum Essen?"),
				},
			},
		},
	}

	ev := outEvent{"type": "message", "text": "Ja, gerne!"}
	populateQuote(ev, msg, noLIDs)

	if ev["quotedId"] != "3EB0A1B2C3" {
		t.Errorf("expected quotedId '3EB0A1B2C3', got %v", ev["quotedId"])
	}
	if ev["quotedSender"] != "41791234567@s.whatsapp.net" {
		t.Errorf("expected quotedSender '41791234567@s.whatsapp.net', got %v", ev["quotedSender"])
	}
	if ev["quotedText"] != "Kommst du am Sonntag zum Essen?" {
		t.Errorf("expected quotedText 'Kommst du am Sonntag zum Essen?', got %v", ev["quotedText"])
	}
}

func TestPopulateQuoteResolvesLIDParticipantThroughDirectory(t *testing.T) {
	directory := func(query types.JID) (types.JID, bool) {
		if query.String() == "2345678901@lid" {
			pn, _ := types.ParseJID("41791234567@s.whatsapp.net")
			return pn, true
		}
		return types.JID{}, false
	}

	msg := &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: proto.String("Alles klar"),
			ContextInfo: &waE2E.ContextInfo{
				StanzaID:    proto.String("STANZA-LID"),
				Participant: proto.String("2345678901@lid"),
				QuotedMessage: &waE2E.Message{
					Conversation: proto.String("Bis morgen"),
				},
			},
		},
	}

	ev := outEvent{"type": "message"}
	populateQuote(ev, msg, directory)

	if ev["quotedSender"] != "41791234567@s.whatsapp.net" {
		t.Fatalf("quoted LID sender should be resolved via directory to phone number, got %v", ev["quotedSender"])
	}
}

func TestPopulateQuoteQuotedPhotoExtractsFotoPlaceholder(t *testing.T) {
	msg := &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: proto.String("Sehr schön!"),
			ContextInfo: &waE2E.ContextInfo{
				StanzaID:    proto.String("PHOTO-1"),
				Participant: proto.String("41791234567@s.whatsapp.net"),
				QuotedMessage: &waE2E.Message{
					ImageMessage: &waE2E.ImageMessage{
						Caption: proto.String("Schau mal der Garten"),
					},
				},
			},
		},
	}

	ev := outEvent{"type": "message"}
	populateQuote(ev, msg, noLIDs)

	if ev["quotedText"] != "[Foto] Schau mal der Garten" {
		t.Fatalf("quoted photo should extract placeholder with caption, got %v", ev["quotedText"])
	}
}

func TestPopulateQuoteQuotedAudioExtractsSprachnachrichtPlaceholder(t *testing.T) {
	msg := &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: proto.String("Danke"),
			ContextInfo: &waE2E.ContextInfo{
				StanzaID:    proto.String("AUDIO-1"),
				Participant: proto.String("41791234567@s.whatsapp.net"),
				QuotedMessage: &waE2E.Message{
					AudioMessage: &waE2E.AudioMessage{},
				},
			},
		},
	}

	ev := outEvent{"type": "message"}
	populateQuote(ev, msg, noLIDs)

	if ev["quotedText"] != "[Sprachnachricht]" {
		t.Fatalf("quoted audio should extract placeholder, got %v", ev["quotedText"])
	}
}

func TestPopulateQuoteTruncatesLongQuotedTextAt240Runes(t *testing.T) {
	longText := strings.Repeat("A", 300)
	msg := &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: proto.String("Gelesen"),
			ContextInfo: &waE2E.ContextInfo{
				StanzaID:    proto.String("LONG-1"),
				Participant: proto.String("41791234567@s.whatsapp.net"),
				QuotedMessage: &waE2E.Message{
					Conversation: proto.String(longText),
				},
			},
		},
	}

	ev := outEvent{"type": "message"}
	populateQuote(ev, msg, noLIDs)

	got, ok := ev["quotedText"].(string)
	if !ok {
		t.Fatalf("quotedText was not populated: %v", ev)
	}
	if len([]rune(got)) != 240 {
		t.Fatalf("quotedText should be capped at 240 runes, got %d runes", len([]rune(got)))
	}
}

func TestPopulateQuoteUnparseableParticipantIsOmitted(t *testing.T) {
	msg := &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: proto.String("Antwort"),
			ContextInfo: &waE2E.ContextInfo{
				StanzaID:    proto.String("STANZA-NO-PART"),
				Participant: proto.String(""),
				QuotedMessage: &waE2E.Message{
					Conversation: proto.String("Ursprüngliche Frage"),
				},
			},
		},
	}

	ev := outEvent{"type": "message"}
	populateQuote(ev, msg, noLIDs)

	if ev["quotedId"] != "STANZA-NO-PART" {
		t.Errorf("quotedId should be populated, got %v", ev["quotedId"])
	}
	if _, ok := ev["quotedSender"]; ok {
		t.Errorf("quotedSender should be omitted when participant is empty, got %v", ev["quotedSender"])
	}
	if ev["quotedText"] != "Ursprüngliche Frage" {
		t.Errorf("quotedText should be populated, got %v", ev["quotedText"])
	}
}

// Avatar profile picture tests.

func TestAvatarFileNameUsesDigitsForUserJID(t *testing.T) {
	jid, err := types.ParseJID("41791234567@s.whatsapp.net")
	if err != nil {
		t.Fatalf("unusable jid: %v", err)
	}

	targetJID := jid.ToNonAD()
	userDigits := onlyDigits(targetJID.User)
	if userDigits != "41791234567" {
		t.Fatalf("expected userDigits 41791234567, got %q", userDigits)
	}

	safeName := safeFileName(targetJID.String())
	if userDigits != "" {
		safeName = userDigits
	}
	if safeName != "41791234567" {
		t.Fatalf("expected avatar filename 41791234567, got %q", safeName)
	}
}

func TestAvatarFileNameForCompanionDeviceJIDResolvesToUser(t *testing.T) {
	jid, err := types.ParseJID("41791234567:2@s.whatsapp.net")
	if err != nil {
		t.Fatalf("unusable companion jid: %v", err)
	}

	targetJID := jid.ToNonAD()
	userDigits := onlyDigits(targetJID.User)
	if userDigits != "41791234567" {
		t.Fatalf("companion device JID should resolve to user digits 41791234567, got %q", userDigits)
	}
}

func TestCommandParsesParticipantForGroupReaction(t *testing.T) {
	raw := `{"cmd":"react","to":"1234567890-1234567890@g.us","emoji":"❤️","messageId":"3EB0","fromMe":false,"participant":"41791234567@s.whatsapp.net"}`
	var cmd command
	if err := json.Unmarshal([]byte(raw), &cmd); err != nil {
		t.Fatalf("failed to unmarshal command: %v", err)
	}
	if cmd.Participant != "41791234567@s.whatsapp.net" {
		t.Fatalf("expected participant 41791234567@s.whatsapp.net, got %q", cmd.Participant)
	}
	if cmd.To != "1234567890-1234567890@g.us" || cmd.Emoji != "❤️" || cmd.MessageID != "3EB0" || cmd.FromMe {
		t.Fatalf("unexpected command fields: %+v", cmd)
	}

	raw1to1 := `{"cmd":"react","to":"41791234567@s.whatsapp.net","emoji":"❤️","messageId":"3EB0","fromMe":false}`
	var cmd1to1 command
	if err := json.Unmarshal([]byte(raw1to1), &cmd1to1); err != nil {
		t.Fatalf("failed to unmarshal 1:1 command: %v", err)
	}
	if cmd1to1.Participant != "" {
		t.Fatalf("expected empty participant for 1:1 reaction, got %q", cmd1to1.Participant)
	}
}

func TestARingingCallIsRejectedOnlyOnce(t *testing.T) {
	book := &callBook{calls: map[string]types.BasicCallMeta{}}
	book.remember(types.BasicCallMeta{CallID: "c1", Timestamp: time.Now()})

	if _, ok := book.take("c1"); !ok {
		t.Fatal("a ringing call should be there to reject")
	}
	if _, ok := book.take("c1"); ok {
		t.Fatal("a call already taken must not be rejected a second time")
	}
}

func TestACallThatEndedCannotBeRejected(t *testing.T) {
	book := &callBook{calls: map[string]types.BasicCallMeta{}}
	book.remember(types.BasicCallMeta{CallID: "c1", Timestamp: time.Now()})
	book.forget("c1")

	if _, ok := book.take("c1"); ok {
		t.Fatal("a call that is over is not ringing any more")
	}
}

func TestAnOfferNobodyEndedIsForgottenAfterAnHour(t *testing.T) {
	book := &callBook{calls: map[string]types.BasicCallMeta{}}
	book.remember(types.BasicCallMeta{CallID: "old", Timestamp: time.Now().Add(-2 * time.Hour)})
	book.remember(types.BasicCallMeta{CallID: "new", Timestamp: time.Now()})

	if _, ok := book.take("old"); ok {
		t.Fatal("an offer two hours old should have been dropped")
	}
	if _, ok := book.take("new"); !ok {
		t.Fatal("the current offer must survive the pruning")
	}
}

func TestAnOfferWithoutAnIdIsNotKept(t *testing.T) {
	book := &callBook{calls: map[string]types.BasicCallMeta{}}
	book.remember(types.BasicCallMeta{Timestamp: time.Now()})
	if len(book.calls) != 0 {
		t.Fatal("a call with no id can never be named again and must not be kept")
	}
}

// The resolver's servers belong to whichever network the phone is on. On 2026-09-17 she left the
// flat, the bridge kept asking the router, and WhatsApp stayed offline until the app restarted.

func TestABareDNSAddressGetsThePortDNSListensOn(t *testing.T) {
	got := parseDNSServers(" 213.55.128.104 , 2001:db8::1,,192.168.1.1:5353")
	want := []string{"213.55.128.104:53", "[2001:db8::1]:53", "192.168.1.1:5353"}
	if !sameAddresses(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
}

// fakeDNS counts the queries that reach it; it never answers, which is all a lookup needs to
// prove where it went.
func fakeDNS(t *testing.T) (string, *atomic.Int32) {
	t.Helper()
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	var hits atomic.Int32
	go func() {
		buf := make([]byte, 512)
		for {
			if _, _, err := conn.ReadFrom(buf); err != nil {
				return
			}
			hits.Add(1)
		}
	}()
	return conn.LocalAddr().String(), &hits
}

func lookupBriefly() {
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	_, _ = net.DefaultResolver.LookupHost(ctx, "web.whatsapp.com")
}

func TestLookupsFollowTheNetworkSheMovedTo(t *testing.T) {
	router, atHome := fakeDNS(t)
	operator, onTheStreet := fakeDNS(t)

	if !setDNSServers(router) {
		t.Fatal("the first servers were not taken")
	}
	lookupBriefly()
	if atHome.Load() == 0 {
		t.Fatal("a lookup at home did not reach the router")
	}

	if !setDNSServers(operator) {
		t.Fatal("moving to another network was not taken as a change")
	}
	before := atHome.Load()
	lookupBriefly()
	if onTheStreet.Load() == 0 {
		t.Fatal("a lookup after leaving home did not reach the operator's server")
	}
	if atHome.Load() != before {
		t.Fatal("a lookup after leaving home still went to the router")
	}
}

func TestTheSameOrNoServersChangeNothing(t *testing.T) {
	server, _ := fakeDNS(t)
	setDNSServers(server)
	if setDNSServers(server) {
		t.Fatal("the same servers again were reported as a change")
	}
	if setDNSServers(" , ") {
		t.Fatal("an empty list replaced servers that worked")
	}
	if got := *dnsAddresses.Load(); !sameAddresses(got, []string{server}) {
		t.Fatalf("servers are now %v", got)
	}
}

// Who is in a WhatsApp group, which the bridge withheld until 2026-09-20 and now sends — see
// [emitGroupInfo] for why the original reasoning was wrong.

// nobody stands for "not linked yet", the state the bridge spends its first minutes in.
var nobody = types.JID{}

func TestEveryMemberIsReportedByTheirNumber(t *testing.T) {
	participants := []types.GroupParticipant{
		{JID: jid(t, "41791234567@s.whatsapp.net")},
		{JID: jid(t, "41799876543@s.whatsapp.net")},
	}

	got := groupMembers(participants, nobody, noLIDs)

	if len(got) != 2 || got[0] != "41791234567@s.whatsapp.net" || got[1] != "41799876543@s.whatsapp.net" {
		t.Fatalf("every member should be reported by their number, got %v", got)
	}
}

func TestAHiddenMemberIsReportedByTheNumberBesideIt(t *testing.T) {
	// The phone matches people to her address book by telephone number and nothing else, so a
	// member sent as a LID is a member she is shown as a stranger.
	participants := []types.GroupParticipant{
		{JID: jid(t, "2345678901@lid"), PhoneNumber: jid(t, "41791234567@s.whatsapp.net")},
	}

	got := groupMembers(participants, nobody, noLIDs)

	if len(got) != 1 || got[0] != "41791234567@s.whatsapp.net" {
		t.Fatalf("a hidden member should be reported by their number, got %v", got)
	}
}

func TestAHiddenMemberWithNoNumberOnItAsksTheDirectory(t *testing.T) {
	number := jid(t, "41791234567@s.whatsapp.net")
	directory := func(query types.JID) (types.JID, bool) {
		if query.String() == "2345678901@lid" {
			return number, true
		}
		return types.JID{}, false
	}

	got := groupMembers([]types.GroupParticipant{{JID: jid(t, "2345678901@lid")}}, nobody, directory)

	if len(got) != 1 || got[0] != "41791234567@s.whatsapp.net" {
		t.Fatalf("the directory should name a hidden member, got %v", got)
	}
}

func TestAMemberNobodyCanPlaceIsStillAMember(t *testing.T) {
	// Left out, she would be one person short in the count and — worse — anything that person
	// wrote would be drawn as coming from somebody who is not in the group. Kept, the phone shows
	// them as somebody with no name, which is the truth.
	got := groupMembers([]types.GroupParticipant{{JID: jid(t, "2345678901@lid")}}, nobody, noLIDs)

	if len(got) != 1 || got[0] != "2345678901@lid" {
		t.Fatalf("a member nobody can place should still be a member, got %v", got)
	}
}

func TestOnePersonIsOneMember(t *testing.T) {
	// The same person arriving as two participants, which WhatsApp does while a group is being
	// migrated to LIDs. Twice in the list is twice in the faces and twice in "und 5 weitere".
	participants := []types.GroupParticipant{
		{JID: jid(t, "41791234567@s.whatsapp.net")},
		{JID: jid(t, "2345678901@lid"), PhoneNumber: jid(t, "41791234567@s.whatsapp.net")},
	}

	got := groupMembers(participants, nobody, noLIDs)

	if len(got) != 1 || got[0] != "41791234567@s.whatsapp.net" {
		t.Fatalf("one person should be one member, got %v", got)
	}
}

func TestAParticipantWithNoAddressAtAllIsNotAMember(t *testing.T) {
	got := groupMembers([]types.GroupParticipant{{}}, nobody, noLIDs)

	if len(got) != 0 {
		t.Fatalf("a participant with no address is nobody, got %v", got)
	}
}

func TestSheIsNotOneOfTheSeveralPeople(t *testing.T) {
	// The faces under a group are the people she is writing to. Her own among them would be one
	// stranger's worth of room taken by a photograph of herself, and one too many in the count.
	me := jid(t, "41790000000@s.whatsapp.net")
	participants := []types.GroupParticipant{
		{JID: me},
		{JID: jid(t, "41791234567@s.whatsapp.net")},
	}

	got := groupMembers(participants, me, noLIDs)

	if len(got) != 1 || got[0] != "41791234567@s.whatsapp.net" {
		t.Fatalf("she is not one of the several people, got %v", got)
	}
}

func TestSheIsRecognisedBehindHerHiddenAddress(t *testing.T) {
	// Her own row in a migrated group arrives as a LID like everybody else's.
	me := jid(t, "41790000000@s.whatsapp.net")
	directory := func(query types.JID) (types.JID, bool) {
		if query.String() == "9990000001@lid" {
			return me, true
		}
		return types.JID{}, false
	}
	participants := []types.GroupParticipant{
		{JID: jid(t, "9990000001@lid")},
		{JID: jid(t, "41791234567@s.whatsapp.net")},
	}

	got := groupMembers(participants, me, directory)

	if len(got) != 1 || got[0] != "41791234567@s.whatsapp.net" {
		t.Fatalf("she is not one of the several people, got %v", got)
	}
}

func TestBeforeSheHasLinkedNobodyIsHer(t *testing.T) {
	participants := []types.GroupParticipant{{JID: jid(t, "41791234567@s.whatsapp.net")}}

	got := groupMembers(participants, nobody, noLIDs)

	if len(got) != 1 {
		t.Fatalf("an empty own address should take nobody out, got %v", got)
	}
}

// Sends to one person go out in the order she made them: a photo, then the sentence typed under it.
func TestSendsToOnePersonKeepTheirOrder(t *testing.T) {
	queues := newSendQueues()
	var mu sync.Mutex
	var order []int
	done := make(chan struct{})
	for i := 0; i < 5; i++ {
		i := i
		if !queues.enqueue("lisa", func() {
			mu.Lock()
			order = append(order, i)
			mu.Unlock()
			if i == 4 {
				close(done)
			}
		}) {
			t.Fatalf("send %d was refused", i)
		}
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("the queue never finished")
	}
	mu.Lock()
	defer mu.Unlock()
	for i, got := range order {
		if got != i {
			t.Fatalf("sends ran out of order: %v", order)
		}
	}
}

// A film still uploading for one person must not hold up a text to somebody else.
func TestASlowSendDoesNotHoldUpSomebodyElse(t *testing.T) {
	queues := newSendQueues()
	release := make(chan struct{})
	defer close(release)
	queues.enqueue("lisa", func() { <-release })

	other := make(chan struct{})
	queues.enqueue("peter", func() { close(other) })
	select {
	case <-other:
	case <-time.After(2 * time.Second):
		t.Fatal("a send to somebody else waited behind a slow one")
	}
}

// A full queue says so instead of blocking the reader that turns calls away.
func TestAFullQueueRefusesRatherThanBlocks(t *testing.T) {
	queues := newSendQueues()
	release := make(chan struct{})
	defer close(release)
	started := make(chan struct{})
	queues.enqueue("lisa", func() { close(started); <-release })
	<-started
	for i := 0; i < sendQueueDepth; i++ {
		if !queues.enqueue("lisa", func() {}) {
			t.Fatalf("send %d refused before the queue was full", i)
		}
	}
	if queues.enqueue("lisa", func() {}) {
		t.Fatal("a send was accepted into a full queue")
	}
}

// Every answer to a send names the request it answers, so the app cannot take a reaction's
// "sent" for the text behind it.
func TestASendResultCarriesItsRequest(t *testing.T) {
	cmd := command{Cmd: "send", To: "41791234567@s.whatsapp.net", Ref: "r-1"}
	ev := sendResult(cmd, outEvent{"type": "send_failed", "message": "x"})
	if ev["ref"] != "r-1" || ev["to"] != cmd.To || ev["type"] != "send_failed" {
		t.Fatalf("unexpected result: %v", ev)
	}

	var parsed command
	if err := json.Unmarshal([]byte(`{"cmd":"send","to":"a@s.whatsapp.net","text":"hi","ref":"r-2"}`), &parsed); err != nil {
		t.Fatal(err)
	}
	if parsed.Ref != "r-2" {
		t.Fatalf("ref not parsed: %+v", parsed)
	}
}

func TestTheSameGroupIsOnlySaidOnce(t *testing.T) {
	forgetWhatWasSaid()

	if alreadySaid("group 1@g.us", `{"name":"Familie"}`) {
		t.Fatal("the first report of a group is news")
	}
	if !alreadySaid("group 1@g.us", `{"name":"Familie"}`) {
		t.Fatal("the same report again is not news")
	}
	if alreadySaid("group 1@g.us", `{"name":"Die Familie"}`) {
		t.Fatal("a renamed group is news")
	}
	if alreadySaid("group 2@g.us", `{"name":"Die Familie"}`) {
		t.Fatal("another group with the same content is still news")
	}
}

func TestALinkToAnotherAccountStartsFromNothing(t *testing.T) {
	forgetWhatWasSaid()
	alreadySaid("avatar 1@s.whatsapp.net", "a.jpg 1 1")
	groupListDone(at(0))

	forgetWhatWasSaid()

	if alreadySaid("avatar 1@s.whatsapp.net", "a.jpg 1 1") {
		t.Fatal("what was said about the old account says nothing about the new one")
	}
	if !groupListDue(at(1)) {
		t.Fatal("a newly linked account's groups should be listed at once")
	}
}

func TestHerGroupsAreNotListedOnEveryConnection(t *testing.T) {
	forgetWhatWasSaid()

	if !groupListDue(at(0)) {
		t.Fatal("the first connection should list her groups")
	}
	groupListDone(at(0))

	// The socket that dropped once a minute all day.
	for minute := 1; minute < 6*60; minute++ {
		if groupListDue(at(minute)) {
			t.Fatalf("her groups were listed again after %d minutes", minute)
		}
	}
	if !groupListDue(at(6 * 60)) {
		t.Fatal("after six hours the list should be read again")
	}
}

func TestARefusedListIsNotAskedForAgainStraightAway(t *testing.T) {
	forgetWhatWasSaid()

	// Asked, and never reported as done: WhatsApp said 429.
	if !groupListDue(at(0)) {
		t.Fatal("the first connection should list her groups")
	}
	if groupListDue(at(1)) || groupListDue(at(9)) {
		t.Fatal("a refused list should not be asked for again on the next connection")
	}
	if !groupListDue(at(10)) {
		t.Fatal("a refused list should be tried again after ten minutes")
	}
}

func TestAnUnchangedPictureIsOnlyAnnouncedOnce(t *testing.T) {
	forgetWhatWasSaid()
	path := filepath.Join(t.TempDir(), "41791234567.jpg")
	if err := os.WriteFile(path, []byte("one"), 0o600); err != nil {
		t.Fatal(err)
	}

	seen := capture(t, func() {
		emitAvatar("41791234567@s.whatsapp.net", path)
		emitAvatar("41791234567@s.whatsapp.net", path)
	})
	if strings.Count(seen, `"type":"avatar"`) != 1 {
		t.Fatalf("the same picture should be announced once, got %q", seen)
	}

	if err := os.WriteFile(path, []byte("another"), 0o600); err != nil {
		t.Fatal(err)
	}
	seen = capture(t, func() { emitAvatar("41791234567@s.whatsapp.net", path) })
	if strings.Count(seen, `"type":"avatar"`) != 1 {
		t.Fatalf("a new picture should be announced, got %q", seen)
	}
}

func TestAnUnchangedGroupIsOnlyReportedOnce(t *testing.T) {
	forgetWhatWasSaid()
	group := &types.GroupInfo{
		JID:          jid(t, "120363000000000001@g.us"),
		GroupName:    types.GroupName{Name: "Familie"},
		Participants: []types.GroupParticipant{{JID: jid(t, "41791234567@s.whatsapp.net")}},
	}

	seen := capture(t, func() {
		emitGroupInfo(group, nobody, noLIDs)
		emitGroupInfo(group, nobody, noLIDs)
	})
	if strings.Count(seen, `"type":"group"`) != 1 {
		t.Fatalf("an unchanged group should be reported once, got %q", seen)
	}

	group.Participants = append(group.Participants, types.GroupParticipant{JID: jid(t, "41799876543@s.whatsapp.net")})
	seen = capture(t, func() { emitGroupInfo(group, nobody, noLIDs) })
	if strings.Count(seen, `"type":"group"`) != 1 {
		t.Fatalf("a group somebody joined should be reported again, got %q", seen)
	}
}

// capture returns what was emitted while fn ran.
func capture(t *testing.T, fn func()) string {
	t.Helper()
	var buffer bytes.Buffer
	stdoutMu.Lock()
	previous := out
	out = bufio.NewWriter(&buffer)
	stdoutMu.Unlock()
	defer func() {
		stdoutMu.Lock()
		out = previous
		stdoutMu.Unlock()
	}()
	fn()
	return buffer.String()
}

func TestNobodysAddressReachesTheLog(t *testing.T) {
	line := cautiousLine("warn", "Failed to handle %s from %s after %d tries: %v", []any{
		&waBinary.Node{Tag: "message", Attrs: waBinary.Attrs{"from": "41791234567@s.whatsapp.net"}},
		jid(t, "41791234567@s.whatsapp.net"),
		3,
		errors.New("no session with 41791234567@s.whatsapp.net (lid 123456789012345)"),
	})

	for _, private := range []string{"4179", "whatsapp.net", "123456789012345"} {
		if strings.Contains(line, private) {
			t.Fatalf("%q should not be in a log line: %s", private, line)
		}
	}
	if !strings.Contains(line, "after 3 tries") || !strings.Contains(line, "no session with <address>") {
		t.Fatalf("what went wrong should still be readable: %s", line)
	}
}

func TestTheReasonASocketClosedIsKept(t *testing.T) {
	line := cautiousLine("error Socket", "Error reading from websocket: %v", []any{
		errors.New("failed to read frame header: read tcp 10.20.30.40:51234->157.240.0.60:443: read: connection reset by peer"),
	})

	if !strings.Contains(line, "connection reset by peer") {
		t.Fatalf("the reason should survive: %s", line)
	}
}

func TestALongDumpIsCutShort(t *testing.T) {
	got := scrub(strings.Repeat("goroutine stack\n", 100))

	if len([]rune(got)) > scrubbedLength+1 || strings.Contains(got, "\n") {
		t.Fatalf("a dump should arrive as one short line, got %d runes", len([]rune(got)))
	}
}

func TestANodeIsDescribedByItsTagsAlone(t *testing.T) {
	node := &waBinary.Node{
		Tag:     "stream:error",
		Attrs:   waBinary.Attrs{"code": "500", "jid": "41791234567@s.whatsapp.net"},
		Content: []waBinary.Node{{Tag: "conflict", Attrs: waBinary.Attrs{"type": "replaced"}}},
	}

	if got := nodeShape(node); got != "stream:error>conflict" {
		t.Fatalf("unexpected shape %q", got)
	}
	if got := nodeShape(nil); got != "none" {
		t.Fatalf("no node should be described as none, got %q", got)
	}
}

// socksServer accepts one SOCKS5 CONNECT at a time and joins it to wherever it was asked to go.
func socksServer(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				head := make([]byte, 3)
				if _, err := io.ReadFull(conn, head); err != nil {
					return
				}
				conn.Write([]byte{5, 0})
				request := make([]byte, 5)
				if _, err := io.ReadFull(conn, request); err != nil || request[3] != 3 {
					return
				}
				name := make([]byte, int(request[4])+2)
				if _, err := io.ReadFull(conn, name); err != nil {
					return
				}
				host := string(name[:len(name)-2])
				port := int(name[len(name)-2])<<8 | int(name[len(name)-1])
				// The name must arrive unresolved: "far.invalid" exists nowhere but here.
				if host != "far.invalid" {
					return
				}
				target, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
				if err != nil {
					return
				}
				defer target.Close()
				conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
				go io.Copy(target, conn)
				io.Copy(conn, target)
			}()
		}
	}()
	return listener.Addr().String()
}

// greeter is the far end: it says one word to whoever reaches it.
func greeter(t *testing.T) (port string) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			conn.Write([]byte("hello"))
			conn.Close()
		}
	}()
	_, port, _ = net.SplitHostPort(listener.Addr().String())
	return port
}

func heard(t *testing.T, conn net.Conn, err error) string {
	t.Helper()
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	said, _ := io.ReadAll(conn)
	return string(said)
}

func TestTheDetourCarriesTheSocketAndTheNameUnresolved(t *testing.T) {
	file := filepath.Join(t.TempDir(), detourFileName)
	os.WriteFile(file, []byte(socksServer(t)+"\n"), 0o600)
	var roads []string
	d := &detour{file: file, direct: &net.Dialer{Timeout: time.Second}, tell: func(road string) { roads = append(roads, road) }}
	port := greeter(t)

	conn, err := d.DialContext(context.Background(), "tcp", "far.invalid:"+port)
	said := heard(t, conn, err)
	notes := strings.Join(roads, "\n")
	if said != "hello" {
		t.Fatalf("the far end was not reached through the detour: %q", said)
	}
	if !strings.Contains(notes, "through the detour") {
		t.Fatalf("the road taken was not noted: %s", notes)
	}
}

func TestAnUnreachableDetourLeavesHerOnTheDirectRoad(t *testing.T) {
	gone, _ := net.Listen("tcp", "127.0.0.1:0")
	address := gone.Addr().String()
	gone.Close()
	file := filepath.Join(t.TempDir(), detourFileName)
	os.WriteFile(file, []byte(address), 0o600)
	var roads []string
	d := &detour{file: file, direct: &net.Dialer{Timeout: time.Second}, tell: func(road string) { roads = append(roads, road) }}
	port := greeter(t)

	conn, err := d.DialContext(context.Background(), "tcp", "127.0.0.1:"+port)
	said := heard(t, conn, err)
	notes := strings.Join(roads, "\n")
	if said != "hello" {
		t.Fatalf("with the detour down she was not connected directly: %q", said)
	}
	if !strings.Contains(notes, "detour unreachable") {
		t.Fatalf("the fallback was not noted: %s", notes)
	}
}

func TestRemovingTheFileEndsTheDetourAtTheNextDial(t *testing.T) {
	file := filepath.Join(t.TempDir(), detourFileName)
	os.WriteFile(file, []byte(socksServer(t)), 0o600)
	var roads []string
	d := &detour{file: file, direct: &net.Dialer{Timeout: time.Second}, tell: func(road string) { roads = append(roads, road) }}
	port := greeter(t)
	os.Remove(file)

	conn, err := d.DialContext(context.Background(), "tcp", "127.0.0.1:"+port)
	heard(t, conn, err)
	conn, err = d.DialContext(context.Background(), "tcp", "127.0.0.1:"+port)
	heard(t, conn, err)
	notes := strings.Join(roads, "\n")
	if strings.Count(notes, "detour file gone") != 1 {
		t.Fatalf("the same road should be noted once, not on every dial: %s", notes)
	}
}

func TestAFileThatIsNotAnAddressIsNoDetour(t *testing.T) {
	file := filepath.Join(t.TempDir(), detourFileName)
	os.WriteFile(file, []byte("yes please"), 0o600)
	if via, pinned := detourPlan(file); via != "" || pinned != "" {
		t.Fatalf("%q / %q was taken for a plan", via, pinned)
	}
	os.WriteFile(file, []byte("pin somewhere"), 0o600)
	if via, pinned := detourPlan(file); via != "" || pinned != "" {
		t.Fatalf("%q / %q was taken for a plan", via, pinned)
	}
}

func TestAPinnedAddressIsDialledInPlaceOfTheName(t *testing.T) {
	file := filepath.Join(t.TempDir(), detourFileName)
	os.WriteFile(file, []byte("pin 127.0.0.1\n"), 0o600)
	var roads []string
	d := &detour{file: file, direct: &net.Dialer{Timeout: time.Second}, tell: func(road string) { roads = append(roads, road) }}
	port := greeter(t)

	// "far.invalid" resolves nowhere, so reaching the greeter proves the address was swapped.
	conn, err := d.DialContext(context.Background(), "tcp", "far.invalid:"+port)
	if said := heard(t, conn, err); said != "hello" {
		t.Fatalf("the pinned address was not reached: %q", said)
	}
	if strings.Join(roads, "\n") != "direct, to the pinned address" {
		t.Fatalf("the road taken was not noted: %v", roads)
	}
}

func TestAnUnreachablePinnedAddressLeavesHerOnTheOrdinaryRoad(t *testing.T) {
	file := filepath.Join(t.TempDir(), detourFileName)
	// Another loopback address, where nothing listens on this port.
	os.WriteFile(file, []byte("pin 127.0.0.2"), 0o600)
	var roads []string
	d := &detour{file: file, direct: &net.Dialer{Timeout: time.Second}, tell: func(road string) { roads = append(roads, road) }}
	gone, _ := net.Listen("tcp", "127.0.0.1:0")
	_, port, _ := net.SplitHostPort(gone.Addr().String())

	// 127.0.0.2 has no listener on this port, 127.0.0.1 has: the ordinary road must be taken.
	accepted := make(chan struct{})
	go func() {
		if conn, err := gone.Accept(); err == nil {
			conn.Close()
			close(accepted)
		}
	}()
	conn, err := d.DialContext(context.Background(), "tcp", "127.0.0.1:"+port)
	if err != nil {
		t.Fatalf("with the pinned address down she was not connected: %v", err)
	}
	conn.Close()
	<-accepted
	gone.Close()
	if strings.Join(roads, "\n") != "direct, pinned address unreachable" {
		t.Fatalf("the fallback was not noted: %v", roads)
	}
}

func TestTheWireTallySaysWhatWasLeftWithoutAnAck(t *testing.T) {
	tr := newTraffic()
	at := time.Date(2026, 10, 15, 5, 30, 0, 0, time.UTC)
	from := types.NewJID("41790000000", types.DefaultUserServer)
	in := func(tag, typ, id string) *waBinary.Node {
		return &waBinary.Node{Tag: tag, Attrs: waBinary.Attrs{"type": typ, "id": id, "from": from}}
	}
	tr.saw(true, in("message", "text", "M1"), at)
	tr.saw(true, in("notification", "devices", "N1"), at.Add(time.Second))
	tr.saw(false, &waBinary.Node{Tag: "ack", Attrs: waBinary.Attrs{"class": "message", "id": "M1", "to": from}}, at.Add(2*time.Second))
	tr.saw(false, &waBinary.Node{Tag: "iq", Attrs: waBinary.Attrs{"type": "get", "id": "Q1"}}, at.Add(3*time.Second))
	tr.saw(false, &waBinary.Node{Tag: "iq", Attrs: waBinary.Attrs{"type": "set", "id": "Q2"}}, at.Add(4*time.Second))
	tr.saw(true, in("iq", "result", "Q1"), at.Add(5*time.Second))

	said := tr.summary(at.Add(57 * time.Second))
	for _, want := range []string{"not acked notification(devices)×1;", "iq unanswered iq(set)×1;", "last in 52s, last out 53s"} {
		if !strings.Contains(said, want) {
			t.Errorf("summary lacks %q: %s", want, said)
		}
	}
	if strings.Contains(said, "41790000000") || strings.Contains(said, "M1") || strings.Contains(said, "@") {
		t.Errorf("summary carries an address or an id: %s", said)
	}
	if again := tr.summary(at.Add(58 * time.Second)); !strings.HasPrefix(again, "in none; out none; not acked none") {
		t.Errorf("the next connection did not start at zero: %s", again)
	}
}

func TestAWordThatIsNotPlainStaysOutOfTheKind(t *testing.T) {
	node := &waBinary.Node{Tag: "notification", Attrs: waBinary.Attrs{"type": "41790000000@s.whatsapp.net"}}
	if kind := nodeKind(node); kind != "notification" {
		t.Errorf("kind %q", kind)
	}
}

func TestOnlyTheRecvAndSendLoggersFeedTheTally(t *testing.T) {
	wire.summary(time.Now())
	node := &waBinary.Node{Tag: "ib", Attrs: waBinary.Attrs{}}
	cautiousLog{module: "Client"}.Debugf("%s", node)
	cautiousLog{module: "Recv"}.Debugf("%s", node)
	if said := wire.summary(time.Now()); !strings.HasPrefix(said, "in ib×1; out none") {
		t.Errorf("%s", said)
	}
}

func TestAReadReceiptNamesTheMessagesAndTheReader(t *testing.T) {
	reader := types.NewJID("41765550000", types.DefaultUserServer)
	when := time.UnixMilli(1_763_000_000_000)
	e := receiptEvent(&events.Receipt{
		MessageSource: types.MessageSource{Chat: reader, Sender: reader},
		MessageIDs:    []types.MessageID{"M1", "", "M2"},
		Timestamp:     when,
		Type:          types.ReceiptTypeRead,
	}, nil)
	if e == nil {
		t.Fatal("a read receipt was dropped")
	}
	if e["type"] != "receipt" || e["state"] != "read" || e["from"] != reader.String() || e["chat"] != reader.String() {
		t.Fatalf("unexpected event %v", e)
	}
	if ids := e["messageIds"].([]string); len(ids) != 2 || ids[0] != "M1" || ids[1] != "M2" {
		t.Fatalf("unexpected ids %v", ids)
	}
	if e["timestamp"] != when.UnixMilli() {
		t.Fatalf("unexpected timestamp %v", e["timestamp"])
	}
}

func TestADeliveryReceiptIsReportedAsDelivered(t *testing.T) {
	reader := types.NewJID("41765550000", types.DefaultUserServer)
	e := receiptEvent(&events.Receipt{
		MessageSource: types.MessageSource{Chat: reader, Sender: reader},
		MessageIDs:    []types.MessageID{"M1"},
		Type:          types.ReceiptTypeDelivered,
	}, nil)
	if e == nil || e["state"] != "delivered" {
		t.Fatalf("unexpected event %v", e)
	}
}

func TestAReceiptUnderALIDNamesTheTelephoneNumber(t *testing.T) {
	lid := types.NewJID("2345678901", types.HiddenUserServer)
	pn := types.NewJID("41765550000", types.DefaultUserServer)
	e := receiptEvent(&events.Receipt{
		MessageSource: types.MessageSource{Chat: lid, Sender: lid, SenderAlt: pn},
		MessageIDs:    []types.MessageID{"M1"},
		Type:          types.ReceiptTypePlayed,
	}, nil)
	if e == nil || e["from"] != pn.String() || e["chat"] != pn.String() || e["state"] != "played" {
		t.Fatalf("unexpected event %v", e)
	}
}

func TestReceiptsForHerOwnReadingOrHousekeepingAreNotReported(t *testing.T) {
	someone := types.NewJID("41765550000", types.DefaultUserServer)
	for _, r := range []*events.Receipt{
		{MessageSource: types.MessageSource{Chat: someone, Sender: someone, IsFromMe: true}, MessageIDs: []types.MessageID{"M1"}, Type: types.ReceiptTypeRead},
		{MessageSource: types.MessageSource{Chat: someone, Sender: someone}, MessageIDs: []types.MessageID{"M1"}, Type: types.ReceiptTypeReadSelf},
		{MessageSource: types.MessageSource{Chat: someone, Sender: someone}, MessageIDs: []types.MessageID{"M1"}, Type: types.ReceiptTypeRetry},
		{MessageSource: types.MessageSource{Chat: someone, Sender: someone}, MessageIDs: []types.MessageID{"M1"}, Type: types.ReceiptTypeSender},
		{MessageSource: types.MessageSource{Chat: someone, Sender: someone}, MessageIDs: []types.MessageID{""}, Type: types.ReceiptTypeRead},
	} {
		if e := receiptEvent(r, nil); e != nil {
			t.Fatalf("receipt %q fromMe=%v should not be reported, got %v", r.Type, r.IsFromMe, e)
		}
	}
}
