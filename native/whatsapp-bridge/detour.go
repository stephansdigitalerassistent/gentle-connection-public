package main

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"

	"go.mau.fi/whatsmeow"
	"golang.org/x/net/proxy"
)

// A detour is a diagnostic, not a feature: it changes the road the WhatsApp socket takes so that
// one question can be answered on one phone — who closes her connection every minute on mobile
// data? Nothing switches it on but a file in app-private storage, which only this app (or a
// developer with a debuggable build) can write. The file holds one line:
//
//	127.0.0.1:18443      through a SOCKS proxy at that address
//	pin 157.240.254.60   straight to that server address instead of the one the name resolves to
const detourFileName = "whatsapp-detour"

// detourDialTimeout is short on purpose. The proxy sits at the far end of a debugging tunnel that
// comes and goes; when it is gone she must be back on the direct road within seconds.
const detourDialTimeout = 8 * time.Second

// detour dials through the proxy named in its file and goes direct whenever that cannot be done.
// The file is read on every dial, so removing it ends the experiment at the next connection
// without restarting anything.
type detour struct {
	file   string
	direct *net.Dialer
	// lastRoad remembers what was last noted, so a socket that reconnects every minute does not
	// write the same line every minute.
	lastRoad atomic.Value
	// tell is where a change of road is reported; [note] outside tests.
	tell func(road string)
}

// takeDetourIfAsked installs the detour only when its file exists as the bridge starts. Without
// the file the client keeps whatsmeow's own dialling untouched, which is every phone but the one
// under test.
func takeDetourIfAsked(client *whatsmeow.Client, storePath string) {
	file := filepath.Join(filepath.Dir(storePath), detourFileName)
	if via, pinned := detourPlan(file); via == "" && pinned == "" {
		return
	}
	d := &detour{file: file, direct: &net.Dialer{Timeout: 30 * time.Second, KeepAlive: 30 * time.Second}, tell: noteRoad}
	// The websocket alone. Photographs keep the direct road: they are not what is being measured
	// and a debugging tunnel is no place for them.
	client.SetSOCKSProxy(d, whatsmeow.SetProxyOptions{NoMedia: true})
	note("detour armed")
}

// detourPlan reads the file: a proxy to go through, or an address to go to, or neither when there
// is no usable file.
func detourPlan(file string) (proxyAddress, pinnedAddress string) {
	raw, err := os.ReadFile(file)
	if err != nil {
		return "", ""
	}
	line := strings.TrimSpace(string(raw))
	if pinned, ok := strings.CutPrefix(line, "pin "); ok {
		if ip := net.ParseIP(strings.TrimSpace(pinned)); ip != nil {
			return "", ip.String()
		}
		return "", ""
	}
	if _, _, err := net.SplitHostPort(line); err != nil {
		return "", ""
	}
	return line, ""
}

func (d *detour) Dial(network, address string) (net.Conn, error) {
	return d.DialContext(context.Background(), network, address)
}

func (d *detour) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	via, pinned := detourPlan(d.file)
	switch {
	case via != "":
		if conn, err := dialThrough(ctx, via, network, address); err == nil {
			d.say("through the detour")
			return conn, nil
		}
		d.say("direct, detour unreachable")
	case pinned != "":
		// The name still travels in the TLS handshake above this; only the address is swapped.
		if _, port, err := net.SplitHostPort(address); err == nil {
			ctx, cancel := context.WithTimeout(ctx, detourDialTimeout)
			conn, err := d.direct.DialContext(ctx, network, net.JoinHostPort(pinned, port))
			cancel()
			if err == nil {
				d.say("direct, to the pinned address")
				return conn, nil
			}
		}
		d.say("direct, pinned address unreachable")
	default:
		d.say("direct, detour file gone")
	}
	return d.direct.DialContext(ctx, network, address)
}

// dialThrough asks the proxy to open the connection, handing it the name rather than an address so
// that the lookup, too, happens on the far side.
func dialThrough(ctx context.Context, via, network, address string) (net.Conn, error) {
	socks, err := proxy.SOCKS5("tcp", via, nil, &net.Dialer{Timeout: detourDialTimeout})
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, detourDialTimeout)
	defer cancel()
	return socks.(proxy.ContextDialer).DialContext(ctx, network, address)
}

func (d *detour) say(road string) {
	if previous, _ := d.lastRoad.Swap(road).(string); previous != road {
		d.tell(road)
	}
}

func noteRoad(road string) {
	note("socket road: %s", road)
}
