#!/usr/bin/env python3
"""Convert the November 12 yaw-test logs into a BBv1 input log (BEARING-LAYER-spec.md 9.2).

Inputs (defaults under ~/btrx-build-logs):
  yawtest-2021-Log_201714-card.txt   session log with "Btrx rx [backlog] ... STEER ..." lines
  yawtest-2021-sei-fields.csv        per-frame DJI SEI fields (camera_bearing_true_deg = raw - 90)

The host clock is the log's wall time (microseconds since local midnight). The pod
sat still indoors, so a fixed position is used: this checks timing, yaw sync, wraps
and gates, not geometry. No raw addresses exist in the log (only a 4-hex tail), so the
source key is a hash of (tail, addr_type).

Usage: convert_yawtest.py [--log LOG] [--sei CSV] [--out OUT.bbv1]
"""
import argparse
import hashlib
import os
import re
import sys

FLAG_BITS = {"target": 0x01, "new": 0x02, "linked": 0x04, "gated": 0x08, "imu": 0x10, "muted": 0x20, "cal": 0x40}
KINDS = {"unknown": 0, "phone": 1, "wearable": 2, "watch": 2, "tracker": 3, "tag": 3, "audio": 4, "computer": 5}
LAT, LON, AGL = 37.0, -122.0, 1.5

STEER_RE = re.compile(r"Nov(\d{2})(\d{2})(\d{2})\.(\d{3}) PST.*Btrx rx (backlog )?.*STEER .*?steer_seq=(\d+) steer_flags=(\S+) (.*)$")


def wall_us(hh, mm, ss, ms):
    return ((int(hh) * 60 + int(mm)) * 60 + int(ss)) * 1000000 + int(ms) * 1000


def field(rest, name, default=None):
    m = re.search(r"\b%s=(\S+)" % name, rest)
    return m.group(1) if m else default


def steer_lines(path):
    out = []
    with open(path, errors="replace") as f:
        for line in f:
            m = STEER_RE.search(line)
            if not m:
                continue
            hh, mm, ss, ms, backlog, seq, flags_s, rest = m.groups()
            t = wall_us(hh, mm, ss, ms)
            flags = 0
            for name in flags_s.split(","):
                flags |= FLAG_BITS.get(name, 0)
            we = int(field(rest, "window_end_ms", "0"))
            roll = int(field(rest, "roll_x2", "0"))
            pitch = int(field(rest, "pitch_x2", "0"))
            entries = []
            if flags & 0x01:
                tail = (re.search(r"\[\.\.([0-9A-Fa-f]+)\]", rest) or [None, "0"])[1]
                atype = int(field(rest, "addr_type", "0"))
                key = hashlib.blake2b(("%s:%d" % (tail, atype)).encode(), digest_size=8).hexdigest()
                cid = field(rest, "cid", "0x0")
                entries.append("%d %s %d %d %d %d %d %d %d %d %d %d" % (
                    int(field(rest, "src", "0")), key, int(field(rest, "rssi_port", "-128")), int(field(rest, "rssi_stbd", "-128")),
                    int(field(rest, "peak", "-128")), int(field(rest, "hits_port", "0")), int(field(rest, "hits_stbd", "0")),
                    int(field(rest, "age_ms", "0")), 1 if flags & 0x02 else 0, 1 if flags & 0x04 else 0,
                    KINDS.get(field(rest, "kind", "unknown"), 0), int(cid, 16)))
            # ext_format is taken from the log when present (format 2 from firmware 0x010C).
            # Older logs have no field; do not assume format 1. The BBv1 R line stores the
            # number as-is; replay does not require it to be 1.
            fmt = int(field(rest, "ext_format", "0"))
            trunc = 1 if field(rest, "ext_truncated", "0") in ("1", "true") else 0
            out.append((t, "BBv1 R %d %d %d %d %d %d %d %d %d %d %s" % (
                t, we, int(seq), flags, roll, pitch, 1 if backlog else 0, fmt, trunc, len(entries), " ".join(entries))))
    return out


def yaw_lines(path):
    out = []
    with open(path) as f:
        header = f.readline().strip().split(",")
        ci = header.index("clock_pst")
        yi = [i for i, h in enumerate(header) if h.startswith("camera_bearing_true_deg")][0]
        for line in f:
            p = line.strip().split(",")
            if len(p) <= yi:
                continue
            hh, mm, rest = p[ci].split(":")
            ss, ms = rest.split(".")
            t = wall_us(hh, mm, ss, ms)
            out.append((t, "BBv1 P %d 4 0 0 0 %s 0 0" % (t, p[yi])))
    return out


def main():
    base = os.path.expanduser("~/btrx-build-logs")
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default=os.path.join(base, "yawtest-2021-Log_201714-card.txt"))
    ap.add_argument("--sei", default=os.path.join(base, "yawtest-2021-sei-fields.csv"))
    ap.add_argument("--out", default="-")
    a = ap.parse_args()
    steer = steer_lines(a.log)
    yaw = yaw_lines(a.sei)
    if not steer:
        sys.exit("no STEER lines in %s" % a.log)
    t0, t1 = steer[0][0] - 2000000, steer[-1][0] + 2000000
    pos = [(t, "BBv1 P %d 3 %.7f %.7f %.1f 0 0 0" % (t, LAT, LON, AGL)) for t in range(t0, t1, 1000000)]
    events = sorted(steer + yaw + pos, key=lambda e: e[0])
    out = sys.stdout if a.out == "-" else open(a.out, "w")
    for _, line in events:
        out.write(line + "\n")
    if out is not sys.stdout:
        out.close()
    sys.stderr.write("BBv1: %d STEER (%d backlog), %d yaw, %d position lines\n" % (
        len(steer), sum(1 for _, l in steer if l.split()[8] == "1"), len(yaw), len(pos)))


if __name__ == "__main__":
    main()
