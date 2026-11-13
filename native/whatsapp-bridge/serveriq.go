package main

import (
	"context"
	"sort"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	waBinary "go.mau.fi/whatsmeow/binary"
	"go.mau.fi/whatsmeow/types"
)

// whatsmeow answers only two questions the server can ask — pair-device and pair-success — and
// drops every other incoming iq get or set without a word. On an affected device each connection that the
// server closes after 57 s carried exactly one such question; the one that stayed up for hours
// through the detour carried none. A server that waits about a minute for an answer and then hangs
// up fits that, so every question is now answered, and its namespace written down.
//
// A ping gets the empty result XMPP asks for. Anything else gets "feature-not-implemented": an
// honest no is better than an empty yes to a question nobody here has read.

// answeredByWhatsmeow are the iq children whatsmeow replies to itself; see handleIQ in its pair.go.
var answeredByWhatsmeow = map[string]bool{"pair-device": true, "pair-success": true}

// serverQuestion is the reply an incoming node needs, or nil if whatsmeow answers it or it is no
// question at all. ok reports whether it was a question from the server.
func serverQuestion(node *waBinary.Node) (reply *waBinary.Node, ok bool) {
	if node == nil || node.Tag != "iq" {
		return nil, false
	}
	if typ := attrString(node, "type"); typ != "get" && typ != "set" {
		return nil, false
	}
	if from, _ := node.Attrs["from"].(types.JID); from != types.ServerJID {
		return nil, false
	}
	if children := node.GetChildren(); len(children) == 1 && answeredByWhatsmeow[children[0].Tag] {
		return nil, true
	}
	id := attrString(node, "id")
	ping := attrString(node, "xmlns") == "urn:xmpp:ping"
	// The server's ping carries no id at all — seen in field testing, 112 times, each one
	// followed 12 s later by the server closing the socket. It is answered without one; anything
	// else without an id has nothing a reply could name.
	if id == "" && !ping {
		return nil, true
	}
	answer := waBinary.Node{
		Tag:   "iq",
		Attrs: waBinary.Attrs{"to": types.ServerJID, "type": "result"},
	}
	if id != "" {
		answer.Attrs["id"] = id
	}
	if !ping {
		answer.Attrs["type"] = "error"
		answer.Content = []waBinary.Node{{
			Tag:   "error",
			Attrs: waBinary.Attrs{"code": 501, "text": "feature-not-implemented"},
		}}
	}
	return &answer, true
}

// questionShape names a server question by its type, namespace and children's tags: protocol
// words, never ids or addresses.
func questionShape(node *waBinary.Node) string {
	shape := nodeKind(node)
	if xmlns := attrString(node, "xmlns"); plainNamespace(xmlns) {
		shape += " xmlns=" + xmlns
	} else if xmlns != "" {
		shape += " xmlns=<other>"
	}
	return shape + " " + nodeShape(node) + " attrs=" + attrNames(node)
}

// attrNames lists a node's attribute names, never their values: which fields the server filled
// in is what a reply may have to echo.
func attrNames(node *waBinary.Node) string {
	names := make([]string, 0, len(node.Attrs))
	for key := range node.Attrs {
		if plainNamespace(key) {
			names = append(names, key)
		} else {
			names = append(names, "<other>")
		}
	}
	sort.Strings(names)
	return strings.Join(names, ",")
}

// plainNamespace accepts the short protocol words WhatsApp uses as namespaces ("w:p",
// "urn:xmpp:ping", "encrypt") and nothing that could carry a number or an address.
func plainNamespace(s string) bool {
	if s == "" || len(s) > 40 {
		return false
	}
	for _, r := range s {
		if !(r >= 'a' && r <= 'z' || r == ':' || r == '_' || r == '-' || r == '.') {
			return false
		}
	}
	return true
}

// serverAsks is installed into the Recv logger, which sees every node before whatsmeow handles
// it. Nil until the client exists, and in tests.
var serverAsks func(node *waBinary.Node)

// answerServerQuestions sends every server question its reply. The send runs off the reading
// goroutine, which is the one that hands nodes to the logger.
func answerServerQuestions(client *whatsmeow.Client) {
	serverAsks = func(node *waBinary.Node) {
		reply, ok := serverQuestion(node)
		if !ok {
			return
		}
		age := "?"
		if since := connectedSince.Load(); since != 0 {
			age = time.Since(time.Unix(0, since)).Round(time.Second).String()
		}
		if reply == nil {
			note("server asked %s, %s after login; not answered here", questionShape(node), age)
			return
		}
		note("server asked %s, %s after login; answering %s", questionShape(node), age, attrString(reply, "type"))
		go func(reply waBinary.Node) {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			if err := client.DangerousInternals().SendNode(ctx, reply); err != nil {
				note("answering the server failed: %s", scrub(err.Error()))
			}
		}(*reply)
	}
}
