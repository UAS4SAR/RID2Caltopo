#!/usr/bin/env python3
"""Compare BTRX STEER gyro yaw (firmware 0x010C, format 2) with DJI SEI true-north yaw.

Reads a session / BTRX log (or the machine-readable lines on their own), interpolates
the SEI yaw to each pod yaw sample time, unwraps both, subtracts the first offset, and
writes a CSV plus a PNG of (pod − SEI − offset) over time. The slope over still or
hover stretches is the drift in deg/min; a jump in the same direction on every full
turn is a gyro scale error.

Sample time: prefers sample_host_ms when present; otherwise arrival_ms + t_off_ms
(an approximation: radio latency is not removed). Rows with t_off_ms=-32768 are
kept in the CSV but left out of the slope and turn-jump figures.

Usage:
  tools/btrx_yaw_drift.py LOG [--out-csv PATH] [--out-png PATH]
  tools/btrx_yaw_drift.py --self-test

Nothing here is sent to CalTopo.
"""
from __future__ import annotations

import argparse
import math
import os
import re
import struct
import sys
import tempfile
import zlib

KV = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)=(-?\d+(?:\.\d+)?|0x[0-9A-Fa-f]+|-)")
# Session-log prefix used by convert_yawtest and current Android/iOS logs.
WALL = re.compile(r"(?:[A-Z][a-z]{2})(\d{2})(\d{2})(\d{2})\.(\d{3})\s")
GYRO = "Btrx gyro_yaw"
SEI = "Btrx sei_yaw"
STEER_YAW = re.compile(r"\bSTEER\b.*\byaw_x10=")
T_OFF_UNKNOWN = -32768
STILL_SEI_DPS = 2.0  # |dSEI/dt| below this counts as hover/still for the drift slope


def wall_ms_from_prefix(line: str) -> int | None:
    m = WALL.search(line)
    if not m:
        return None
    hh, mm, ss, ms = (int(x) for x in m.groups())
    return ((hh * 60 + mm) * 60 + ss) * 1000 + ms


def fields(line: str) -> dict:
    out = {}
    for k, v in KV.findall(line):
        if v == "-":
            out[k] = None
        elif v.startswith("0x") or v.startswith("0X"):
            out[k] = int(v, 16)
        elif "." in v:
            out[k] = float(v)
        else:
            out[k] = int(v)
    return out


def parse_log(text: str):
    """Return (gyro_rows, sei_rows). Times are host milliseconds."""
    gyro, sei = [], []
    for line in text.splitlines():
        f = fields(line)
        wall = wall_ms_from_prefix(line)
        if GYRO in line or (STEER_YAW.search(line) and "yaw_x10" in f):
            arrival = f.get("arrival_ms")
            if arrival is None and wall is not None:
                arrival = wall
            t_off = f.get("t_off_ms")
            sample = f.get("sample_host_ms")
            if sample is None and arrival is not None and t_off is not None and t_off != T_OFF_UNKNOWN:
                sample = arrival + t_off
            ready = t_off is None or t_off != T_OFF_UNKNOWN
            if sample is None:
                sample = f.get("sample_ms")  # may be pod clock; last resort
            if sample is None:
                sample = arrival
            yaw_x10 = f.get("yaw_x10")
            if yaw_x10 is None or sample is None:
                continue
            turns = f.get("turns") or 0
            gyro.append({
                "t_ms": int(sample),
                "arrival_ms": arrival,
                "window_end_ms": f.get("window_end_ms"),
                "sample_ms": f.get("sample_ms"),
                "yaw_x10": int(yaw_x10),
                "yaw_fixed_x10": int(f.get("yaw_fixed_x10") or yaw_x10),
                "turns": int(turns),
                "bias_z_mdps": f.get("bias_z_mdps"),
                "t_off_ms": t_off,
                "yflags": f.get("yflags") or 0,
                "ready": bool(ready),
                "unwrapped": int(turns) * 360.0 + int(yaw_x10) / 10.0,
                "unwrapped_fixed": None,  # filled after unwrap of fixed
            })
        if SEI in line and f.get("yaw_true_deg") is not None and (f.get("t_ms") is not None or wall is not None):
            sei.append({
                "t_ms": int(f.get("t_ms") if f.get("t_ms") is not None else wall),
                "yaw_true_deg": float(f["yaw_true_deg"]),
                "stream": None,
            })
            # stream is not numeric; pick it up separately
            m = re.search(r"\bstream=(\S+)", line) or re.search(r"\bsei_stream=(\S+)", line)
            if m:
                sei[-1]["stream"] = m.group(1)
        # SEI attached to a gyro line (sparse fallback)
        if GYRO in line and f.get("sei_yaw_true_deg") is not None and f.get("sei_t_ms") is not None:
            sei.append({
                "t_ms": int(f["sei_t_ms"]),
                "yaw_true_deg": float(f["sei_yaw_true_deg"]),
                "stream": None,
            })
    sei.sort(key=lambda r: r["t_ms"])
    gyro.sort(key=lambda r: r["t_ms"])
    return gyro, sei


def unwrap_deg(values):
    out, acc, prev = [], 0.0, None
    for v in values:
        if prev is None:
            acc = v
        else:
            d = (v - prev + 180.0) % 360.0 - 180.0
            acc += d
        out.append(acc)
        prev = v
    return out


def interp(t, ts, ys):
    """Linear interpolate ys at t. None if t is outside the series."""
    if not ts:
        return None
    if t < ts[0] or t > ts[-1]:
        return None
    lo, hi = 0, len(ts) - 1
    while lo + 1 < hi:
        mid = (lo + hi) // 2
        if ts[mid] <= t:
            lo = mid
        else:
            hi = mid
    if ts[hi] == ts[lo]:
        return ys[lo]
    u = (t - ts[lo]) / (ts[hi] - ts[lo])
    return ys[lo] + u * (ys[hi] - ys[lo])


def linreg(xs, ys):
    n = len(xs)
    if n < 2:
        return None, None
    mx = sum(xs) / n
    my = sum(ys) / n
    den = sum((x - mx) ** 2 for x in xs)
    if den == 0:
        return None, my
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den
    intercept = my - slope * mx
    return slope, intercept


def detect_turns(t_ms, unwrapped, min_span=300.0):
    """Yield (start_index, end_index, direction) for stretches of ~360 deg travel."""
    n = len(unwrapped)
    i = 0
    while i < n - 1:
        while i < n - 1 and abs(unwrapped[i + 1] - unwrapped[i]) < 1e-6:
            i += 1
        if i >= n - 1:
            break
        sign = 1 if unwrapped[i + 1] > unwrapped[i] else -1
        j = i + 1
        while j < n and (unwrapped[j] - unwrapped[j - 1]) * sign >= -1.0:
            if abs(unwrapped[j] - unwrapped[i]) >= min_span:
                # close the turn near a multiple of 360
                span = unwrapped[j] - unwrapped[i]
                if abs(span) >= 300:
                    k = j
                    while k + 1 < n and (unwrapped[k + 1] - unwrapped[k]) * sign >= -1.0 and abs(unwrapped[k + 1] - unwrapped[i]) < abs(span) + 30:
                        k += 1
                        if abs(abs(unwrapped[k] - unwrapped[i]) - 360) < abs(abs(span) - 360):
                            j, span = k, unwrapped[k] - unwrapped[i]
                    yield i, j, sign
                    i = j
                    break
            j += 1
        else:
            i = j


def analyze(gyro, sei):
    if not sei:
        raise SystemExit("no Btrx sei_yaw (or sei_yaw_true_deg on a gyro_yaw line) in the log")
    if not gyro:
        raise SystemExit("no Btrx gyro_yaw / STEER yaw_x10 lines in the log")
    sei_t = [r["t_ms"] for r in sei]
    sei_u = unwrap_deg([r["yaw_true_deg"] % 360.0 for r in sei])
    # held-bias unwrap from 0-360 readings
    fixed_wrapped = [(r["yaw_fixed_x10"] / 10.0) % 360.0 for r in gyro]
    fixed_u = unwrap_deg(fixed_wrapped)
    for r, fu in zip(gyro, fixed_u):
        r["unwrapped_fixed"] = fu

    rows = []
    first_off = None
    first_off_fixed = None
    for r, fu in zip(gyro, fixed_u):
        sei_at = interp(r["t_ms"], sei_t, sei_u)
        item = dict(r)
        item["sei_unwrapped"] = sei_at
        if sei_at is None or not r["ready"]:
            item["diff"] = None
            item["diff_fixed"] = None
            rows.append(item)
            continue
        if first_off is None:
            first_off = r["unwrapped"] - sei_at
            first_off_fixed = fu - sei_at
        item["diff"] = r["unwrapped"] - sei_at - first_off
        item["diff_fixed"] = fu - sei_at - first_off_fixed
        rows.append(item)

    used = [r for r in rows if r["diff"] is not None]

    def sei_rate_at(t_ms):
        i = 0
        while i + 1 < len(sei_t) and sei_t[i + 1] < t_ms:
            i += 1
        if 0 < i < len(sei_t) - 1:
            dt = (sei_t[i + 1] - sei_t[i - 1]) / 1000.0
            return abs(sei_u[i + 1] - sei_u[i - 1]) / dt if dt > 0 else 0.0
        if i == 0 and len(sei_t) > 1:
            dt = (sei_t[1] - sei_t[0]) / 1000.0
            return abs(sei_u[1] - sei_u[0]) / dt if dt > 0 else 0.0
        if i >= 1:
            dt = (sei_t[i] - sei_t[i - 1]) / 1000.0
            return abs(sei_u[i] - sei_u[i - 1]) / dt if dt > 0 else 0.0
        return 0.0

    # still/hover points, then the longest contiguous still stretch (so a later
    # pause after a turn, which sits on a different offset step, is not mixed in)
    marked = []
    for r in used:
        flags_still = (r["yflags"] & 0x04) != 0
        marked.append(r if (sei_rate_at(r["t_ms"]) <= STILL_SEI_DPS or flags_still) else None)
    segments, cur = [], []
    for r in marked:
        if r is None:
            if cur:
                segments.append(cur)
                cur = []
        else:
            if cur and r["t_ms"] - cur[-1]["t_ms"] > 5000:
                segments.append(cur)
                cur = []
            cur.append(r)
    if cur:
        segments.append(cur)
    still = max(segments, key=len) if segments else []

    def slope_of(subset):
        if len(subset) < 2:
            return None
        t0 = subset[0]["t_ms"]
        xs = [(r["t_ms"] - t0) / 60000.0 for r in subset]  # minutes
        ys = [r["diff"] for r in subset]
        s, _ = linreg(xs, ys)
        return s

    slope_still = slope_of(still) if still else slope_of(used)
    slope_all = slope_of(used)

    jumps = []
    if used:
        t_u = [r["t_ms"] for r in used]
        sei_at_used = [r["sei_unwrapped"] for r in used]
        for a, b, sign in detect_turns(t_u, sei_at_used):
            jump = used[b]["diff"] - used[a]["diff"]
            span = sei_at_used[b] - sei_at_used[a]
            jumps.append({"start_ms": used[a]["t_ms"], "end_ms": used[b]["t_ms"],
                          "span_deg": span, "jump_deg": jump, "dir": sign})
    mean_jump = sum(j["jump_deg"] for j in jumps) / len(jumps) if jumps else None
    return {
        "rows": rows,
        "offset": first_off,
        "offset_fixed": first_off_fixed,
        "slope_still_deg_per_min": slope_still,
        "slope_all_deg_per_min": slope_all,
        "n_used": len(used),
        "n_still": len(still),
        "turns": jumps,
        "mean_turn_jump_deg": mean_jump,
    }


def write_csv(path, result):
    cols = ["t_ms", "arrival_ms", "window_end_ms", "sample_ms", "t_off_ms", "ready",
            "yaw_x10", "yaw_fixed_x10", "turns", "bias_z_mdps", "yflags",
            "pod_unwrapped_deg", "pod_fixed_unwrapped_deg", "sei_unwrapped_deg",
            "diff_deg", "diff_fixed_deg"]
    with open(path, "w") as f:
        f.write(",".join(cols) + "\n")
        for r in result["rows"]:
            def fmt(v):
                if v is None:
                    return ""
                if isinstance(v, float):
                    return "%.6f" % v
                if isinstance(v, bool):
                    return "1" if v else "0"
                return str(v)
            vals = [
                r["t_ms"], r.get("arrival_ms"), r.get("window_end_ms"), r.get("sample_ms"),
                r.get("t_off_ms"), r["ready"], r["yaw_x10"], r["yaw_fixed_x10"], r["turns"],
                r.get("bias_z_mdps"), r.get("yflags"), r["unwrapped"], r.get("unwrapped_fixed"),
                r.get("sei_unwrapped"), r.get("diff"), r.get("diff_fixed"),
            ]
            f.write(",".join(fmt(v) for v in vals) + "\n")


def _png_chunk(tag, data):
    crc = zlib.crc32(tag + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)


def write_png(path, result, width=900, height=500):
    """Simple line plot of diff_deg and diff_fixed_deg versus time, no extra deps."""
    used = [r for r in result["rows"] if r["diff"] is not None]
    img = bytearray([255, 255, 255]) * (width * height)

    def px(x, y, rgb):
        if 0 <= x < width and 0 <= y < height:
            i = (y * width + x) * 3
            img[i:i + 3] = bytes(rgb)

    left, right, top, bot = 50, width - 20, 20, height - 40
    # axes
    for x in range(left, right):
        px(x, bot, (0, 0, 0))
        px(x, top, (200, 200, 200))
    for y in range(top, bot + 1):
        px(left, y, (0, 0, 0))
        px(right - 1, y, (200, 200, 200))
    if len(used) >= 2:
        t0, t1 = used[0]["t_ms"], used[-1]["t_ms"]
        ys = [r["diff"] for r in used] + [r["diff_fixed"] for r in used if r.get("diff_fixed") is not None]
        ymin, ymax = min(ys), max(ys)
        if ymin == ymax:
            ymin, ymax = ymin - 1, ymax + 1
        pad = (ymax - ymin) * 0.08
        ymin -= pad
        ymax += pad
        # zero line
        if ymin < 0 < ymax:
            zy = bot - int(round((0 - ymin) / (ymax - ymin) * (bot - top)))
            for x in range(left, right):
                px(x, zy, (180, 180, 180))

        def to_xy(t, v):
            x = left + int(round((t - t0) / (t1 - t0) * (right - left - 1)))
            y = bot - int(round((v - ymin) / (ymax - ymin) * (bot - top)))
            return x, y

        def draw(series_key, rgb):
            prev = None
            for r in used:
                v = r.get(series_key)
                if v is None:
                    prev = None
                    continue
                x, y = to_xy(r["t_ms"], v)
                if prev is not None:
                    x0, y0 = prev
                    steps = max(abs(x - x0), abs(y - y0), 1)
                    for s in range(steps + 1):
                        px(x0 + (x - x0) * s // steps, y0 + (y - y0) * s // steps, rgb)
                prev = (x, y)

        draw("diff", (20, 70, 180))
        draw("diff_fixed", (200, 90, 20))
    # pack PNG
    raw = b"".join(b"\x00" + bytes(img[y * width * 3:(y + 1) * width * 3]) for y in range(height))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", ihdr) + _png_chunk(b"IDAT", zlib.compress(raw, 9)) + _png_chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def report(result, out=sys.stdout):
    def fmt(v, unit=""):
        if v is None:
            return "n/a"
        return "%.4f%s" % (v, unit)
    out.write("samples used: %d (still/hover: %d)\n" % (result["n_used"], result["n_still"]))
    out.write("first offset (pod − SEI): %s\n" % fmt(result["offset"], " deg"))
    out.write("drift slope, still/hover: %s\n" % fmt(result["slope_still_deg_per_min"], " deg/min"))
    out.write("drift slope, all samples: %s\n" % fmt(result["slope_all_deg_per_min"], " deg/min"))
    out.write("turns detected: %d\n" % len(result["turns"]))
    for i, t in enumerate(result["turns"], 1):
        out.write("  turn %d: span %.1f deg, jump %.3f deg\n" % (i, t["span_deg"], t["jump_deg"]))
    out.write("mean per-turn jump: %s\n" % fmt(result["mean_turn_jump_deg"], " deg"))


def synthetic_log():
    """10 min still (0.5 deg/min drift) then three 360 deg turns with +2 deg scale error each."""
    lines = []
    t0 = 1_700_000_000_000
    # SEI at 10 Hz, pod at 1 Hz
    still_s = 600
    drift = 0.5 / 60.0  # deg/s
    sei0 = 10.0
    # still
    for ms in range(0, still_s * 1000 + 1, 100):
        t = t0 + ms
        lines.append("Btrx sei_yaw t_ms=%d yaw_true_deg=%.3f stream=drone" % (t, sei0))
    pod = 0.0
    for s in range(0, still_s + 1):
        t = t0 + s * 1000
        pod = drift * s
        yaw_x10 = int(round((pod % 360) * 10))
        turns = int(math.floor(pod / 360.0))
        lines.append(
            "Btrx gyro_yaw arrival_ms=%d window_end_ms=%d sample_ms=%d yaw_x10=%d yaw_fixed_x10=%d "
            "turns=%d bias_z_mdps=-345 yflags=0x07 t_off_ms=0 backlog=0" % (
                t, s * 1000, t, yaw_x10, yaw_x10, turns))
    # three turns at 15 deg/s, 2 deg extra on the pod per turn; 4 s pause between
    t_base = t0 + still_s * 1000
    elapsed = still_s
    pod = drift * still_s
    sei = sei0
    rate = 15.0
    extra_per_turn = 2.0
    pause = 4
    for turn in range(3):
        dur = 360.0 / rate
        n = int(round(dur))
        for s in range(1, n + 1):
            elapsed += 1
            t = t0 + elapsed * 1000
            sei = sei0 + (turn * 360) + rate * s
            pod = drift * elapsed + (turn * (360 + extra_per_turn)) + (rate + extra_per_turn / (360 / rate)) * s
            # cleaner: at end of turn, sei += 360, pod += 360+2
            frac = s / n
            sei = sei0 + turn * 360 + 360 * frac
            pod = drift * elapsed + turn * (360 + extra_per_turn) + (360 + extra_per_turn) * frac
            for k in range(10):
                tt = t - 900 + k * 100
                sei_k = sei0 + turn * 360 + 360 * ((s - 1 + k / 10.0) / n)
                lines.append("Btrx sei_yaw t_ms=%d yaw_true_deg=%.3f stream=drone" % (tt, sei_k % 360.0))
            yaw_x10 = int(round((pod % 360) * 10)) % 3600
            turns = int(math.floor(pod / 360.0))
            lines.append(
                "Btrx gyro_yaw arrival_ms=%d window_end_ms=%d sample_ms=%d yaw_x10=%d yaw_fixed_x10=%d "
                "turns=%d bias_z_mdps=-345 yflags=0x03 t_off_ms=0 backlog=0" % (
                    t, elapsed * 1000, t, yaw_x10, yaw_x10, turns))
        for s in range(1, pause + 1):
            elapsed += 1
            t = t0 + elapsed * 1000
            sei = sei0 + (turn + 1) * 360
            pod = drift * elapsed + (turn + 1) * (360 + extra_per_turn)
            for k in range(10):
                lines.append("Btrx sei_yaw t_ms=%d yaw_true_deg=%.3f stream=drone" % (t - 900 + k * 100, sei % 360.0))
            yaw_x10 = int(round((pod % 360) * 10)) % 3600
            turns = int(math.floor(pod / 360.0))
            lines.append(
                "Btrx gyro_yaw arrival_ms=%d window_end_ms=%d sample_ms=%d yaw_x10=%d yaw_fixed_x10=%d "
                "turns=%d bias_z_mdps=-345 yflags=0x03 t_off_ms=0 backlog=0" % (
                    t, elapsed * 1000, t, yaw_x10, yaw_x10, turns))
    return "\n".join(lines) + "\n"


def self_test():
    text = synthetic_log()
    gyro, sei = parse_log(text)
    result = analyze(gyro, sei)
    slope = result["slope_still_deg_per_min"]
    jump = result["mean_turn_jump_deg"]
    ok = True
    if slope is None or abs(slope - 0.5) > 0.08:
        print("FAIL still slope %s (want ~0.5 deg/min)" % slope)
        ok = False
    else:
        print("ok still slope %.4f deg/min" % slope)
    if jump is None or abs(jump - 2.0) > 0.35:
        print("FAIL mean turn jump %s (want ~2 deg); turns=%s" % (jump, result["turns"]))
        ok = False
    else:
        print("ok mean turn jump %.4f deg (%d turns)" % (jump, len(result["turns"])))
    if result["n_used"] < 500:
        print("FAIL too few used samples: %d" % result["n_used"])
        ok = False
    fd, csv_path = tempfile.mkstemp(suffix=".csv")
    os.close(fd)
    fd, png_path = tempfile.mkstemp(suffix=".png")
    os.close(fd)
    try:
        write_csv(csv_path, result)
        write_png(png_path, result)
        with open(csv_path) as f:
            ncsv = sum(1 for _ in f) - 1
        png_ok = open(png_path, "rb").read(8) == b"\x89PNG\r\n\x1a\n"
        if ncsv != len(result["rows"]) or not png_ok:
            print("FAIL outputs csv_rows=%d png_ok=%s" % (ncsv, png_ok))
            ok = False
        else:
            print("ok wrote CSV (%d rows) and PNG" % ncsv)
    finally:
        os.remove(csv_path)
        os.remove(png_path)
    # convert_yawtest must not assume ext_format == 1
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    sys.path.insert(0, os.path.join(repo, "tools", "btrx_bearing_test"))
    import convert_yawtest
    fake = (
        "Nov120000.000 PST DEBUG Btrx rx from=desk STEER seq=1 pod=00000001 flags=none  "
        "steer_seq=7 steer_flags=target src=1 window_end_ms=1000 roll_x2=0 pitch_x2=0 "
        "ext_format=2 yaw_x10=123 t_off_ms=-20 sample_ms=980\n"
    )
    fd, log_path = tempfile.mkstemp(suffix=".log")
    os.close(fd)
    try:
        with open(log_path, "w") as f:
            f.write(fake)
        rows = convert_yawtest.steer_lines(log_path)
        if not rows:
            print("FAIL convert_yawtest produced no R lines")
            ok = False
        else:
            parts = rows[0][1].split()
            # BBv1 R t we seq flags roll pitch backlog fmt trunc n
            fmt = int(parts[9])
            if fmt != 2:
                print("FAIL convert_yawtest assumed a format other than the log's 2: %s" % rows[0][1])
                ok = False
            else:
                print("ok convert_yawtest passes ext_format=2 through to the BBv1 R line")
    finally:
        os.remove(log_path)
    if not ok:
        raise SystemExit(1)
    print("self-test passed")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log", nargs="?", help="session / BTRX log file")
    ap.add_argument("--out-csv", help="CSV path (default: LOG.yaw-drift.csv)")
    ap.add_argument("--out-png", help="PNG path (default: LOG.yaw-drift.png)")
    ap.add_argument("--self-test", action="store_true", help="run the synthetic-data checks and exit")
    a = ap.parse_args()
    if a.self_test:
        self_test()
        return
    if not a.log:
        ap.error("LOG is required unless --self-test is set")
    text = open(a.log, errors="replace").read()
    gyro, sei = parse_log(text)
    result = analyze(gyro, sei)
    report(result)
    csv_path = a.out_csv or (a.log + ".yaw-drift.csv")
    png_path = a.out_png or (a.log + ".yaw-drift.png")
    write_csv(csv_path, result)
    write_png(png_path, result)
    sys.stderr.write("wrote %s and %s\n" % (csv_path, png_path))


if __name__ == "__main__":
    main()
