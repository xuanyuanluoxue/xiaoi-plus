#!/usr/bin/env python3
"""DEX 索引器：把 classes*.dex 解析成 SQLite，供快速检索。

为什么不用 dexdump：32 个 dex（341 MB）dump 成文本是 GB 级，还要按 UTF-16 读；
直接读 DEX 结构只取「类/方法/字段/字符串」，几秒完成，且可反复查询。

用法：
  dexindex.py build [--version 8.2.70.8421]
  dexindex.py stats
  dexindex.py classes --prefix com/aios/apptoolsdk
  dexindex.py methods --contains buildOpenAIRequest
  dexindex.py fields --type kotlinx/serialization/json/Json
  dexindex.py strings --grep tool_calls
  dexindex.py class --name Lqm/i;
"""
import argparse
import glob
import os
import sqlite3
import struct
import sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "..", "..", "artifacts", "reverse")


def uleb128(buf, pos):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


class Dex:
    def __init__(self, buf):
        self.buf = buf
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = self._header()
        self._strings = {}
        self._types = {}

    def _header(self):
        b = self.buf
        if b[:4] != b"dex\n":
            raise ValueError("not a dex")
        # 112 字节头；末尾 6 组 (size, off) 依次是 string/type/proto/field/method/class
        vals = struct.unpack_from("<12I", b, 56)
        return tuple(vals)

    def u32(self, off):
        return struct.unpack_from("<I", self.buf, off)[0]

    def string(self, idx):
        v = self._strings.get(idx)
        if v is not None:
            return v
        off = self.u32(self.string_ids_off + idx * 4)
        _, pos = uleb128(self.buf, off)
        end = self.buf.index(b"\x00", pos)
        raw = self.buf[pos:end]
        v = raw.decode("utf-8", "replace")
        self._strings[idx] = v
        return v

    def type_name(self, idx):
        v = self._types.get(idx)
        if v is not None:
            return v
        v = self.string(self.u32(self.type_ids_off + idx * 4))
        self._types[idx] = v
        return v

    def proto(self, idx):
        base = self.proto_ids_off + idx * 12
        ret = self.type_name(self.u32(base + 4))
        params_off = self.u32(base + 8)
        params = ""
        if params_off:
            # type_list: u32 size 紧接 size 个 u16 type_idx
            n = self.u32(params_off)
            types = [self.type_name(struct.unpack_from("<H", self.buf, params_off + 4 + i * 2)[0])
                     for i in range(n)]
            params = "".join(types)
        return "(%s)%s" % (params, ret)


def build(version):
    dex_dir = os.path.join(BASE, "xiaoi-" + version, "dex")
    out_dir = os.path.join(BASE, "xiaoi-" + version, "index")
    os.makedirs(out_dir, exist_ok=True)
    db_path = os.path.join(out_dir, "dex.db")
    if os.path.exists(db_path):
        os.remove(db_path)

    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.executescript("""
        CREATE TABLE classes(name TEXT, super TEXT, dex TEXT);
        CREATE TABLE methods(cls TEXT, name TEXT, proto TEXT, dex TEXT);
        CREATE TABLE fields(cls TEXT, name TEXT, type TEXT, dex TEXT);
        CREATE TABLE strings(value TEXT, dex TEXT);
        CREATE INDEX idx_methods_name ON methods(name);
        CREATE INDEX idx_fields_type ON fields(type);
        CREATE INDEX idx_classes_name ON classes(name);
        CREATE INDEX idx_strings_value ON strings(value);
    """)

    files = sorted(glob.glob(os.path.join(dex_dir, "classes*.dex")))
    if not files:
        print("✗ 没找到 dex；先跑 extract-dex.sh", file=sys.stderr)
        sys.exit(1)

    total_c = total_m = total_f = total_s = 0
    for path in files:
        name = os.path.basename(path)
        with open(path, "rb") as f:
            dex = Dex(f.read())

        classes = []
        for i in range(dex.class_defs_size):
            base = dex.class_defs_off + i * 32
            cls = dex.type_name(dex.u32(base))
            sup_idx = dex.u32(base + 8)
            sup = dex.type_name(sup_idx) if sup_idx != 0xFFFFFFFF else ""
            classes.append((cls, sup, name))

        methods = []
        for i in range(dex.method_ids_size):
            base = dex.method_ids_off + i * 8
            cls_idx, proto_idx, name_idx = struct.unpack_from("<HHI", dex.buf, base)
            methods.append((dex.type_name(cls_idx), dex.string(name_idx),
                            dex.proto(proto_idx), name))

        fields = []
        for i in range(dex.field_ids_size):
            base = dex.field_ids_off + i * 8
            cls_idx, type_idx, name_idx = struct.unpack_from("<HHI", dex.buf, base)
            fields.append((dex.type_name(cls_idx), dex.string(name_idx),
                           dex.type_name(type_idx), name))

        strs = [(dex.string(i), name) for i in range(dex.string_ids_size)]

        cur.executemany("INSERT INTO classes VALUES (?,?,?)", classes)
        cur.executemany("INSERT INTO methods VALUES (?,?,?,?)", methods)
        cur.executemany("INSERT INTO fields VALUES (?,?,?,?)", fields)
        cur.executemany("INSERT INTO strings VALUES (?,?)", strs)

        total_c += len(classes); total_m += len(methods)
        total_f += len(fields); total_s += len(strs)
        print("  %-18s classes=%-6d methods=%-7d fields=%-6d strings=%d"
              % (name, len(classes), len(methods), len(fields), len(strs)))

    conn.commit()
    print("\n✓ %s" % db_path)
    print("  classes=%d methods=%d fields=%d strings=%d"
          % (total_c, total_m, total_f, total_s))
    conn.close()


def open_db(version):
    path = os.path.join(BASE, "xiaoi-" + version, "index", "dex.db")
    if not os.path.exists(path):
        print("✗ 索引不存在；先跑 dexindex.py build", file=sys.stderr)
        sys.exit(1)
    return sqlite3.connect(path)


def query(args):
    conn = open_db(args.version)
    cur = conn.cursor()
    limit = args.limit

    if args.cmd == "stats":
        for t in ("classes", "methods", "fields", "strings"):
            cur.execute("SELECT COUNT(*) FROM " + t)
            print("%-8s %d" % (t, cur.fetchone()[0]))
        return

    if args.cmd == "classes":
        cur.execute("SELECT name, super FROM classes WHERE name LIKE ? LIMIT ?",
                    (args.prefix + "%", limit))
        for name, sup in cur.fetchall():
            print("%s   super=%s" % (name, sup))
        return

    if args.cmd == "methods":
        cur.execute("SELECT cls, name, proto FROM methods WHERE name LIKE ? LIMIT ?",
                    ("%" + args.contains + "%", limit))
        for cls, name, proto in cur.fetchall():
            print("%s  %s  %s" % (cls, name, proto))
        return

    if args.cmd == "fields":
        cur.execute("SELECT cls, name, type FROM fields WHERE type LIKE ? LIMIT ?",
                    ("%" + args.type + "%", limit))
        for cls, name, t in cur.fetchall():
            print("%s  %s  %s" % (cls, name, t))
        return

    if args.cmd == "strings":
        cur.execute("SELECT DISTINCT value FROM strings WHERE value LIKE ? LIMIT ?",
                    ("%" + args.grep + "%", limit))
        for (v,) in cur.fetchall():
            print(v)
        return

    if args.cmd == "pkgs":
        rows = cur.execute("SELECT name FROM classes WHERE name LIKE ?",
                           (args.prefix + "%",)).fetchall()
        depth = args.depth
        cnt = {}
        for (n,) in rows:
            parts = n[1:].split('/')
            key = '/'.join(parts[:depth])
            cnt[key] = cnt.get(key, 0) + 1
        for k, v in sorted(cnt.items(), key=lambda kv: -kv[1])[:args.limit]:
            print("%6d  %s" % (v, k))
        print("总计 %d 个类（前缀 %s，深度 %d）" % (len(rows), args.prefix, depth))
        return

    if args.cmd in ("cls", "class"):
        name = args.name
        cur.execute("SELECT super FROM classes WHERE name = ? LIMIT 1", (name,))
        row = cur.fetchone()
        print("class %s  super=%s" % (name, row[0] if row else "?"))
        cur.execute("SELECT name, proto FROM methods WHERE cls = ? ORDER BY name LIMIT ?",
                    (name, limit))
        for n, p in cur.fetchall():
            print("  m %s %s" % (n, p))
        cur.execute("SELECT name, type FROM fields WHERE cls = ? ORDER BY name LIMIT ?",
                    (name, limit))
        for n, t in cur.fetchall():
            print("  f %s %s" % (n, t))
        return


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", default="8.2.70.8421")
    ap.add_argument("--limit", type=int, default=40)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("build")
    sub.add_parser("stats")

    c = sub.add_parser("classes"); c.add_argument("--prefix", required=True)
    p = sub.add_parser("pkgs"); p.add_argument("--prefix", required=True)
    p.add_argument("--depth", type=int, default=4)
    m = sub.add_parser("methods"); m.add_argument("--contains", required=True)
    f = sub.add_parser("fields"); f.add_argument("--type", required=True)
    s = sub.add_parser("strings"); s.add_argument("--grep", required=True)
    k = sub.add_parser("cls", aliases=["class"]); k.add_argument("--name", required=True)

    # 允许把 --limit 放在子命令后面（不写默认值，避免覆盖顶层）
    for sp in (c, p, m, f, s, k):
        sp.add_argument("--limit", type=int, default=argparse.SUPPRESS)

    args = ap.parse_args()
    if args.cmd == "build":
        build(args.version)
    else:
        query(args)


if __name__ == "__main__":
    main()
