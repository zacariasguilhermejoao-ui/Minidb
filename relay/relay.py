#!/usr/bin/env python3
"""MiniDB Relay - ponte entre o telefone e os sites."""
import base64, json, os, queue, secrets, threading, time, uuid
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

PORT = int(os.environ.get("PORT", 9000))
HOST = os.environ.get("HOST", "0.0.0.0")
TOKEN_FILE = os.environ.get("RELAY_TOKEN_FILE", "relay_token.txt")
TOKEN = os.environ.get("RELAY_TOKEN")
if not TOKEN:
    if os.path.exists(TOKEN_FILE):
        TOKEN = open(TOKEN_FILE).read().strip()
    else:
        TOKEN = secrets.token_urlsafe(16)
        open(TOKEN_FILE, "w").write(TOKEN)

MAX_BODY = 25 * 1024 * 1024
OFFLINE_AFTER, WAIT_RESPONSE, POLL_WAIT = 45, 30, 25
queues, last_seen, pending = {}, {}, {}
lock = threading.Lock()

def q_for(t):
    with lock:
        return queues.setdefault(t, queue.Queue())

class H(BaseHTTPRequestHandler):
    server_version = "MiniDBRelay/1.0"
    def log_message(self, f, *a): pass
    def cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "apikey, authorization, content-type")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS")
    def out(self, code, body=b"", ctype="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body))); self.cors(); self.end_headers()
        if body: self.wfile.write(body)
    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY: raise ValueError("grande demais")
        return self.rfile.read(n) if n else b""
    def do_OPTIONS(self):
        self.send_response(204); self.cors(); self.send_header("Content-Length", "0"); self.end_headers()
    def do_GET(self): self.go()
    def do_POST(self): self.go()
    def do_PUT(self): self.go()
    def do_PATCH(self): self.go()
    def do_DELETE(self): self.go()
    def go(self):
        try:
            u = urlparse(self.path); parts = [p for p in u.path.split("/") if p]
            if not parts: return self.out(200, b"MiniDB Relay a funcionar", "text/plain; charset=utf-8")
            if parts[0] == "_poll" and len(parts) == 2: return self.poll(parts[1])
            if parts[0] == "_respond" and len(parts) == 3: return self.respond(parts[1], parts[2])
            if parts[0] == "t" and len(parts) >= 2: return self.forward(parts[1], u)
            self.out(404, {"error": "nao existe"})
        except Exception as e: self.out(500, {"error": str(e)})
    def auth(self): return self.headers.get("X-Relay-Token") == TOKEN
    def poll(self, tunnel):
        if not self.auth(): return self.out(401, {"error": "token errado"})
        last_seen[tunnel] = time.time()
        try: job = q_for(tunnel).get(timeout=POLL_WAIT)
        except queue.Empty:
            last_seen[tunnel] = time.time()
            self.send_response(204); self.cors(); self.send_header("Content-Length", "0"); self.end_headers(); return
        last_seen[tunnel] = time.time(); self.out(200, job)
    def respond(self, tunnel, jid):
        if not self.auth(): return self.out(401, {"error": "token errado"})
        data = json.loads(self.body() or b"{}")
        with lock: slot = pending.get(jid)
        if slot: slot["resp"] = data; slot["ev"].set()
        self.out(200, {"ok": True})
    def forward(self, tunnel, u):
        if time.time() - last_seen.get(tunnel, 0) > OFFLINE_AFTER:
            return self.out(503, {"error": "o telefone esta offline"})
        prefix = "/t/" + tunnel; rest = u.path[len(prefix):] or "/"
        if u.query: rest += "?" + u.query
        hdrs = {k.lower(): v for k, v in self.headers.items() if k.lower() in ("apikey", "authorization", "content-type")}
        jid = uuid.uuid4().hex; slot = {"ev": threading.Event(), "resp": None}
        with lock: pending[jid] = slot
        try:
            q_for(tunnel).put({"id": jid, "method": self.command, "path": rest, "headers": hdrs, "body": base64.b64encode(self.body()).decode()})
            if not slot["ev"].wait(WAIT_RESPONSE): return self.out(504, {"error": "o telefone nao respondeu a tempo"})
            r = slot["resp"]
            self.out(int(r.get("status", 500)), base64.b64decode(r.get("body", "")), r.get("type", "application/json; charset=utf-8"))
        finally:
            with lock: pending.pop(jid, None)

if __name__ == "__main__":
    print("=" * 52); print(" MiniDB Relay na porta %d" % PORT)
    print(" TOKEN (poe no app, em Conexao): %s" % TOKEN); print("=" * 52)
    ThreadingHTTPServer((HOST, PORT), H).serve_forever()
