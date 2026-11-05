#!/usr/bin/env python3
"""Owned HTTPS fixture for Android environment acceptance; contains synthetic data only.

The fixture observes pages. It never remotely edits an already-open composer or scrolls it.
TLS credentials must be supplied from the runner's temporary directory, outside artifacts.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
import uuid

HOST = "environment-fixture.test"
PAGE_ACCOUNTS = {"/c/a1": "A", "/c/a2": "A", "/c/b1": "B", "/c/c1": "C"}
MARKERS = {account: "environment-" + account for account in ("A", "B", "C")}
CONTENTEDITABLE = {"/c/a2", "/c/c1"}
STORE_KEY = "environment_identity"
FILE_BYTES = (b"ChatGPT Studio synthetic environment download. No private account data.\n" * 3)[:128]
FIXED_DATE = "2026-01-15T12:00:00.000Z"


def stamp() -> int:
    return int(time.time() * 1000)


def env_cookie(header: str) -> tuple[bool, str | None]:
    # An existing empty or incorrect cookie is evidence, not a reason to replace it.
    for segment in header.split(";"):
        key, separator, value = segment.strip().partition("=")
        if key == "env_login":
            return True, value.strip() if separator else ""
    return False, None


class Evidence:
    def __init__(self, report: Path) -> None:
        self.report = report
        self.lock = threading.RLock()
        self.initial = {path: "" for path in PAGE_ACCOUNTS}
        self.state = {
            "schema": 1,
            "host": HOST,
            "startedAt": stamp(),
            "port": 0,
            "fixedDate": FIXED_DATE,
            "markers": MARKERS,
            "storageKey": STORE_KEY,
            "pages": {path: {} for path in PAGE_ACCOUNTS},
            "pageCounts": {path: 0 for path in PAGE_ACCOUNTS},
            "requests": {},
            "eventCount": 0,
            "rejectedEventCount": 0,
            "sendCount": 0,
            "sends": [],
            "downloads": [],
            "downloadBytes": len(FILE_BYTES),
            "downloadSha256": hashlib.sha256(FILE_BYTES).hexdigest(),
            "configuredInitialDrafts": dict(self.initial),
        }

    def persist(self) -> None:
        self.report.parent.mkdir(parents=True, exist_ok=True)
        next_path = self.report.with_name(self.report.name + ".new")
        with next_path.open("w", encoding="utf-8") as output:
            json.dump(self.state, output, ensure_ascii=False, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(next_path, self.report)

    def request(self, path: str, language: str, cookie: str) -> None:
        with self.lock:
            value = self.state["requests"].setdefault(path, {"count": 0, "firstAt": stamp()})
            value.update(count=value["count"] + 1, lastAt=stamp(), acceptLanguage=language,
                         cookie=cookie, envLogin=env_cookie(cookie)[1])
            if path in PAGE_ACCOUNTS:
                self.state["pageCounts"][path] += 1
            self.persist()

    def snapshot(self) -> bytes:
        with self.lock:
            return json.dumps(self.state, ensure_ascii=False).encode("utf-8")


WORKER_JS = r"""'use strict';
const fixedDate = '2026-01-15T12:00:00.000Z';
function sample() {
  return {language:navigator.language,languages:Array.from(navigator.languages),
    timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone,
    offsetMinutes:new Date(fixedDate).getTimezoneOffset(),fixedDate};
}
self.onmessage = () => self.postMessage(sample());
self.postMessage(sample());
"""

PAGE_SCRIPT = r"""'use strict';
const config = __CONFIG__;
const editor = document.getElementById('prompt-textarea');
const reading = document.getElementById('reading');
const doc = crypto.randomUUID();
let modelDraft = config.initialDraft;
let localValue = null, idbValue = null, localError = '', idbError = '';
let workerState = null, workerError = '';
let storageDatabase = null, idbBusy = false;
let inputCount = 0, trustedInputCount = 0, sendClicks = 0;
let pending = false, dirty = true, debounce = null;
let eventSequence = 0, sentEvents = 0;
function readDraft() { return 'value' in editor ? editor.value : editor.innerText; }
function renderDraft() {
  if ('value' in editor) editor.value = modelDraft;
  else editor.textContent = modelDraft;
}
renderDraft();
editor.addEventListener('input', event => {
  modelDraft = readDraft();
  inputCount += 1;
  if (event.isTrusted) trustedInputCount += 1;
  queue();
});
reading.addEventListener('scroll', queue, {passive:true});
window.addEventListener('resize', queue, {passive:true});
try {
  localValue = localStorage.getItem(config.storageKey);
  if (localValue === null) {
    localStorage.setItem(config.storageKey, config.marker);
    localValue = localStorage.getItem(config.storageKey);
  }
} catch (error) { localError = error.name; }
try {
  const request = indexedDB.open('environment-fixture', 1);
  request.onupgradeneeded = () => request.result.createObjectStore('markers');
  request.onerror = () => { idbError = request.error?.name || 'open-failed'; queue(); };
  request.onsuccess = () => {
    const database = request.result;
    const tx = database.transaction('markers', 'readwrite');
    const store = tx.objectStore('markers');
    const get = store.get(config.storageKey);
    get.onsuccess = () => {
      if (get.result === undefined) {
        store.put(config.marker, config.storageKey);
      }
    };
    tx.oncomplete = () => { storageDatabase=database;observeStorage(); };
    tx.onerror = tx.onabort = () => { idbError = tx.error?.name || 'transaction-failed'; database.close(); queue(); };
  };
} catch (error) { idbError = error.name; }
try {
  const worker = new Worker('/worker.js');
  worker.onmessage = event => { workerState = event.data; queue(); };
  worker.onerror = () => { workerError = 'worker-failed'; queue(); };
  worker.postMessage('sample');
} catch (error) { workerError = error.name; }
function observeStorage() {
  try {localValue=localStorage.getItem(config.storageKey);}catch(error){localError=error.name;}
  if(!storageDatabase||idbBusy)return;
  idbBusy=true;
  try {
    const transaction=storageDatabase.transaction('markers','readonly');
    const read=transaction.objectStore('markers').get(config.storageKey);
    read.onsuccess=()=>{idbValue=read.result===undefined?null:read.result;};
    transaction.oncomplete=()=>{idbBusy=false;queue();};
    transaction.onabort=transaction.onerror=()=>{idbBusy=false;idbError=transaction.error?.name||'read-failed';queue();};
  }catch(error){idbBusy=false;idbError=error.name;}
}
function sample() {
  const rect = editor.getBoundingClientRect();
  return {path:config.path,account:config.account,document:doc,url:location.href,
    language:navigator.language,languages:Array.from(navigator.languages),
    timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone,
    offsetMinutes:new Date(config.fixedDate).getTimezoneOffset(),fixedDate:config.fixedDate,
    worker:workerState,workerError,
    draft:readDraft(),modelDraft,scroll:reading.scrollTop,
    scrollHeight:reading.scrollHeight,clientHeight:reading.clientHeight,
    windowScroll:window.scrollY,devicePixelRatio:window.devicePixelRatio,viewportWidth:window.innerWidth,viewportHeight:window.innerHeight,
    inputRect:{x:rect.x,y:rect.y,left:rect.left,top:rect.top,right:rect.right,bottom:rect.bottom,width:rect.width,height:rect.height},
    localStorage:localValue,indexedDB:idbValue,localError,idbError,
    // HttpOnly login bytes are observed independently by the server, never by page script.
    visibleCookies:document.cookie,inputCount,trustedInputCount,sendClicks,
    focused:document.activeElement===editor,visibility:document.visibilityState,
    eventSequence:++eventSequence,sentEvents,capturedAt:Date.now()};
}
async function report() {
  if (pending) { dirty = true; return; }
  if (!dirty) return;
  dirty = false;pending = true;
  try {
    const result = await fetch('/event', {method:'POST',credentials:'same-origin',
      headers:{'Content-Type':'application/json'},body:JSON.stringify(sample()),cache:'no-store'});
    if (!result.ok) dirty = true;
    else sentEvents += 1;
  } catch (_) { dirty = true; }
  finally {pending=false;if(dirty)queue();}
}
function queue() {
  dirty=true;if(debounce!==null)clearTimeout(debounce);
  debounce=setTimeout(()=>{debounce=null;report();},150);
}
// Observation only: this never changes an editor, selection, storage marker or scroll.
setInterval(()=>{observeStorage();dirty=true;report();},1000);
document.addEventListener('visibilitychange',queue);
document.getElementById('send').addEventListener('click', async () => {
  sendClicks += 1;
  try { await fetch('/send',{method:'POST',credentials:'same-origin',
    headers:{'Content-Type':'application/json'},
    body:JSON.stringify({path:config.path,document:doc,draft:readDraft(),modelDraft})}); }
  finally {queue();}
});
queue();
"""


def page_html(path: str, initial: str) -> bytes:
    account = PAGE_ACCOUNTS[path]
    config = dict(path=path, account=account, marker=MARKERS[account], initialDraft=initial,
                  storageKey=STORE_KEY, fixedDate=FIXED_DATE)
    script = PAGE_SCRIPT.replace("__CONFIG__", json.dumps(config, ensure_ascii=False).replace("<", "\\u003c"))
    editor = ('<div id="prompt-textarea" role="textbox" contenteditable="true" aria-multiline="true" '
              'aria-label="合成编辑器"></div>') if path in CONTENTEDITABLE else (
              '<textarea id="prompt-textarea" aria-label="合成编辑器" rows="3"></textarea>')
    messages = "\n".join(
        f'<article data-message-author-role="{("user" if index % 2 == 0 else "assistant")}" '
        f'data-message-id="synthetic-{index}" id="message-{index}">'
        f'<strong>{("合成用户" if index % 2 == 0 else "合成助手")} {index + 1}</strong>'
        f'<p>环境隔离验收文字 · {html.escape(account)} · {index + 1}。'
        '此内容不来自真实账号，不包含真实对话。</p></article>'
        for index in range(48)
    )
    body = f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>合成对话 {html.escape(path)}</title><style>
*{{box-sizing:border-box}} html,body{{margin:0;height:100%;font-family:system-ui,sans-serif;background:#fff;color:#202020}}
main{{height:100dvh;display:flex;flex-direction:column;min-height:0}}
header{{padding:12px 16px;border-bottom:1px solid #eee;font-size:14px;flex-shrink:0}}
#reading{{overflow-y:auto;overscroll-behavior:contain;flex:1;min-height:0;padding:0 18px}}
article{{min-height:112px;border-bottom:1px solid #eee;padding:20px 0;font-size:15px;line-height:1.6}}
#composer{{padding:10px 14px 14px;flex-shrink:0;border-top:1px solid #ddd;background:#fff}}
#prompt-textarea{{display:block;width:100%;min-height:78px;max-height:130px;overflow-y:auto;border:1px solid #bbb;border-radius:10px;padding:10px;font:16px/1.4 system-ui,sans-serif;white-space:pre-wrap;resize:none;background:#fff;color:#111}}
.actions{{display:flex;justify-content:space-between;align-items:center;padding-top:8px;gap:12px}}
a,button{{font-size:14px}}button{{padding:10px 18px;border:0;border-radius:8px;background:#202020;color:#fff}}
</style></head><body><main>
<header>合成环境 {html.escape(account)} · {html.escape(path)} · 无真实服务</header>
<div id="reading" aria-label="合成对话阅读区">{messages}</div>
<div id="composer">{editor}<div class="actions"><a id="download" aria-label="保存合成文件" href="/download?account={account}">下载合成文件</a>
<button id="send" type="button">发送合成消息</button></div></div>
</main><script>{script}</script></body></html>'''
    return body.encode("utf-8")


def handler_for(evidence: Evidence):
    class Handler(BaseHTTPRequestHandler):
        server_version = "EnvironmentFixture/1"
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt: str, *args) -> None:
            pass

        def host_allowed(self) -> bool:
            try:
                host = urlsplit("https://" + self.headers.get("Host", "")).hostname
                return host == HOST
            except ValueError:
                return False

        def send_bytes(self, code: int, body: bytes, mime: str = "application/json; charset=utf-8",
                       extra: dict[str, str] | None = None) -> None:
            self.send_response(code)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            for key, value in (extra or {}).items():
                self.send_header(key, value)
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                pass

        def json_response(self, code: int, value: dict) -> None:
            self.send_bytes(code, json.dumps(value, ensure_ascii=False).encode("utf-8"))

        def request_info(self, path: str) -> tuple[str, str]:
            language, cookie = self.headers.get("Accept-Language", ""), self.headers.get("Cookie", "")
            evidence.request(path, language, cookie)
            return language, cookie

        def do_GET(self) -> None:
            if not self.host_allowed():
                self.send_bytes(421, b'{"error":"fixture host required"}')
                return
            parsed = urlsplit(self.path)
            path = parsed.path
            language, cookie = self.request_info(path)
            if path == "/status":
                self.send_bytes(200, evidence.snapshot())
            elif path in PAGE_ACCOUNTS:
                with evidence.lock:
                    initial = evidence.initial[path]
                headers = {}
                if not env_cookie(cookie)[0]:
                    headers["Set-Cookie"] = "env_login=" + MARKERS[PAGE_ACCOUNTS[path]] + "; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400"
                self.send_bytes(200, page_html(path, initial), "text/html; charset=utf-8", headers)
            elif path == "/worker.js":
                self.send_bytes(200, WORKER_JS.encode("utf-8"), "application/javascript; charset=utf-8")
            elif path == "/download":
                account = parse_qs(parsed.query).get("account", [""])[0]
                if account not in MARKERS:
                    self.json_response(400, {"error": "unknown synthetic account"})
                    return
                with evidence.lock:
                    evidence.state["downloads"].append(dict(account=account, cookie=cookie,
                        envLogin=env_cookie(cookie)[1], acceptLanguage=language, bytes=len(FILE_BYTES),
                        sha256=hashlib.sha256(FILE_BYTES).hexdigest(), requestedAt=stamp()))
                    evidence.state["downloads"] = evidence.state["downloads"][-200:]
                    evidence.persist()
                self.send_bytes(200, FILE_BYTES, "application/octet-stream",
                                {"Content-Disposition": f'attachment; filename="environment-{account}.bin"'})
            else:
                self.json_response(404, {"error": "unknown fixture path"})

        def read_json(self) -> dict:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > 512 * 1024:
                raise ValueError("body size")
            value = json.loads(self.rfile.read(length))
            if not isinstance(value, dict):
                raise ValueError("object required")
            return value

        def do_POST(self) -> None:
            if not self.host_allowed():
                self.send_bytes(421, b'{"error":"fixture host required"}')
                return
            path = urlsplit(self.path).path
            language, cookie = self.request_info(path)
            if path == "/send":
                with evidence.lock:
                    evidence.state["sendCount"] += 1
                    evidence.persist()
            try:
                value = self.read_json()
                if path == "/configure":
                    target, initial = value.get("path"), value.get("initialDraft")
                    if target not in PAGE_ACCOUNTS or not isinstance(initial, str) or len(initial) > 100000:
                        raise ValueError("configuration")
                    with evidence.lock:
                        evidence.initial[target] = initial
                        evidence.state["configuredInitialDrafts"][target] = initial
                        evidence.persist()
                    self.json_response(200, {"ok": True, "appliesTo": "future documents only"})
                elif path == "/event":
                    target, document = value.get("path"), value.get("document")
                    if target not in PAGE_ACCOUNTS or not isinstance(document, str) or str(uuid.UUID(document)) != document:
                        raise ValueError("document identity")
                    if value.get("account") != PAGE_ACCOUNTS[target]:
                        raise ValueError("account identity")
                    for name in ("draft", "modelDraft"):
                        if not isinstance(value.get(name), str) or len(value[name]) > 100000:
                            raise ValueError("draft")
                    # Keep only the documented synthetic evidence fields, not arbitrary posted data.
                    allowed = ("path", "account", "document", "url", "language", "languages", "timeZone",
                        "offsetMinutes", "fixedDate", "worker", "workerError", "draft", "modelDraft",
                        "scroll", "scrollHeight", "clientHeight", "windowScroll", "devicePixelRatio",
                        "inputRect", "localStorage", "indexedDB", "localError", "idbError", "visibleCookies",
                        "inputCount", "trustedInputCount", "sendClicks", "focused", "visibility",
                        "eventSequence", "sentEvents", "capturedAt")
                    observation = {key: value[key] for key in allowed if key in value}
                    observation.update(receivedAt=stamp(), acceptLanguage=language, cookie=cookie,
                                       envLogin=env_cookie(cookie)[1])
                    with evidence.lock:
                        documents = evidence.state["pages"][target]
                        previous = documents.get(document)
                        if previous and value.get("eventSequence", 0) <= previous.get("eventSequence", 0):
                            self.json_response(409, {"error": "stale observation"})
                            return
                        observation["eventCount"] = (previous or {}).get("eventCount", 0) + 1
                        documents[document] = observation
                        evidence.state["eventCount"] += 1
                        evidence.persist()
                    self.json_response(200, {"ok": True})
                elif path == "/send":
                    with evidence.lock:
                        evidence.state["sends"].append({key: value.get(key) for key in ("path", "document", "draft", "modelDraft")})
                        evidence.state["sends"] = evidence.state["sends"][-20:]
                        evidence.persist()
                    self.json_response(200, {"ok": True})
                else:
                    self.json_response(404, {"error": "unknown fixture path"})
            except (ValueError, TypeError, KeyError, UnicodeError):
                with evidence.lock:
                    evidence.state["rejectedEventCount"] += 1
                    evidence.persist()
                self.json_response(400, {"error": "invalid synthetic payload"})

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cert", required=True, type=Path)
    parser.add_argument("--key", required=True, type=Path)
    parser.add_argument("--port", type=int, default=18480)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    evidence = Evidence(args.report)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), handler_for(evidence))
    server.daemon_threads = True
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    with evidence.lock:
        evidence.state["port"] = server.server_port
        evidence.persist()
    print(f"Synthetic environment fixture listening on {HOST}:{server.server_port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        with evidence.lock:
            evidence.state["stoppedAt"] = stamp()
            evidence.persist()


if __name__ == "__main__":
    main()
