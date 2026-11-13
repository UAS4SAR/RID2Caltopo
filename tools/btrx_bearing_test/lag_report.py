#!/usr/bin/env python3
"""Join BBLAG and BBRND lines from a session log into one CSV row per report entry
and print per-stage percentiles (BEARING-LAYER-spec.md 3.5).

Usage: lag_report.py SESSION_LOG [--csv OUT.csv]
"""
import argparse
import re
import sys

LAG = re.compile(r"BBLAG (.*)$")
RND = re.compile(r"BBRND snap=(\d+) t7=(-?\d+)")
STAGES = [("pod_to_arrival", "t0", "t2"), ("arrival_to_push", "t2", "t3"), ("pose_wait", "t3", "t4"),
          ("tick_wait", "t4", "t5"), ("publish_wait", "t5", "t6"), ("render", "t6", "t7"), ("total", "t0", "t7")]


def pct(v, p):
    v = sorted(v)
    return v[(len(v) - 1) * p // 100] if v else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--csv")
    a = ap.parse_args()
    rows, rnd = [], {}
    with open(a.log, errors="replace") as f:
        for line in f:
            m = LAG.search(line)
            if m:
                kv = dict(x.split("=", 1) for x in m.group(1).split() if "=" in x)
                rows.append(kv)
                continue
            m = RND.search(line)
            if m:
                rnd.setdefault(int(m.group(1)), int(m.group(2)))
    # BBLAG lines carry t6; the snapshot that held the report is the first BBRND at or after t6
    snaps = sorted(rnd.items())
    for r in rows:
        r["t7"] = "-"
        if r.get("t6", "-") != "-":
            t6 = int(r["t6"])
            for _, t7 in snaps:
                if t7 >= t6:
                    r["t7"] = str(t7)
                    break
    cols = ["seq", "src", "rank", "fate", "wait", "t0", "t2", "t3", "t4", "t5", "t6", "t7"] + [s[0] + "_ms" for s in STAGES]
    stage_vals = {s[0]: [] for s in STAGES}
    for r in rows:
        for name, a0, b0 in STAGES:
            x, y = r.get(a0, "-"), r.get(b0, "-")
            v = (int(y) - int(x)) / 1000.0 if x != "-" and y != "-" and r.get("fate") != "history" else None
            r[name + "_ms"] = "" if v is None else "%.1f" % v
            if v is not None:
                stage_vals[name].append(v)
    if a.csv:
        with open(a.csv, "w") as out:
            out.write(",".join(cols) + "\n")
            for r in rows:
                out.write(",".join(str(r.get(c, "")) for c in cols) + "\n")
    fates = {}
    for r in rows:
        fates[r.get("fate")] = fates.get(r.get("fate"), 0) + 1
    print("entries=%d fates=%s" % (len(rows), fates))
    for name, _, _ in STAGES:
        v = stage_vals[name]
        if v:
            print("%-16s n=%4d p50=%7.1f p90=%7.1f p99=%7.1f max=%7.1f ms" % (name, len(v), pct(v, 50), pct(v, 90), pct(v, 99), max(v)))
        else:
            print("%-16s n=   0" % name)


if __name__ == "__main__":
    sys.exit(main())
