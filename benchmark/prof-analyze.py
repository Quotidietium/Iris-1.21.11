#!/usr/bin/env python
"""Aggregate JFR profile recordings produced by prof.sh.

Usage: python benchmark/prof-analyze.py <dir-with-jfr-files> [--top N]

For every <name>.jfr in the directory prints, for jdk.ExecutionSample,
the hottest leaf frames and hottest Iris frames (first com/volmit frame
on the stack), and for jdk.ObjectAllocationSample the heaviest object
classes with their allocation sites. leaf = first stack frame (JFR prints
top-of-stack first).
"""
import collections
import glob
import os
import subprocess
import sys

JFR = r"F:\Java\25\bin\jfr.exe"


def parse_events(path):
    """Yield (event_type, fields_dict, frames_list) tuples."""
    try:
        out = subprocess.run([JFR, "print", "--events",
                              "jdk.ExecutionSample,jdk.ObjectAllocationSample", path],
                             capture_output=True, text=True, timeout=300).stdout
    except FileNotFoundError:
        out = subprocess.run(["jfr", "print", "--events",
                              "jdk.ExecutionSample,jdk.ObjectAllocationSample", path],
                             capture_output=True, text=True, timeout=300).stdout
    etype = None
    fields = {}
    frames = []
    in_stack = False
    for line in out.splitlines():
        line = line.rstrip()
        if not line:
            continue
        if line.startswith("jdk.") and line.endswith("{"):
            if etype is not None:
                yield etype, fields, frames
            etype = line[:-1].strip()
            fields = {}
            frames = []
            in_stack = False
            continue
        if line == "}":
            if etype is not None:
                yield etype, fields, frames
            etype = None
            in_stack = False
            continue
        if in_stack:
            s = line.strip()
            if s.startswith("at ") or (s and not s.startswith(("startTime", "sampledThread", "thread", "stackTrace", "objectClass", "objectSize", "weight", "eventThread", "allocationSize", "tlabSize")) and "(" in s or s.startswith("at ")):
                frames.append(s)
            continue
        if line.strip().startswith("stackTrace = ["):
            in_stack = True
            continue
        if " = " in line:
            k, v = line.strip().split(" = ", 1)
            fields[k] = v


def frame_method(frame):
    # "at com.volmit.iris.Foo.bar(Foo.java:123)" -> com.volmit.iris.Foo.bar
    s = frame.strip()
    if s.startswith("at "):
        s = s[3:]
    return s.split("(")[0]


def main():
    d = sys.argv[1]
    top = 15
    if "--top" in sys.argv:
        top = int(sys.argv[sys.argv.index("--top") + 1])
    files = sorted(glob.glob(os.path.join(d, "*.jfr")))
    for f in files:
        name = os.path.splitext(os.path.basename(f))[0]
        exec_leaf = collections.Counter()
        exec_iris = collections.Counter()
        alloc_class = collections.Counter()
        n_exec = 0
        n_alloc = 0
        for etype, fields, frames in parse_events(f):
            if etype == "jdk.ExecutionSample":
                n_exec += 1
                if frames:
                    exec_leaf[frame_method(frames[0])] += 1
                for fr in frames:
                    m = frame_method(fr)
                    if m.startswith("com.volmit.iris"):
                        exec_iris[m] += 1
                        break
            elif etype == "jdk.ObjectAllocationSample":
                n_alloc += 1
                w = float(fields.get("weight", "0").replace(",", "").split(" ")[0])
                cls = fields.get("objectClass", "?").split(" ")[-1]
                site = ""
                for fr in frames:
                    m = frame_method(fr)
                    if m.startswith("com.volmit.iris"):
                        site = m
                        break
                alloc_class[(cls, site)] += w
        print(f"\n===== {name}  (exec samples: {n_exec}, alloc samples: {n_alloc}) =====")
        print("-- execution, hottest leaf frames --")
        for m, c in exec_leaf.most_common(top):
            print(f"  {c / max(1, n_exec) * 100:5.1f}%  {m}")
        print("-- execution, first iris frame on stack --")
        for m, c in exec_iris.most_common(top):
            print(f"  {c / max(1, n_exec) * 100:5.1f}%  {m}")
        print("-- allocation, heaviest classes (KB sampled) --")
        tw = sum(alloc_class.values())
        for (cls, site), w in alloc_class.most_common(top):
            print(f"  {w / max(1.0, tw) * 100:5.1f}%  {w / 1e6:10.2f} MB  {cls}  @ {site}")


if __name__ == "__main__":
    main()
