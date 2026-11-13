package main

import (
	"strings"
	"testing"
	"time"

	waBinary "go.mau.fi/whatsmeow/binary"
	"go.mau.fi/whatsmeow/types"
)

func serverIQ(typ, id, xmlns string, children ...string) *waBinary.Node {
	node := &waBinary.Node{Tag: "iq", Attrs: waBinary.Attrs{"type": typ, "id": id, "from": types.ServerJID}}
	if xmlns != "" {
		node.Attrs["xmlns"] = xmlns
	}
	var kids []waBinary.Node
	for _, tag := range children {
		kids = append(kids, waBinary.Node{Tag: tag})
	}
	if kids != nil {
		node.Content = kids
	}
	return node
}

func TestAPingFromTheServerGetsAnEmptyResult(t *testing.T) {
	reply, ok := serverQuestion(serverIQ("get", "P1", "urn:xmpp:ping", "ping"))
	if !ok || reply == nil {
		t.Fatalf("no reply to a ping: ok=%v", ok)
	}
	if reply.Tag != "iq" || reply.Attrs["type"] != "result" || reply.Attrs["id"] != "P1" || reply.Attrs["to"] != types.ServerJID {
		t.Errorf("reply %v", reply.Attrs)
	}
	if reply.Content != nil {
		t.Errorf("a ping's result carries content: %v", reply.Content)
	}
}

func TestAPingWithoutAnIdIsAnsweredWithoutOne(t *testing.T) {
	reply, ok := serverQuestion(serverIQ("get", "", "urn:xmpp:ping"))
	if !ok || reply == nil {
		t.Fatalf("no reply to an id-less ping: ok=%v", ok)
	}
	if reply.Attrs["type"] != "result" || reply.Attrs["to"] != types.ServerJID {
		t.Errorf("reply %v", reply.Attrs)
	}
	if _, has := reply.Attrs["id"]; has {
		t.Errorf("reply invents an id: %v", reply.Attrs)
	}
}

func TestAnyOtherQuestionWithoutAnIdIsLeftUnanswered(t *testing.T) {
	if reply, ok := serverQuestion(serverIQ("get", "", "w:something")); !ok || reply != nil {
		t.Errorf("ok=%v reply=%v", ok, reply)
	}
}

func TestAnyOtherServerQuestionGetsANotImplemented(t *testing.T) {
	reply, ok := serverQuestion(serverIQ("set", "Q7", "w:something", "props"))
	if !ok || reply == nil {
		t.Fatalf("no reply: ok=%v", ok)
	}
	if reply.Attrs["type"] != "error" || reply.Attrs["id"] != "Q7" {
		t.Errorf("reply %v", reply.Attrs)
	}
	errs := reply.GetChildrenByTag("error")
	if len(errs) != 1 || errs[0].Attrs["code"] != 501 {
		t.Errorf("error child %v", reply.Content)
	}
}

func TestWhatWhatsmeowAnswersItselfIsLeftToIt(t *testing.T) {
	for _, child := range []string{"pair-device", "pair-success"} {
		reply, ok := serverQuestion(serverIQ("set", "X", "md", child))
		if !ok || reply != nil {
			t.Errorf("%s: ok=%v reply=%v", child, ok, reply)
		}
	}
}

func TestAnswersAndQuestionsFromOthersAreNoServerQuestions(t *testing.T) {
	someone := types.NewJID("41790000000", types.DefaultUserServer)
	cases := []*waBinary.Node{
		serverIQ("result", "R1", ""),
		serverIQ("error", "R2", ""),
		{Tag: "iq", Attrs: waBinary.Attrs{"type": "get", "id": "Y", "from": someone}},
		{Tag: "message", Attrs: waBinary.Attrs{"type": "text", "id": "M", "from": types.ServerJID}},
		nil,
	}
	for i, node := range cases {
		if reply, ok := serverQuestion(node); ok || reply != nil {
			t.Errorf("case %d: ok=%v reply=%v", i, ok, reply)
		}
	}
}

func TestTheShapeOfAQuestionNamesNoAddress(t *testing.T) {
	if got := questionShape(serverIQ("get", "P1", "urn:xmpp:ping", "ping")); got != "iq(get) xmlns=urn:xmpp:ping iq>ping attrs=from,id,type,xmlns" {
		t.Errorf("shape %q", got)
	}
	odd := serverIQ("get", "P2", "41790000000@s.whatsapp.net")
	if got := questionShape(odd); strings.Contains(got, "4179") || !strings.Contains(got, "xmlns=<other>") {
		t.Errorf("shape %q", got)
	}
}

func TestTheRecvLoggerHandsServerQuestionsOn(t *testing.T) {
	defer func() { serverAsks = nil }()
	var seen []string
	serverAsks = func(node *waBinary.Node) { seen = append(seen, node.Tag) }
	node := serverIQ("get", "P1", "urn:xmpp:ping")
	cautiousLog{module: "Send"}.Debugf("%s", node)
	cautiousLog{module: "Recv"}.Debugf("%s", node)
	if len(seen) != 1 {
		t.Errorf("handed on %d times", len(seen))
	}
	wire.summary(time.Now())
}

func TestAReceiptAcknowledgesTheMessagesItNames(t *testing.T) {
	tr := newTraffic()
	at := time.Date(2026, 10, 16, 6, 0, 0, 0, time.UTC)
	from := types.NewJID("41790000000", types.DefaultUserServer)
	for _, id := range []string{"M1", "M2", "M3", "M4"} {
		tr.saw(true, &waBinary.Node{Tag: "message", Attrs: waBinary.Attrs{"type": "media", "id": id, "from": from}}, at)
	}
	tr.saw(false, &waBinary.Node{Tag: "receipt", Attrs: waBinary.Attrs{"type": "inactive", "id": "M1", "to": from}}, at)
	tr.saw(false, &waBinary.Node{Tag: "receipt", Attrs: waBinary.Attrs{"id": "M2", "to": from}, Content: []waBinary.Node{
		{Tag: "list", Content: []waBinary.Node{{Tag: "item", Attrs: waBinary.Attrs{"id": "M3"}}}},
	}}, at)
	if said := tr.summary(at.Add(time.Second)); !strings.Contains(said, "not acked message(media)×1;") {
		t.Errorf("%s", said)
	}
}
