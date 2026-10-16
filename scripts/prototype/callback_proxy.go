// Isolated prototype TLS termination for the ordinary New API webhook.
// This program is a lab artifact, not part of either production application.
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

func main() {
	lab := flag.String("lab-dir", "", "private task directory with server.pem/server.key")
	flag.Parse()
	if *lab == "" {
		log.Fatal("private lab directory required")
	}
	transport := &http.Transport{Proxy: nil, DisableKeepAlives: true}
	client := &http.Client{Transport: transport, Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("redirect rejected") }}
	var lock sync.Mutex
	counts := map[string]int{"requests": 0, "upstream_success": 0, "rejected": 0, "dropped_responses": 0}
	record := func(name string) {
		lock.Lock()
		defer lock.Unlock()
		counts[name]++
		data, err := json.Marshal(counts)
		if err != nil {
			log.Fatal("counter encoding failed")
		}
		temp := filepath.Join(*lab, "callback-progress.tmp")
		if err := os.WriteFile(temp, data, 0600); err != nil {
			log.Fatal("counter persistence failed")
		}
		if err := os.Rename(temp, filepath.Join(*lab, "callback-progress.json")); err != nil {
			log.Fatal("counter rename failed")
		}
	}
	takeFault := func(name string) bool { return os.Remove(filepath.Join(*lab, name)) == nil }
	server := &http.Server{Addr: "127.0.0.1:8444", ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 15 * time.Second, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12}}
	server.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/user/epay/notify" || r.URL.RawPath != "" || (r.Method != http.MethodGet && r.Method != http.MethodPost) {
			http.NotFound(w, r)
			return
		}
		record("requests")
		if takeFault("callback-reject-once") {
			record("rejected")
			http.Error(w, "fail", http.StatusServiceUnavailable)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 32768)
		ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
		defer cancel()
		target := "http://127.0.0.1:3000/api/user/epay/notify"
		if r.URL.RawQuery != "" {
			target += "?" + r.URL.RawQuery
		}
		req, err := http.NewRequestWithContext(ctx, r.Method, target, r.Body)
		if err != nil {
			http.Error(w, "fail", 502)
			return
		}
		req.Header.Set("Content-Type", r.Header.Get("Content-Type"))
		response, err := client.Do(req)
		if err != nil {
			http.Error(w, "fail", 502)
			return
		}
		defer response.Body.Close()
		body, err := io.ReadAll(io.LimitReader(response.Body, 4097))
		if err != nil || len(body) > 4096 {
			http.Error(w, "fail", 502)
			return
		}
		if response.StatusCode == 200 && string(body) == "success" {
			record("upstream_success")
		}
		if takeFault("callback-drop-response-once") {
			record("dropped_responses")
			conn, _, err := w.(http.Hijacker).Hijack()
			if err == nil {
				conn.Close()
			}
			return
		}
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(response.StatusCode)
		w.Write(body)
	})
	log.Fatal(server.ListenAndServeTLS(filepath.Join(*lab, "server.pem"), filepath.Join(*lab, "server.key")))
}
