// FrpcDash 端到端协议测试台（仅用 Go 标准库，无需任何外部依赖）。
//
// 两个模式：
//   go run harness.go serve   启动各协议所需的真实后端（TCP/UDP 回声、HTTP、HTTPS）
//   go run harness.go check   经 frps + frpc 隧道逐协议真实访问并校验
//
// 拓扑（全部本机）：
//
//	checker --(frps 端口)--> frps <--(控制连接)-- frpc(A: 8 种 proxy)  --> 后端
//	checker --(visitor 端口)--> frpc(B: stcp/xtcp/sudp visitor) --> frps --> frpc(A) --> 后端
package main

import (
	"bufio"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"strings"
	"time"
)

const (
	// 后端监听端口（serve 模式）
	backendTCP   = 18001 // tcp 代理后端
	backendUDP   = 18002 // udp 代理后端
	backendHTTP  = 18003 // http 代理后端
	backendHTTPS = 18004 // https 代理后端（真 TLS）
	backendSTCP  = 18005 // stcp 后端
	backendXTCP  = 18006 // xtcp 后端
	backendSUDP  = 18007 // sudp 后端（UDP）
	backendMUX   = 18008 // tcpmux 后端

	// frps 监听端口
	frpsHTTPPort  = 17080
	frpsHTTPSPort = 17443
	// tcpmux(httpconnect) 专用端口：frps 需要在服务端显式开启 tcpmuxHTTPConnectPort
	frpsMuxPort = 17081

	// 本机最小 STUN 服务（xtcp 打洞要做 NAT 发现，默认公网 STUN 在 CI 里不可达）
	stunPort    = 17900
	stunPortAlt = 17901

	// frps 侧映射出的端口（check 模式访问）
	remoteTCP   = 17001
	remoteUDP   = 17002
	visitorSTCP = 17005
	visitorXTCP = 17006
	visitorSUDP = 17007

	httpDomain  = "e2e-http.test"
	httpsDomain = "e2e-https.test"
	muxDomain   = "e2e-mux.test"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: harness serve|check")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "serve":
		serve()
	case "check":
		os.Exit(check())
	default:
		fmt.Fprintln(os.Stderr, "unknown mode:", os.Args[1])
		os.Exit(2)
	}
}

// ------------------------------------------------------------------ 后端

func serve() {
	mustTCP(backendTCP, echo)
	mustTCP(backendSTCP, echo)
	mustTCP(backendXTCP, echo)
	mustTCP(backendMUX, echo)
	mustUDP(backendUDP)
	mustUDP(backendSUDP)
	go stunServer()
	mustHTTP(backendHTTP, "http-backend", nil)
	cert, err := selfSigned()
	if err != nil {
		fatal("生成自签证书失败: %v", err)
	}
	mustHTTP(backendHTTPS, "https-backend", cert)
	// serve 只在父脚本里作为后台进程使用，等父进程杀掉即可
	fmt.Println("READY")
	select {}
}

func echo(conn net.Conn) {
	defer conn.Close()
	_, _ = io.Copy(conn, conn)
}

func mustTCP(port int, handler func(net.Conn)) {
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		fatal("监听 %s 失败: %v", addr, err)
	}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go handler(conn)
		}
	}()
}

func mustUDP(port int) {
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	pc, err := net.ListenPacket("udp", addr)
	if err != nil {
		fatal("监听 %s 失败: %v", addr, err)
	}
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = pc.WriteTo(buf[:n], from)
		}
	}()
}

func mustHTTP(port int, banner string, cert *tls.Certificate) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		_, _ = fmt.Fprintf(w, "%s:%s:%s", banner, r.Host, r.URL.Path)
	})
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		fatal("监听 %s 失败: %v", addr, err)
	}
	if cert != nil {
		ln = tls.NewListener(ln, &tls.Config{Certificates: []tls.Certificate{*cert}})
	}
	go func() { _ = srv.Serve(ln) }()
}

// stunServer 最小可用的 STUN Binding 服务（RFC 5389）。
//
// frp 的 nathole.Prepare 要求至少拿到 2 个外部地址（len(addrs) < 2 直接报
// "not enough addresses"），只有 STUN 响应里带 OTHER-ADDRESS 时它才会向该地址
// 再发一次 Binding 请求、从而得到第二个地址。所以这里起两个监听：
// 17900 主口（回 OTHER-ADDRESS 指向 17901）+ 17901 备口。
// CI/内网里公网 STUN 通常不可达，xtcp 会卡在 "wait response from stun server timeout"。
func stunServer() {
	startStunListener(stunPort, stunPortAlt)
	startStunListener(stunPortAlt, 0)
}

func startStunListener(port, otherPort int) {
	const magicCookie = 0x2112A442
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	pc, err := net.ListenPacket("udp", addr)
	if err != nil {
		fatal("STUN 监听 %s 失败: %v", addr, err)
	}
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			if n < 20 || binary.BigEndian.Uint16(buf[0:2]) != 0x0001 {
				continue // 只处理 Binding Request
			}
			if binary.BigEndian.Uint32(buf[4:8]) != magicCookie {
				continue
			}
			udpAddr, ok := from.(*net.UDPAddr)
			if !ok {
				continue
			}
			ip4 := udpAddr.IP.To4()
			if ip4 == nil {
				continue
			}
			cookie := make([]byte, 4)
			binary.BigEndian.PutUint32(cookie, magicCookie)

			attrLen := 12 // XOR-MAPPED-ADDRESS
			if otherPort > 0 {
				attrLen += 12 // OTHER-ADDRESS
			}
			resp := make([]byte, 0, 20+attrLen)
			resp = append(resp, 0x01, 0x01, byte(attrLen>>8), byte(attrLen)) // Binding Success Response
			resp = append(resp, cookie...)
			resp = append(resp, buf[8:20]...)                       // 原样回传 transaction id
			resp = append(resp, 0x00, 0x20, 0x00, 0x08, 0x00, 0x01) // XOR-MAPPED-ADDRESS (IPv4)
			xport := make([]byte, 2)
			binary.BigEndian.PutUint16(xport, uint16(udpAddr.Port)^uint16(magicCookie>>16))
			resp = append(resp, xport...)
			for i := 0; i < 4; i++ {
				resp = append(resp, ip4[i]^cookie[i])
			}
			if otherPort > 0 {
				// OTHER-ADDRESS（不 XOR）：备用 STUN 的地址
				resp = append(resp, 0x80, 0x2c, 0x00, 0x08, 0x00, 0x01,
					byte(otherPort>>8), byte(otherPort), 127, 0, 0, 1)
			}
			_, _ = pc.WriteTo(resp, from)
		}
	}()
}

func selfSigned() (*tls.Certificate, error) {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	tmpl := x509.Certificate{
		SerialNumber:          big.NewInt(time.Now().UnixNano()),
		Subject:               pkix.Name{CommonName: httpsDomain},
		DNSNames:              []string{httpsDomain},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &priv.PublicKey, priv)
	if err != nil {
		return nil, err
	}
	keyDER, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		return nil, err
	}
	pair, err := tls.X509KeyPair(
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}),
	)
	if err != nil {
		return nil, err
	}
	return &pair, nil
}

// ------------------------------------------------------------------ 校验

type result struct {
	name string
	err  error
}

func check() int {
	results := []result{
		{"tcp", tcpEcho(remoteTCP, "e2e-tcp", 1)},
		{"udp", udpEcho(remoteUDP, "e2e-udp", 1)},
		{"http", httpCheck()},
		{"https", httpsCheck()},
		{"stcp", tcpEcho(visitorSTCP, "e2e-stcp", 3)},
		{"xtcp", tcpEcho(visitorXTCP, "e2e-xtcp", 3)},
		{"tcpmux", tcpmuxCheck()},
		{"sudp", udpEcho(visitorSUDP, "e2e-sudp", 3)},
	}
	failed := 0
	for _, r := range results {
		if r.err != nil {
			failed++
			fmt.Printf("FAIL  %-7s %v\n", r.name, r.err)
		} else {
			fmt.Printf("PASS  %-7s\n", r.name)
		}
	}
	if failed > 0 {
		fmt.Printf("\n%d/%d 协议失败\n", failed, len(results))
	}
	return failed
}

func tcpEcho(port int, payload string, attempts int) error {
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	var lastErr error
	for i := 0; i < attempts; i++ {
		if i > 0 {
			time.Sleep(2 * time.Second)
		}
		lastErr = func() error {
			conn, err := net.DialTimeout("tcp", addr, 8*time.Second)
			if err != nil {
				return fmt.Errorf("连接 %s 失败: %w", addr, err)
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
			if _, err := conn.Write([]byte(payload)); err != nil {
				return fmt.Errorf("写入失败: %w", err)
			}
			buf := make([]byte, len(payload))
			if _, err := io.ReadFull(conn, buf); err != nil {
				return fmt.Errorf("读取回声失败: %w", err)
			}
			if string(buf) != payload {
				return fmt.Errorf("回声不匹配: 期望 %q 实际 %q", payload, string(buf))
			}
			return nil
		}()
		if lastErr == nil {
			return nil
		}
	}
	return lastErr
}

func udpEcho(port int, payload string, attempts int) error {
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	var lastErr error
	for i := 0; i < attempts; i++ {
		if i > 0 {
			time.Sleep(2 * time.Second)
		}
		lastErr = func() error {
			conn, err := net.Dial("udp", addr)
			if err != nil {
				return fmt.Errorf("连接 %s 失败: %w", addr, err)
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
			if _, err := conn.Write([]byte(payload)); err != nil {
				return fmt.Errorf("写入失败: %w", err)
			}
			buf := make([]byte, len(payload))
			n, err := conn.Read(buf)
			if err != nil {
				return fmt.Errorf("读取回声失败: %w", err)
			}
			if string(buf[:n]) != payload {
				return fmt.Errorf("回声不匹配: 期望 %q 实际 %q", payload, string(buf[:n]))
			}
			return nil
		}()
		if lastErr == nil {
			return nil
		}
	}
	return lastErr
}

func httpClient() *http.Client {
	return &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			Proxy:             nil, // 强制直连，避免被系统代理劫持
			DisableKeepAlives: true,
		},
	}
}

func httpCheck() error {
	req, err := http.NewRequest("GET", fmt.Sprintf("http://127.0.0.1:%d/hello", frpsHTTPPort), nil)
	if err != nil {
		return err
	}
	req.Host = httpDomain
	resp, err := httpClient().Do(req)
	if err != nil {
		return fmt.Errorf("请求失败: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	if resp.StatusCode != 200 {
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(body))
	}
	if !strings.Contains(string(body), "http-backend") {
		return fmt.Errorf("响应非预期后端: %s", string(body))
	}
	return nil
}

func httpsCheck() error {
	conn, err := tls.DialWithDialer(
		&net.Dialer{Timeout: 10 * time.Second},
		"tcp",
		fmt.Sprintf("127.0.0.1:%d", frpsHTTPSPort),
		&tls.Config{ServerName: httpsDomain, InsecureSkipVerify: true},
	)
	if err != nil {
		return fmt.Errorf("TLS 握手失败: %w", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	_, _ = fmt.Fprintf(conn, "GET /hello HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", httpsDomain)
	body, err := io.ReadAll(io.LimitReader(conn, 4096))
	if err != nil {
		return fmt.Errorf("读取响应失败: %w", err)
	}
	text := string(body)
	if !strings.Contains(text, "200") || !strings.Contains(text, "https-backend") {
		return fmt.Errorf("响应非预期: %s", firstLine(text))
	}
	return nil
}

// tcpmux（multiplexer = httpconnect）：在 vhost HTTP 端口上用 HTTP CONNECT 打开隧道
func tcpmuxCheck() error {
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", frpsMuxPort), 10*time.Second)
	if err != nil {
		return fmt.Errorf("连接失败: %w", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))

	_, _ = fmt.Fprintf(conn, "CONNECT %s:80 HTTP/1.1\r\nHost: %s\r\n\r\n", muxDomain, muxDomain)
	reader := bufio.NewReader(conn)
	status, err := reader.ReadString('\n')
	if err != nil {
		return fmt.Errorf("读取 CONNECT 响应失败: %w", err)
	}
	if !strings.Contains(status, "200") {
		return fmt.Errorf("CONNECT 未成功: %s", strings.TrimSpace(status))
	}
	// 吃掉响应头
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return fmt.Errorf("读取响应头失败: %w", err)
		}
		if line == "\r\n" || line == "\n" {
			break
		}
	}
	payload := "e2e-tcpmux"
	if _, err := conn.Write([]byte(payload)); err != nil {
		return fmt.Errorf("写入失败: %w", err)
	}
	buf := make([]byte, len(payload))
	if _, err := io.ReadFull(reader, buf); err != nil {
		return fmt.Errorf("读取回声失败: %w", err)
	}
	if string(buf) != payload {
		return fmt.Errorf("回声不匹配: %q", string(buf))
	}
	return nil
}

func firstLine(s string) string {
	if i := strings.IndexAny(s, "\r\n"); i > 0 {
		return s[:i]
	}
	return s
}

func fatal(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
