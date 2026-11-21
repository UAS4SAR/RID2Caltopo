#!/usr/bin/env python3
"""Read-only pre-check before installing a debug build over a running test device.

Never kills, force-stops, clears or uninstalls anything. Exit 0 = safe to install,
1 = not safe (an active session), 2 = the check could not be done reliably (do not install).

  install_precheck.py android <serial>   A5/S11U: no ESTABLISHED connection on the relay ports
  install_precheck.py ipad <udid>        iPad: RID2CaltopoApple absent, or present but idle
  install_precheck.py --self-test

iPad rule (Nov 18 2026): iOS keeps a quit app's process suspended, and prewarms apps that
were never opened, so a listed RID2CaltopoApple process is not by itself a running session.
It is safe to install over when either
  * no app log belongs to the current launch (the newest session log was written by another
    pid, so the process never reached the app's launch code: a prewarmed process), or
  * the current launch's log ends with "Primary application session cleanup completed"
    (Quit, window swipe or idle timeout finished) with no new session started after it.
Otherwise the app may be monitoring; do not install.

An App Store or TestFlight install (devicectl: builtByDeveloper false) has a container devicectl
cannot read, so its logs cannot be checked: the check says so plainly and exits 2 (Nov 20 2026).
Only Ken's confirmation that the app is not in use can override that.
"""
import json
import os
import re
import subprocess
import sys
import tempfile

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
RELAY_PORTS = (1935, 8554, 8888, 8889, 8189)
BUNDLE_ID = "org.ncssar.RID2CaltopoApple"
EXECUTABLE = "RID2CaltopoApple.app/RID2CaltopoApple"
LOG_ROOT = "Documents/RID2Caltopo/FlightStorage"
LOG_NAME = re.compile(r"Log_(\d{2})([A-Z][a-z]{2})(\d{4})-(\d{6})-[A-Z]+-\d{4}\.txt")
MONTHS = {m: i for i, m in enumerate(
    "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split(), start=1)}
CLEANUP_DONE = "Primary application session cleanup completed"
SESSION_START = "Application UI started"
LAUNCH_PID = re.compile(r"\[App\] Process launched pid=(\d+)")


def run(cmd):
    """Run without hiding errors; returns (rc, stdout, stderr)."""
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return proc.returncode, proc.stdout, proc.stderr


# ---------------------------------------------------------------- Android
def established_on_relay_ports(netstat_text):
    hits = []
    for line in netstat_text.splitlines():
        parts = line.split()
        if len(parts) < 6 or parts[-1] != "ESTABLISHED":
            continue
        local = parts[3]
        port = local.rsplit(":", 1)[-1]
        if port.isdigit() and int(port) in RELAY_PORTS:
            hits.append(line.strip())
    return hits


def android(serial):
    rc, out, err = run([ADB, "-s", serial, "shell", "netstat", "-tn"])
    print(f"netstat -tn rc={rc}")
    if err.strip():
        print(err.strip())
    if rc != 0 or "Active Internet connections" not in out:
        print("UNRELIABLE: netstat did not run; do not install.")
        return 2
    rc2, out2, err2 = run([ADB, "-s", serial, "shell", "netstat", "-tln"])
    print(f"netstat -tln rc={rc2}")
    if err2.strip():
        print(err2.strip())
    listeners = [l.strip() for l in out2.splitlines() if "LISTEN" in l and any(
        l.split()[3].endswith(f":{p}") for p in RELAY_PORTS if len(l.split()) > 3)]
    for l in listeners:
        print("listener:", l)
    hits = established_on_relay_ports(out)
    for h in hits:
        print("ESTABLISHED:", h)
    if hits:
        print("NOT SAFE: video relay connections are established.")
        return 1
    print("SAFE: nothing ESTABLISHED on relay ports " + ",".join(map(str, RELAY_PORTS)))
    return 0


# ---------------------------------------------------------------- iPad
def app_pids(processes_json):
    procs = processes_json.get("result", {}).get("runningProcesses", [])
    return [p["processIdentifier"] for p in procs
            if str(p.get("executable", "")).endswith(EXECUTABLE)]


def log_paths(files_json):
    """Newest-first relative paths (day/Log_….txt) of session logs in any devicectl JSON."""
    found = set()

    def walk(node):
        if isinstance(node, dict):
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)
        elif isinstance(node, str):
            m = LOG_NAME.search(node)
            if m:
                found.add(m.group(0))
    walk(files_json)

    def key(name):
        d, mon, y, hms = LOG_NAME.match(name).groups()
        return (int(y), MONTHS.get(mon, 0), int(d), hms)

    out = []
    for name in sorted(found, key=key, reverse=True):
        d, mon, y, _ = LOG_NAME.match(name).groups()
        out.append(f"{y}-{MONTHS.get(mon, 0):02d}-{d}/{name}")
    return out


def ipad_decision(pid, log_text):
    """Returns (safe, reason) for one running RID2CaltopoApple pid and the newest session log."""
    if log_text is None:
        return True, f"pid {pid}: no session log at all; the app's launch code never ran (prewarm)"
    launch_pids = LAUNCH_PID.findall(log_text)
    if not launch_pids or int(launch_pids[-1]) != pid:
        writer = launch_pids[-1] if launch_pids else "unknown"
        return True, (f"pid {pid}: newest session log was written by pid {writer}; "
                      "no app log for the current launch (prewarmed, never opened)")
    lines = [l for l in log_text.splitlines() if l.strip()]
    last_cleanup = max((i for i, l in enumerate(lines) if CLEANUP_DONE in l), default=-1)
    if last_cleanup < 0:
        return False, f"pid {pid}: current launch's log has no completed cleanup; a session may be running"
    if any(SESSION_START in l for l in lines[last_cleanup + 1:]):
        return False, f"pid {pid}: a new session started after the last cleanup"
    return True, f"pid {pid}: current launch's log ends with cleanup completed (quit and suspended)"


def installed_app(apps_json):
    """The RID2CaltopoApple entry of `devicectl device info apps --include-all-apps`, or None."""
    for app in apps_json.get("result", {}).get("apps", []):
        if app.get("bundleIdentifier") == BUNDLE_ID:
            return app
    return None


def store_build_message(app, pids):
    """A plain message when the installed app's container cannot be read (App Store/TestFlight), else None."""
    if app is None or (app.get("builtByDeveloper") is not False and app.get("containerAccessible") is not False):
        return None
    return (f"UNRELIABLE: the installed RID2CaltopoApple {app.get('version', '?')} ({app.get('bundleVersion', '?')}) "
            "is an App Store/TestFlight build (builtByDeveloper=false), so devicectl cannot read its container "
            f"or logs. Running pid(s) {', '.join(map(str, pids))} may be an iOS prewarm. Installing a developer build "
            "over it needs Ken's confirmation that the app is not in use.")


def ipad(udid):
    with tempfile.TemporaryDirectory() as tmp:
        procs_path = os.path.join(tmp, "procs.json")
        rc, _, err = run(["xcrun", "devicectl", "device", "info", "processes",
                          "--device", udid, "--json-output", procs_path])
        if rc != 0 or not os.path.exists(procs_path):
            print(f"UNRELIABLE: devicectl processes rc={rc} {err.strip()}")
            return 2
        procs = json.load(open(procs_path))
        if not procs.get("result", {}).get("runningProcesses"):
            print("UNRELIABLE: the process list is empty; do not install.")
            return 2
        pids = app_pids(procs)
        if not pids:
            print("SAFE: RID2CaltopoApple is not running.")
            return 0
        apps_path = os.path.join(tmp, "apps.json")
        rc, _, err = run(["xcrun", "devicectl", "device", "info", "apps", "--device", udid,
                          "--include-all-apps", "--json-output", apps_path])
        if rc == 0 and os.path.exists(apps_path):
            app = installed_app(json.load(open(apps_path)))
            if app is not None:
                print(f"installed: {app.get('version', '?')} ({app.get('bundleVersion', '?')}) "
                      f"builtByDeveloper={app.get('builtByDeveloper')} containerAccessible={app.get('containerAccessible')}")
            message = store_build_message(app, pids)
            if message:
                print(message)
                return 2
        else:
            print(f"note: devicectl apps rc={rc} {err.strip()} (build type unknown; checking logs)")
        files_path = os.path.join(tmp, "files.json")
        rc, _, err = run(["xcrun", "devicectl", "device", "info", "files", "--device", udid,
                          "--domain-type", "appDataContainer", "--domain-identifier", BUNDLE_ID,
                          "--subdirectory", LOG_ROOT, "--json-output", files_path])
        if rc != 0 or not os.path.exists(files_path):
            print(f"UNRELIABLE: could not list app logs rc={rc} {err.strip()}")
            return 2
        logs = log_paths(json.load(open(files_path)))
        text = None
        if logs:
            local = os.path.join(tmp, "newest.txt")
            rc, _, err = run(["xcrun", "devicectl", "device", "copy", "from", "--device", udid,
                              "--domain-type", "appDataContainer", "--domain-identifier", BUNDLE_ID,
                              "--source", f"{LOG_ROOT}/{logs[0]}", "--destination", local])
            if rc != 0 or not os.path.exists(local):
                print(f"UNRELIABLE: could not read {logs[0]} rc={rc} {err.strip()}")
                return 2
            text = open(local, errors="replace").read()
            print(f"newest session log: {logs[0]}")
        verdict = 0
        for pid in pids:
            safe, reason = ipad_decision(pid, text)
            print(("SAFE: " if safe else "NOT SAFE: ") + reason)
            if not safe:
                verdict = 1
        return verdict


# ---------------------------------------------------------------- self-test
def self_test():
    netstat = """Active Internet connections (w/o servers)
Proto Recv-Q Send-Q Local Address           Foreign Address         State
tcp6       0      0 ::ffff:192.168.68:33580 ::ffff:142.250.141:5228 ESTABLISHED
tcp        0      0 192.168.68.50:1935      192.168.68.20:51000     ESTABLISHED
tcp        0      0 192.168.68.50:46894     172.217.113.4:443       CLOSE_WAIT"""
    assert established_on_relay_ports(netstat) == [netstat.splitlines()[3].strip()]
    assert established_on_relay_ports(netstat.replace(":1935 ", ":1936 ")) == []
    procs = {"result": {"runningProcesses": [
        {"executable": "file:///sbin/launchd", "processIdentifier": 1},
        {"executable": "file:///private/var/containers/Bundle/Application/X/RID2CaltopoApple.app/RID2CaltopoApple",
         "processIdentifier": 921}]}}
    assert app_pids(procs) == [921]
    files = {"result": {"files": [{"relativePath": "2026-11-12/Log_12Nov2026-203538-PST-0800.txt"},
                                  {"name": "Log_16Nov2026-095715-PST-0800.txt"},
                                  {"relativePath": "2026-11-16/r2c_reported.txt"}]}}
    assert log_paths(files) == ["2026-11-16/Log_16Nov2026-095715-PST-0800.txt",
                                "2026-11-12/Log_12Nov2026-203538-PST-0800.txt"]
    quit_log = "\n".join([
        "x [INFO][458-main] [App] Process launched pid=458 state=background options=[] log=L",
        "x [INFO][458-main] [App] Application UI started",
        "x [INFO][458-main] [Lifecycle] Closing primary application session reason=operator quit",
        "x [INFO][458-main] [Lifecycle] Primary application session cleanup completed",
        "x [INFO][458-main] [MediaMTX] Stopped",
        "x [INFO][458-main] [Lifecycle] Entered background"])
    assert ipad_decision(921, None)[0]
    assert ipad_decision(921, quit_log)[0]                      # another pid wrote the newest log
    assert ipad_decision(458, quit_log)[0]                      # quit, then suspended
    running = quit_log.split("\n")[:2]
    assert not ipad_decision(458, "\n".join(running))[0]        # session open
    reopened = quit_log + "\nx [INFO][458-main] [App] Application UI started"
    assert not ipad_decision(458, reopened)[0]                  # reopened after cleanup
    store = {"result": {"apps": [{"bundleIdentifier": "com.google.Gmail", "builtByDeveloper": False},
                                 {"bundleIdentifier": BUNDLE_ID, "version": "2.5.7", "bundleVersion": "436",
                                  "builtByDeveloper": False, "containerAccessible": False}]}}
    msg = store_build_message(installed_app(store), [453])
    assert msg and "App Store/TestFlight" in msg and "2.5.7 (436)" in msg and "453" in msg
    dev = {"result": {"apps": [{"bundleIdentifier": BUNDLE_ID, "version": "2.6.0", "bundleVersion": "446",
                                "builtByDeveloper": True, "containerAccessible": True}]}}
    assert store_build_message(installed_app(dev), [453]) is None
    assert store_build_message(installed_app({"result": {"apps": []}}), [453]) is None
    print("self-test passed")
    return 0


def main(argv):
    if argv[1:2] == ["--self-test"]:
        return self_test()
    if len(argv) != 3 or argv[1] not in ("android", "ipad"):
        print(__doc__)
        return 2
    return android(argv[2]) if argv[1] == "android" else ipad(argv[2])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
