#!/usr/bin/env python3
"""CI emulator-only synthetic app + attributable WebView resource measurement.

No real ChatGPT, external nodes, local device runs, or performance pass thresholds.
Every incomplete attribution/counter/window fails visibly; raw evidence is retained.
"""
import argparse
import json
import math
import os
from pathlib import Path
import re
import shlex
import statistics
import subprocess
import time

PACKAGE = "com.forcemind.chatgpt.networkpreview.ci"
TEST = "com.forcemind.chatgpt.networkpreview.NetworkResourceInstrumentedTest#syntheticResourceProfiles"
PHASES = ["direct_idle_foreground", "proxy_idle_foreground", "proxy_fixed_workload",
          "proxy_idle_background", "recovery_direct"]


def parse_proc_stat(raw):
    """comm may contain spaces/parentheses; identity includes kernel start time."""
    close = raw.rfind(")")
    if close < 0 or not re.match(r"^\d+ \(", raw):
        raise ValueError("Malformed /proc/PID/stat")
    fields = raw[close + 1:].split()
    if len(fields) < 20:
        raise ValueError("Truncated /proc/PID/stat")
    ticks, started = int(fields[11]) + int(fields[12]), int(fields[19])
    if ticks < 0 or started <= 0:
        raise ValueError("Invalid process counter")
    return {"cpuTicks": ticks, "startTicks": started}


def parse_rss(raw):
    match = re.search(r"^VmRSS:\s+(\d+)\s+kB\s*$", raw, re.M)
    if not match:
        raise ValueError("VmRSS unavailable")
    return int(match.group(1))


def parse_pss(raw):
    match = re.search(r"TOTAL PSS:\s*([\d,]+)", raw)
    if not match:
        match = re.search(r"^\s*TOTAL:\s*(\d+)", raw, re.M)
    if not match:
        match = re.search(r"^\s*TOTAL\s+(\d+)\s", raw, re.M)
    if not match or re.search(r"No process found|Permission Denial", raw, re.I):
        raise ValueError("PSS unavailable from dumpsys meminfo")
    return int(match.group(1).replace(",", ""))


def parse_system_stat(raw):
    match = re.search(r"^cpu\s+(.+)$", raw, re.M)
    if not match:
        raise ValueError("Aggregate CPU counters unavailable")
    values = [int(value) for value in match.group(1).split()]
    if len(values) < 8 or min(values) < 0:
        raise ValueError("Invalid aggregate CPU counters")
    cores = len(re.findall(r"^cpu\d+\s", raw, re.M))
    if cores < 1:
        raise ValueError("Logical CPU count unavailable")
    # guest/guest_nice are already included in user/nice; don't count them twice.
    return {"totalTicks": sum(values[:8]), "idleTicks": values[3] + values[4], "logicalCores": cores}


def parse_mem_available(raw):
    match = re.search(r"^MemAvailable:\s+(\d+) kB$", raw, re.M)
    if not match:
        raise ValueError("MemAvailable unavailable")
    return int(match.group(1))


def parse_webview_identity(raw):
    match = re.search(r"Current WebView package\s*\(name, version\):\s*\(([^,]+),\s*([^)]+)\)", raw)
    if not match:
        raise ValueError("Current WebView package/version unavailable")
    return {"package": match.group(1).strip(), "version": match.group(2).strip()}


def attributable_processes(raw, package=PACKAGE):
    """Use ActivityManager service/component ownership, never renderer name alone.

    Android external isolated services have a client-package component such as
    com.example.app/org.chromium.content.app.SandboxedProcessService0:0.
    Preserve the complete activity dump so attribution can be audited.
    """
    starts = list(re.finditer(r"^\s*\*(?:APP|PERS|ISOLATED)\*[^\n]*ProcessRecord\{[^\n]*", raw, re.M))
    if not starts:
        raise ValueError("ActivityManager process records unavailable")
    processes, renderers, excluded = {}, [], []
    for index, start in enumerate(starts):
        block = raw[start.start():starts[index + 1].start() if index + 1 < len(starts) else len(raw)]
        identity = re.search(r"ProcessRecord\{\S+\s+(\d+):([^/\s}]+)", start.group())
        if not identity:
            raise ValueError("Unrecognized ProcessRecord identity")
        pid, name = int(identity.group(1)), identity.group(2)
        if name == package or name.startswith(package + ":"):
            processes[pid] = {"pid": pid, "name": name, "kind": "app_and_jni_core"}
        elif "sandboxed_process" in name:
            owners = set(re.findall(r"([A-Za-z][\w]*(?:\.[\w]+)+)/[^\s}\]]*SandboxedProcessService[^\s}\]]*", block))
            # A known renderer with no owner is ambiguous: an app-only/partial sum is not acceptable.
            if not owners:
                raise ValueError("Cannot attribute renderer PID %s (%s) to a client service" % (pid, name))
            if package in owners:
                if owners != {package}:
                    raise ValueError("Ambiguous renderer client ownership: %s" % sorted(owners))
                item = {"pid": pid, "name": name, "kind": "webview_renderer", "clientPackage": package}
                processes[pid] = item
                renderers.append(pid)
            else:
                excluded.append({"pid": pid, "name": name, "clientPackages": sorted(owners)})
    return sorted(processes.values(), key=lambda item: item["pid"]), renderers, excluded


def cpu_interval(before, after, hz):
    elapsed = after["counterTimeMonotonic"] - before["counterTimeMonotonic"]
    if elapsed <= 0 or hz <= 0 or before["startTicks"] != after["startTicks"]:
        raise ValueError("Process restarted or CPU interval invalid")
    ticks = after["cpuTicks"] - before["cpuTicks"]
    if ticks < 0:
        raise ValueError("CPU counter regressed")
    seconds = ticks / hz
    return {"elapsedSeconds": elapsed, "cpuCoreSeconds": seconds, "cpuPercentOneCore": seconds / elapsed * 100}


class Sampler:
    def __init__(self, output, window, warmup, interval):
        self.output, self.window, self.warmup, self.interval = output, window, warmup, interval
        self.instrumentation = None
        self.log = None
        self.hz = None

    def adb(self, *args, timeout=25):
        result = subprocess.run(["adb", *args], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode:
            raise RuntimeError("adb %s failed (%s): %s" % (" ".join(args), result.returncode, result.stderr[-1200:]))
        return result.stdout

    def shell(self, *args, timeout=25):
        # adb shell concatenates its arguments: quote each token for the remote shell.
        return self.adb("shell", " ".join(shlex.quote(str(arg)) for arg in args), timeout=timeout)

    def raw(self, folder, name, data):
        (folder / name).write_text(data)
        return data

    def preflight(self):
        if os.environ.get("GITHUB_ACTIONS") != "true":
            raise RuntimeError("Resource sampler runs only in GitHub Actions")
        if self.shell("getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError("Resource sampler requires an isolated CI emulator")
        self.adb("root")
        self.adb("wait-for-device", timeout=60)
        if self.shell("id", "-u").strip() != "0":
            raise RuntimeError("CI emulator root required to include isolated WebView /proc counters")
        if self.shell("getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError("Emulator identity changed after adb root")
        self.hz = int(self.shell("getconf", "CLK_TCK").strip())
        if self.hz <= 0:
            raise ValueError("Invalid kernel CLK_TCK")
        provider_dump = self.shell("dumpsys", "webviewupdate")
        metadata = {"package": PACKAGE, "test": TEST, "clockTicksPerSecond": self.hz,
                    "api": self.shell("getprop", "ro.build.version.sdk").strip(),
                    "fingerprint": self.shell("getprop", "ro.build.fingerprint").strip(),
                    "webViewProvider": provider_dump, "webViewIdentity": parse_webview_identity(provider_dump),
                    "windowSeconds": self.window, "warmupSeconds": self.warmup,
                    "sampleIntervalSeconds": self.interval}
        (self.output / "environment.json").write_text(json.dumps(metadata, indent=2))
        return metadata

    def phase_file(self, name):
        return self.shell("run-as", PACKAGE, "cat", "files/" + name)

    def ack(self, token):
        command = "printf %s " + shlex.quote(token) + " > files/resource-ack.tmp && mv files/resource-ack.tmp files/resource-ack"
        self.shell("run-as", PACKAGE, "sh", "-c", command)

    def wait_phase(self, name):
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            if self.instrumentation.poll() is not None:
                raise RuntimeError("Resource instrumentation exited before phase " + name)
            try:
                phase = json.loads(self.phase_file("resource-phase.json"))
            except (RuntimeError, json.JSONDecodeError):
                phase = {}
            if phase.get("phase") == name and phase.get("state") == "ready":
                if phase.get("windowSeconds") != self.window:
                    raise ValueError("Host and device measurement windows differ")
                return phase
            time.sleep(1)
        raise TimeoutError("Timed out waiting for resource phase " + name)

    def snapshot(self, folder, index, baseline=False):
        label = "%02d" % index
        activity = self.raw(folder, label + "-activity-processes.txt", self.shell("dumpsys", "activity", "processes"))
        processes, renderers, excluded = attributable_processes(activity)
        if baseline and processes:
            raise ValueError("Baseline contains app or attributable WebView processes")
        if not baseline and (not any(p["name"] == PACKAGE for p in processes) or not renderers):
            raise ValueError("Complete app + attributable WebView renderer process set required")
        system_raw = self.raw(folder, label + "-proc-stat.txt", self.shell("cat", "/proc/stat"))
        system = parse_system_stat(system_raw)
        system["counterTimeMonotonic"] = time.monotonic()
        available = parse_mem_available(self.raw(folder, label + "-meminfo.txt", self.shell("cat", "/proc/meminfo")))
        for process in processes:
            prefix = label + "-pid-" + str(process["pid"])
            process.update(parse_proc_stat(self.raw(folder, prefix + "-stat.txt", self.shell("cat", "/proc/%s/stat" % process["pid"]))))
            process["counterTimeMonotonic"] = time.monotonic()
            process["rssKiB"] = parse_rss(self.raw(folder, prefix + "-status.txt", self.shell("cat", "/proc/%s/status" % process["pid"])))
            process["pssKiB"] = parse_pss(self.raw(folder, prefix + "-meminfo.txt", self.shell("dumpsys", "meminfo", "-s", str(process["pid"]))))
        return {"timeMonotonic": time.monotonic(), "processes": processes, "excludedUnrelatedRenderers": excluded,
                "system": system, "systemMemAvailableKiB": available,
                "appAndRendererPssKiB": sum(p["pssKiB"] for p in processes),
                "appAndRendererRssKiB": sum(p["rssKiB"] for p in processes)}

    def measure(self, name, baseline=False):
        folder = self.output / name
        folder.mkdir()
        phase = {"phase": name, "state": "baseline"} if baseline else self.wait_phase(name)
        if baseline:
            time.sleep(self.warmup)
        samples = [self.snapshot(folder, 0, baseline)]
        if not baseline:
            if phase["pid"] not in [p["pid"] for p in samples[0]["processes"]]:
                raise ValueError("Instrumentation PID differs from measured app PID")
            self.ack(name + ":start")
        started = time.monotonic()
        # Fixed schedule including a final sample at/after the complete window.
        count = math.ceil(self.window / self.interval)
        for index in range(1, count + 1):
            due = started + min(index * self.interval, self.window)
            time.sleep(max(0, due - time.monotonic()))
            samples.append(self.snapshot(folder, index, baseline))
            (folder / "samples.json").write_text(json.dumps(samples, indent=2))
        if not baseline:
            self.ack(name + ":done")
        intervals = []
        for previous, current in zip(samples, samples[1:]):
            old = {p["pid"]: p for p in previous["processes"]}
            new = {p["pid"]: p for p in current["processes"]}
            if old.keys() != new.keys():
                raise ValueError("Process set changed during %s; retained raw snapshots, no complete CPU sum" % name)
            per_process = [{"pid": pid, "kind": new[pid]["kind"], **cpu_interval(old[pid], new[pid], self.hz)} for pid in new]
            system_before, system_after = previous["system"], current["system"]
            elapsed = system_after["counterTimeMonotonic"] - system_before["counterTimeMonotonic"]
            total = system_after["totalTicks"] - system_before["totalTicks"]
            idle = system_after["idleTicks"] - system_before["idleTicks"]
            if elapsed <= 0 or total <= 0 or idle < 0 or idle > total:
                raise ValueError("Invalid system CPU interval")
            busy_seconds = (total - idle) / self.hz
            intervals.append({"processes": per_process, "appAndRendererCpuCoreSeconds": sum(p["cpuCoreSeconds"] for p in per_process),
                              "appAndRendererCpuPercentOneCore": sum(p["cpuPercentOneCore"] for p in per_process),
                              "systemCpuCoreSeconds": busy_seconds, "systemCpuPercentOneCore": busy_seconds / elapsed * 100,
                              "systemCpuUtilizationPercent": (total - idle) / total * 100,
                              "systemElapsedSeconds": elapsed})
        duration = samples[-1]["system"]["counterTimeMonotonic"] - samples[0]["system"]["counterTimeMonotonic"]
        if len(samples) < 3 or duration < self.window:
            raise ValueError("At least three samples and full measured window required")
        result = {"phase": name, "devicePhase": phase, "complete": True, "durationSeconds": duration,
                  "samples": samples, "intervals": intervals,
                  "summary": {"pssMiBMean": statistics.mean(s["appAndRendererPssKiB"] for s in samples) / 1024,
                              "pssMiBPeak": max(s["appAndRendererPssKiB"] for s in samples) / 1024,
                              "rssMiBMean": statistics.mean(s["appAndRendererRssKiB"] for s in samples) / 1024,
                              "cpuPercentOneCoreMean": sum(
                                  sum(p["cpuCoreSeconds"] for i in intervals for p in i["processes"] if p["pid"] == process["pid"])
                                  / sum(p["elapsedSeconds"] for i in intervals for p in i["processes"] if p["pid"] == process["pid"]) * 100
                                  for process in samples[0]["processes"]),
                              "cpuCoreSeconds": sum(i["appAndRendererCpuCoreSeconds"] for i in intervals),
                              "systemCpuPercentOneCoreMean": statistics.mean(i["systemCpuPercentOneCore"] for i in intervals),
                              "systemMemAvailableMiBMean": statistics.mean(s["systemMemAvailableKiB"] for s in samples) / 1024}}
        (folder / "phase-report.json").write_text(json.dumps(result, indent=2))
        print("Resource phase complete: %s (%d samples, %.1fs)" % (name, len(samples), duration), flush=True)
        return result

    def launch(self):
        self.shell("run-as", PACKAGE, "rm", "-f", "files/resource-phase.json", "files/resource-result.json", "files/resource-ack")
        self.log = (self.output / "instrumentation.txt").open("w")
        arguments = ["am", "instrument", "-w", "-r", "-e", "class", TEST,
                     "-e", "resourceSampler", "1", "-e", "resourceWindowSeconds", str(self.window),
                     "-e", "resourceWarmupSeconds", str(self.warmup), PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
        self.instrumentation = subprocess.Popen(["adb", "shell", " ".join(shlex.quote(arg) for arg in arguments)],
                                                stdout=self.log, stderr=subprocess.STDOUT)

    def finish(self):
        code = self.instrumentation.wait(timeout=90)
        self.log.flush()
        output = (self.output / "instrumentation.txt").read_text()
        # `am instrument` may return exit 0 even when the JUnit test failed.
        if code or "OK (1 test)" not in output or "FAILURES!!!" in output or "INSTRUMENTATION_FAILED" in output:
            raise RuntimeError("Resource instrumentation failed; see instrumentation.txt")
        result = json.loads(self.phase_file("resource-result.json"))
        (self.output / "device-result.json").write_text(json.dumps(result, indent=2))
        if not result.get("complete") or [p["phase"] for p in result.get("phases", [])] != PHASES:
            raise ValueError("Device did not complete every resource profile")
        return result

    def cleanup(self):
        if self.instrumentation is not None and self.instrumentation.poll() is None:
            self.shell("am", "force-stop", PACKAGE)
            self.instrumentation.terminate()
            try:
                self.instrumentation.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.instrumentation.kill()
        if self.log:
            self.log.close()


def markdown_report(report):
    environment = report["environment"]
    provider = environment["webViewIdentity"]
    lines = ["# Synthetic Android network resource profiles", "",
             "CI emulator only; synthetic page/files and owned VLESS A. No real ChatGPT session or user node.", "",
             "Android API %s; WebView %s %s. Compare direct/proxy phases only within this emulator run." %
             (environment["api"], provider["package"], provider["version"]), "",
             "| Phase | Window s | Samples | PSS mean MiB | PSS peak MiB | RSS mean MiB | CPU % one core | CPU core-s |",
             "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for phase in report["phases"]:
        s = phase["summary"]
        lines.append("| %s | %.1f | %d | %.2f | %.2f | %.2f | %.2f | %.3f |" %
                     (phase["phase"], phase["durationSeconds"], len(phase["samples"]), s["pssMiBMean"], s["pssMiBPeak"],
                      s["rssMiBMean"], s["cpuPercentOneCoreMean"], s["cpuCoreSeconds"]))
    lines.extend(["", *["- " + note for note in report["measurementNotes"]], ""])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--window-seconds", type=int, default=60)
    parser.add_argument("--warmup-seconds", type=int, default=5)
    parser.add_argument("--sample-interval-seconds", type=int, default=10)
    args = parser.parse_args()
    if not 60 <= args.window_seconds <= 120 or not 3 <= args.warmup_seconds <= 30:
        parser.error("window must be 60..120s and warmup 3..30s")
    if not 5 <= args.sample_interval_seconds <= args.window_seconds / 2:
        parser.error("sampling interval must be 5s..half window (at least three samples)")
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("CI only; no local emulator/device execution")
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    sampler = Sampler(output, args.window_seconds, args.warmup_seconds, args.sample_interval_seconds)
    report = {"complete": False, "phases": [], "measurementNotes": [
        "CPU core-seconds = delta(utime + stime) / kernel CLK_TCK; CPU % one core = core-seconds / per-process elapsed seconds * 100. Multicore totals may exceed 100%.",
        "PSS sums the app and ActivityManager-client-attributed WebView renderer processes. JNI Go core runs in the app process and is included, not separately estimated. RSS sums may double-count shared pages.",
        "App-process AndroidJUnitRunner, phase synchronization, workload assertions, and sampler dumpsys overhead affect measurements. This is an emulator synthetic comparison, not a real-device or real-ChatGPT memory limit.",
        "Android API, emulator image and WebView provider/version are recorded per run. Do not infer proxy overhead by comparing different Android versions, images, or WebView versions; use direct/proxy phases within one run.",
        "No-app baseline has no instrumentation or app/renderer process; compare device CPU/MemAvailable in JSON. Do not interpret subtracting this baseline as app PSS.",
        "Proxy fixed workload: 30 evenly scheduled cycles of a 20-byte native disk download, 128 KiB upload + echo, and a 20-byte WebView fetch. HTTP/header/TCP/VLESS overhead is excluded from payload byte counts.",
        "Background profile is a stopped Activity (CREATED lifecycle), with proxy retained. Recovery restores direct route and the synthetic direct page before measurement.",
        "Warmups and route transitions are excluded. Seven default memory samples and six CPU intervals cover each >=60s window. Memory peak is sampled, not a continuous high-water mark.",
        "Unknown renderer ownership, unreadable counters, process restart/churn, missing samples, workload overrun, or failed instrumentation invalidates completion; raw evidence remains available."
    ]}
    try:
        report["environment"] = sampler.preflight()
        sampler.shell("am", "force-stop", PACKAGE)
        report["phases"].append(sampler.measure("baseline_no_app", baseline=True))
        sampler.launch()
        for phase in PHASES:
            report["phases"].append(sampler.measure(phase))
        report["deviceResult"] = sampler.finish()
        report["complete"] = True
        (output / "report.md").write_text(markdown_report(report))
    except Exception as error:
        report["error"] = "%s: %s" % (type(error).__name__, error)
        if sampler.instrumentation is not None:
            try:
                report["partialDeviceResult"] = json.loads(sampler.phase_file("resource-result.json"))
            except (RuntimeError, json.JSONDecodeError, subprocess.TimeoutExpired):
                pass
        raise
    finally:
        (output / "report.json").write_text(json.dumps(report, indent=2))
        sampler.cleanup()


if __name__ == "__main__":
    main()
