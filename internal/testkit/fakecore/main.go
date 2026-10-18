// Command fakecore is an offline test fixture. It only forwards to loopback targets.
package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"
)

type config struct {
	Behavior string `json:"test_behavior"`
	Inbounds []struct {
		Listen   string `json:"listen"`
		Port     int    `json:"port"`
		Settings struct {
			Accounts []struct {
				User string `json:"user"`
				Pass string `json:"pass"`
			} `json:"accounts"`
		} `json:"settings"`
	} `json:"inbounds"`
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "version" {
		fmt.Println("Xray test-fixture ac5d8a8")
		return
	}
	var path string
	test := false
	for i, a := range os.Args {
		if a == "-test" {
			test = true
		}
		if a == "-c" && i+1 < len(os.Args) {
			path = os.Args[i+1]
		}
	}
	b, e := os.ReadFile(path)
	if e != nil {
		os.Exit(23)
	}
	var cfg config
	if json.Unmarshal(b, &cfg) != nil || len(cfg.Inbounds) != 1 || len(cfg.Inbounds[0].Settings.Accounts) != 1 {
		os.Exit(23)
	}
	if cfg.Behavior == "invalid" {
		os.Exit(23)
	}
	if test {
		return
	}
	if cfg.Behavior == "crash" {
		os.Exit(24)
	}
	if cfg.Behavior == "delay" {
		time.Sleep(2 * time.Second)
	}
	if cfg.Behavior == "ignore-term" {
		signal.Ignore(syscall.SIGTERM)
	}
	i := cfg.Inbounds[0]
	l, e := net.Listen("tcp4", net.JoinHostPort(i.Listen, strconv.Itoa(i.Port)))
	if e != nil {
		os.Exit(25)
	}
	for {
		c, e := l.Accept()
		if e != nil {
			return
		}
		go serve(c, i.Settings.Accounts[0].User, i.Settings.Accounts[0].Pass)
	}
}
func serve(c net.Conn, user, pass string) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(30 * time.Second))
	b := make([]byte, 2)
	if _, e := io.ReadFull(c, b); e != nil || b[0] != 5 {
		return
	}
	methods := make([]byte, int(b[1]))
	if _, e := io.ReadFull(c, methods); e != nil {
		return
	}
	c.Write([]byte{5, 2})
	if _, e := io.ReadFull(c, b); e != nil || b[0] != 1 {
		return
	}
	u := make([]byte, int(b[1]))
	if _, e := io.ReadFull(c, u); e != nil {
		return
	}
	if _, e := io.ReadFull(c, b[:1]); e != nil {
		return
	}
	p := make([]byte, int(b[0]))
	if _, e := io.ReadFull(c, p); e != nil {
		return
	}
	if string(u) != user || string(p) != pass {
		c.Write([]byte{1, 1})
		return
	}
	c.Write([]byte{1, 0})
	h := make([]byte, 4)
	if _, e := io.ReadFull(c, h); e != nil || (h[1] != 1 && h[1] != 3) {
		return
	}
	host := ""
	switch h[3] {
	case 1:
		x := make([]byte, 4)
		io.ReadFull(c, x)
		host = net.IP(x).String()
	case 4:
		x := make([]byte, 16)
		io.ReadFull(c, x)
		host = net.IP(x).String()
	case 3:
		io.ReadFull(c, b[:1])
		x := make([]byte, int(b[0]))
		io.ReadFull(c, x)
		host = string(x)
	default:
		return
	}
	io.ReadFull(c, b)
	port := int(binary.BigEndian.Uint16(b))
	if h[1] == 3 {
		relay(c)
		return
	}
	ip := net.ParseIP(host)
	if host != "localhost" && (ip == nil || !ip.IsLoopback()) {
		c.Write([]byte{5, 2, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	up, e := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), time.Second)
	if e != nil {
		c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer up.Close()
	c.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0})
	c.SetDeadline(time.Time{})
	done := make(chan struct{})
	go func() {
		io.Copy(up, c)
		if x, ok := up.(*net.TCPConn); ok {
			x.CloseWrite()
		}
		close(done)
	}()
	io.Copy(c, up)
	c.Close()
	<-done
}

func relay(control net.Conn) {
	l, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		return
	}
	defer l.Close()
	p := l.LocalAddr().(*net.UDPAddr).Port
	control.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, byte(p >> 8), byte(p)})
	go func() { io.Copy(io.Discard, control); l.Close() }()
	for {
		b := make([]byte, 2048)
		n, client, e := l.ReadFromUDP(b)
		if e != nil {
			return
		}
		if n < 10 || b[0] != 0 || b[1] != 0 || b[2] != 0 || b[3] != 1 {
			continue
		}
		target := &net.UDPAddr{IP: net.IP(b[4:8]), Port: int(binary.BigEndian.Uint16(b[8:10]))}
		if !target.IP.IsLoopback() {
			continue
		}
		up, e := net.DialUDP("udp4", nil, target)
		if e != nil {
			continue
		}
		up.SetDeadline(time.Now().Add(time.Second))
		up.Write(b[10:n])
		response := make([]byte, 2048)
		count, e := up.Read(response)
		up.Close()
		if e == nil {
			l.WriteToUDP(append(b[:10], response[:count]...), client)
		}
	}
}
