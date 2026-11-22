#!/usr/bin/env python3
"""小爱逆向扫描器：按结构特征在 dexdump 结果里找类/方法/字段/字符串。

用法：
  scan.py classes --contains buildOpenAIRequest
  scan.py classes --contains streamCompletion --limit 20
  scan.py classes --field-type kotlinx/serialization/json/Json
  scan.py classes --ctor-shape object-bool
  scan.py strings --grep tool_calls

约定：
  - 只读 artifacts/reverse/ 下的 dump / dex，不改任何东西；
  - dump 是 dexdump 输出，通常是 UTF-16；
  - 命中只打印类名/方法名/签名，不打印业务字符串内容。
"""
import argparse
import glob
import io
import os
import sys

DEFAULT_BASE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "artifacts", "reverse")


def read_text(path):
    with open(path, "rb") as f:
        head = f.read(2)
    enc = "utf-16" if head in (b"\xff\xfe", b"\xfe\xff") else "utf-8"
    return io.open(path, encoding=enc, errors="replace")


class ClassInfo:
    __slots__ = ("name", "super_name", "methods", "fields")

    def __init__(self, name):
        self.name = name
        self.super_name = ""
        self.methods = []   # (name, type)
        self.fields = []    # (name, type)


def parse_dumps(paths):
    """返回 {class_name: ClassInfo}；只保留结构，不保留代码。"""
    out = {}
    for path in paths:
        cur = None
        section = ""
        pending = None
        for line in read_text(path):
            s = line.strip()
            if s.startswith("Class descriptor"):
                m = s.split(":", 1)[1].strip().strip("'")
                cur = ClassInfo(m)
                out[m] = cur
                section = ""
                pending = None
                continue
            if cur is None:
                continue
            if s.startswith("Superclass"):
                cur.super_name = s.split(":", 1)[1].strip().strip("'")
                continue
            if s.startswith("Static fields"):
                section = "field"
                continue
            if s.startswith("Instance fields"):
                section = "field"
                continue
            if s.startswith("Direct methods") or s.startswith("Virtual methods"):
                section = "method"
                continue
            if s.startswith("name"):
                pending = s.split(":", 1)[1].strip().strip("'") if ":" in s else ""
                continue
            if s.startswith("type") and pending is not None:
                t = s.split(":", 1)[1].strip().strip("'") if ":" in s else ""
                if section == "method":
                    cur.methods.append((pending, t))
                elif section == "field":
                    cur.fields.append((pending, t))
                pending = None
    return out


def cmd_classes(args, classes):
    hits = 0
    for name, info in sorted(classes.items()):
        if args.class_contains and args.class_contains not in name:
            continue
        matched = []
        if args.contains:
            matched = [(n, t) for (n, t) in info.methods if args.contains in n]
            if not matched:
                continue
            for n, t in matched[:args.per_class]:
                print("%s  %s  %s" % (name, n, t))
        elif args.field_type or args.ctor_shape:
            if args.field_type:
                matched = [(n, t) for (n, t) in info.fields if args.field_type in t]
                if not matched:
                    continue
                for n, t in matched[:args.per_class]:
                    print("%s  field %s  %s" % (name, n, t))
            else:
                want = args.ctor_shape.lower()
                for n, t in info.methods:
                    if n != "<init>":
                        continue
                    if want == "object-bool" and not _ctor_object_bool(t):
                        continue
                    print("%s  %s  %s" % (name, n, t))
                    break
        else:
            print("%s  methods=%d fields=%d  super=%s"
                  % (name, len(info.methods), len(info.fields), info.super_name))
        hits += 1
        if hits >= args.limit:
            print("... 已达上限 %d，可用 --limit 放大" % args.limit)
            break
    if hits == 0:
        print("（无命中）")


def _ctor_object_bool(type_desc):
    # 形如 '(Ljava/lang/Object;Z...' 即 Object + boolean 开头
    return type_desc.startswith("(Ljava/lang/Object;Z")


def cmd_strings(args, base):
    pattern = args.grep.encode("utf-8")
    total = 0
    for dex in sorted(glob.glob(os.path.join(base, "*", "dex", "classes*.dex"))):
        with open(dex, "rb") as f:
            data = f.read()
        idx = data.find(pattern)
        if idx < 0:
            continue
        count = data.count(pattern)
        total += count
        print("%s  hits=%d  首处 offset=0x%x" % (os.path.basename(dex), count, idx))
        if args.limit and total >= args.limit:
            break
    if total == 0:
        print("（未找到；确认 dump-dex 已解出全部 dex）")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default=DEFAULT_BASE, help="artifacts/reverse 根目录")
    sub = ap.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("classes", help="按方法名/字段类型/构造器形状找类")
    c.add_argument("--contains", help="方法名包含该子串")
    c.add_argument("--class-contains", help="类名包含该子串（过滤）")
    c.add_argument("--field-type", help="字段类型包含该子串")
    c.add_argument("--ctor-shape", help="构造器形状：object-bool")
    c.add_argument("--per-class", type=int, default=3)
    c.add_argument("--limit", type=int, default=40)

    s = sub.add_parser("strings", help="在 dex 里搜字符串")
    s.add_argument("--grep", required=True)
    s.add_argument("--limit", type=int, default=20)

    args = ap.parse_args()

    if args.cmd == "strings":
        cmd_strings(args, args.base)
        return

    dumps = sorted(glob.glob(os.path.join(args.base, "*", "dex", "*.dump.txt")))
    if not dumps:
        print("✗ 没找到 dump；先跑 reverse/scripts/dump-dex.sh", file=sys.stderr)
        sys.exit(1)
    classes = parse_dumps(dumps)
    print("# 解析 %d 个 dump，%d 个类" % (len(dumps), len(classes)))
    cmd_classes(args, classes)


if __name__ == "__main__":
    main()
