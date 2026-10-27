#!/usr/bin/env python3
"""Verify saved page recovery through a real force-stop on a fresh CI emulator."""
from pathlib import Path
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "com.forcemind.chatgpt.profiles.debug"
ACTIVITY = PACKAGE + "/com.forcemind.chatgpt.profiles.MainActivity"
CONFIG = "process-restart-fixture.json"
OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, data=None, check=True, timeout=20):
    return subprocess.run(["adb", *args], input=data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout, check=check)

def private_read(name, required=True):
    result = adb("exec-out", "run-as", PACKAGE, "cat", "files/" + name, check=False)
    if result.returncode and required:
        raise RuntimeError("Fixture private file unavailable: " + name)
    return result.stdout if result.returncode == 0 else None

def private_write(name, value):
    command = "run-as " + shlex.quote(PACKAGE) + " sh -c " + shlex.quote("cat > files/" + name)
    adb("shell", command, data=json.dumps(value, ensure_ascii=False).encode())

def snapshot_ready():
    try:
        raw = private_read("process-snapshot-ready.json", required=False)
        value = json.loads(raw) if raw else None
        return value if value and value.get("saved") and value.get("image") else None
    except (ValueError, TypeError):
        return None

def process():
    result = adb("shell", "pidof", PACKAGE, check=False)
    return result.stdout.decode().strip()

def wait_until(condition, seconds=60):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = condition()
        if result:
            return result
        time.sleep(0.5)
    raise RuntimeError("Process recovery fixture timed out")

def tree():
    try:
        adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/profiles-restart.xml", timeout=15)
        return ET.fromstring(adb("exec-out", "cat", "/sdcard/profiles-restart.xml").stdout)
    except (subprocess.SubprocessError, ET.ParseError):
        return None

def visible_page(phase, marker):
    root = tree()
    if root is None:
        return None
    labels = " ".join(n.get("text", "") + " " + n.get("content-desc", "") for n in root.iter("node"))
    if "验证阶段：" + phase not in labels or marker not in labels or "阅读位置：1400" not in labels:
        return None
    if phase == "verify" and ("无预置草稿" not in labels or "发送次数：0" not in labels):
        return None
    return root

def visible_snapshot(marker):
    root = tree()
    if root is None:
        return None
    labels = " ".join(n.get("text", "") + " " + n.get("content-desc", "") for n in root.iter("node"))
    return root if "上次保存的对话" in labels and marker in labels and "重新连接" in labels else None

def tap(label):
    root = tree()
    if root is None:
        return False
    for node in root.iter("node"):
        if node.get("text") != label:
            continue
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if bounds:
            x1,y1,x2,y2 = map(int, bounds.groups())
            if x2>x1 and y2>y1:
                adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                return True
    return False

def capture(name, root):
    (OUT / (name + ".xml")).write_bytes(ET.tostring(root, encoding="utf-8"))
    (OUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p").stdout)

devices = adb("devices").stdout.decode().splitlines()[1:]
connected = [line.split()[0] for line in devices if line.strip().endswith("\tdevice")]
if os.environ.get("GITHUB_ACTIONS") != "true" or len(connected) != 1 or not connected[0].startswith("emulator-"):
    raise SystemExit("Requires one fresh, isolated GitHub Actions emulator; physical devices are refused.")
if adb("shell", "getprop", "ro.kernel.qemu").stdout.decode().strip() != "1":
    raise SystemExit("Emulator property not confirmed")
adb("shell", "run-as", PACKAGE, "id")
existing = adb("shell", "run-as " + PACKAGE + " sh -c 'test -e files/workspace.json'", check=False)
if existing.returncode == 0 or process():
    raise SystemExit("Existing workspace/process found; refusing to overwrite debug data")

account, storage, tab, token = [str(uuid.uuid4()) for _ in range(4)]
marker = "process-restore-" + str(uuid.uuid4())
snapshot_marker = "saved-conversation-" + str(uuid.uuid4())
address = "https://chatgpt.com/c/process-restart-fixture"
workspace = {"schema": 1, "activeAccount": account, "pendingDeletes": [], "accounts": [{
    "id": account, "storageId": storage, "name": "进程重启验收", "email": "restart@example.test", "color": "#797979",
    "activeTab": tab, "tabs": [{"id": tab, "url": address, "title": "离线进程重启验收", "pinned": False}], "closedTabs": []}]}
adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
private_write("workspace.json", workspace)
private_write(CONFIG, {"token": token, "accountId": account, "storageId": storage, "tabId": tab, "phase": "seed", "draft": marker, "snapshotText": snapshot_marker})
started = False
try:
    adb("shell", "am", "start", "-W", "-n", ACTIVITY); started = True
    before_pid = wait_until(process, 20)
    seed = wait_until(lambda: visible_page("seed", marker))
    capture("process-restart-seed", seed)
    signal = wait_until(snapshot_ready)
    assert signal.get("saved") and signal.get("image")
    relative = "page-recovery/" + account + "/" + storage + "/" + tab + ".bin"
    encrypted = wait_until(lambda: private_read(relative, required=False))
    assert encrypted.startswith(b"HP01") and marker.encode() not in encrypted and snapshot_marker.encode() not in encrypted
    cipher_sha = hashlib.sha256(encrypted).hexdigest()
    # The next Application has no copy of the original text in its fixture config.
    private_write(CONFIG, {"token": token, "accountId": account, "phase": "offline"})
    assert marker.encode() not in private_read(CONFIG) and snapshot_marker.encode() not in private_read(CONFIG)
    adb("shell", "am", "force-stop", PACKAGE)
    wait_until(lambda: not process(), 20)
    assert hashlib.sha256(private_read(relative)).hexdigest() == cipher_sha
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)
    after_pid = wait_until(process, 20)
    assert before_pid != after_pid, "Expected a newly created application process"
    snapshot = wait_until(lambda: visible_snapshot(snapshot_marker))
    capture("process-restart-offline-text", snapshot)
    wait_until(lambda: tap("查看画面"))
    image = wait_until(lambda: (lambda r: r if r is not None and any(n.get("content-desc")=="上次保存的页面画面，只读" for n in r.iter("node")) else None)(tree()))
    capture("process-restart-offline-picture", image)
    wait_until(lambda: tap("查看文字"))
    assert hashlib.sha256(private_read(relative)).hexdigest() == cipher_sha
    private_write("process-restart-online.json", {"enabled": True})
    wait_until(lambda: tap("重新连接"))
    restored = wait_until(lambda: visible_page("verify", marker))
    capture("process-restart-restored", restored)
    saved = json.loads(private_read("workspace.json"))
    owner = next(a for a in saved["accounts"] if a["id"] == account)
    assert saved["activeAccount"] == account and owner["storageId"] == storage and owner["activeTab"] == tab
    report = {"passed": True, "scope": "Android CI emulator; actual force-stop and new process; offline fixture, not real ChatGPT",
              "source": os.environ.get("GITHUB_SHA"), "pidBefore": before_pid, "pidAfter": after_pid,
              "checks": ["saved encrypted checkpoint", "checkpoint contains no plaintext marker", "marker removed from next-launch fixture config",
                         "old process absent", "new application PID", "offline native transcript from encrypted checkpoint", "bounded saved picture available offline", "offline viewing leaves checkpoint unchanged", "unchanged account/storage/tab identity", "draft restored into initially empty page", "reading position 1400 restored", "no automatic send"],
              "checkpointSha256BeforeStop": cipher_sha, "limitations": ["not real ChatGPT", "not physical device", "not personal data upgrade", "does not cover unsaved edits after the checkpoint"]}
    (OUT / "process-restart-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("Actual force-stop/offline reopen/reconnect passed; encrypted text and picture, same account/tab, restored draft and reading position, no send.")
finally:
    if started:
        adb("shell", "am", "force-stop", PACKAGE, check=False)
    adb("shell", "run-as", PACKAGE, "rm", "-f", "files/" + CONFIG, "files/process-restart-online.json", "files/process-snapshot-ready.json", check=False)
