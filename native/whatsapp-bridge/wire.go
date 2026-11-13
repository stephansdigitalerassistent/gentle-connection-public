package main

import (
	"fmt"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	waBinary "go.mau.fi/whatsmeow/binary"
)

// wire tallies what crossed the socket during one connection, by kind only, so that the note
// written when it closes can say what was still waiting for an answer.
//
// Diagnostic observation: its socket is closed by the far side 57 s after login,
// on every network it has been on — LTE, a home Wi-Fi, a hospital guest Wi-Fi — while the same
// build on the Fold keeps its socket for hours. A server that closes a client which has not
// acknowledged something does it on a timer like that. The tally holds node tags, the
// `type`/`class` word and, while a node waits for its answer, its id; it writes out only tags,
// words and counts. No addresses, no content.
var wire = newTraffic()

type traffic struct {
	mu      sync.Mutex
	in, out map[string]int
	// Incoming nodes WhatsApp expects an ack for, by class and id, until whatsmeow sends it.
	unacked map[string]string
	// Our iqs, by id, until WhatsApp answers them.
	unanswered      map[string]string
	lastIn, lastOut time.Time
}

func newTraffic() *traffic {
	t := &traffic{}
	t.reset()
	return t
}

func (t *traffic) reset() {
	t.in, t.out = map[string]int{}, map[string]int{}
	t.unacked, t.unanswered = map[string]string{}, map[string]string{}
	t.lastIn, t.lastOut = time.Time{}, time.Time{}
}

// needsAck is what WhatsApp waits to hear an ack for; see whatsmeow's sendAck.
var needsAck = map[string]bool{"message": true, "receipt": true, "notification": true, "call": true}

var plainWord = regexp.MustCompile(`^[a-z_-]{1,24}$`)

// nodeKind is the tag and, when it is a plain protocol word, the `type` (for an ack, the
// `class`) — "notification(devices)", "iq(result)", "ack(message)".
func nodeKind(node *waBinary.Node) string {
	attr := "type"
	if node.Tag == "ack" {
		attr = "class"
	}
	if word, ok := node.Attrs[attr].(string); ok && plainWord.MatchString(word) {
		return node.Tag + "(" + word + ")"
	}
	return node.Tag
}

func attrString(node *waBinary.Node, key string) string {
	s, _ := node.Attrs[key].(string)
	return s
}

func (t *traffic) saw(incoming bool, node *waBinary.Node, now time.Time) {
	if node == nil {
		return
	}
	kind, id := nodeKind(node), attrString(node, "id")
	t.mu.Lock()
	defer t.mu.Unlock()
	if incoming {
		t.in[kind]++
		t.lastIn = now
		switch {
		case needsAck[node.Tag] && id != "":
			t.unacked[node.Tag+"/"+id] = kind
		case node.Tag == "iq" && id != "":
			delete(t.unanswered, id)
		}
		return
	}
	t.out[kind]++
	t.lastOut = now
	switch node.Tag {
	case "ack":
		delete(t.unacked, attrString(node, "class")+"/"+id)
	case "receipt":
		// whatsmeow acknowledges a message with its delivery receipt, not an ack: the receipt
		// carries the message's id, and a receipt for several messages lists the rest as items.
		delete(t.unacked, "message/"+id)
		if list, ok := node.GetOptionalChildByTag("list"); ok {
			for _, item := range list.GetChildrenByTag("item") {
				delete(t.unacked, "message/"+attrString(&item, "id"))
			}
		}
	case "iq":
		if typ := attrString(node, "type"); (typ == "get" || typ == "set") && id != "" {
			t.unanswered[id] = kind
		}
	}
}

// summary describes the connection now ending, then forgets it: the next one starts at zero.
func (t *traffic) summary(now time.Time) string {
	t.mu.Lock()
	defer t.mu.Unlock()
	defer t.reset()
	unacked := map[string]int{}
	for _, kind := range t.unacked {
		unacked[kind]++
	}
	unanswered := map[string]int{}
	for _, kind := range t.unanswered {
		unanswered[kind]++
	}
	return fmt.Sprintf("in %s; out %s; not acked %s; iq unanswered %s; last in %s, last out %s before the close",
		counts(t.in), counts(t.out), counts(unacked), counts(unanswered), ago(t.lastIn, now), ago(t.lastOut, now))
}

func counts(m map[string]int) string {
	if len(m) == 0 {
		return "none"
	}
	kinds := make([]string, 0, len(m))
	for kind, n := range m {
		kinds = append(kinds, fmt.Sprintf("%s×%d", kind, n))
	}
	sort.Strings(kinds)
	return strings.Join(kinds, " ")
}

func ago(then, now time.Time) string {
	if then.IsZero() {
		return "never"
	}
	return now.Sub(then).Round(time.Second).String()
}
