#!/usr/bin/env python3
"""枚举设备上所有 AppTool provider，并列出各自声明的工具。

AppTool 是小爱（及同类 Agent）调用系统应用能力的标准通道：
  action   com.aios.osbot.action.APP_TOOL_PROVIDER
  声明     APK 内 assets/app_tools.json
  协议     IAppToolProvider#handleRequest(JSON-RPC: tools/call)

本脚本不整包拉 APK：用 `tail -c` 远程读 zip 尾部拿到中央目录，再只取
`assets/app_tools.json` 那一个条目，省掉几百 MB 传输。

用法:
  python3 survey-apptools.py [--json] [--package NAME] [--device SERIAL]
"""
import json
import struct
import subprocess
import sys
import zlib

DEFAULT_SERIAL = "192.168.0.118:5555"
PROVIDER_ACTION = "com.aios.osbot.action.APP_TOOL_PROVIDER"
ENTRY = "assets/app_tools.json"


class Adb:
    def __init__(self, serial):
        self.base = ["adb"]
        if serial:
            self.base += ["-s", serial]

    def shell(self, cmd, timeout=120):
        return subprocess.run(self.base + ["shell", cmd], capture_output=True,
                              text=True, timeout=timeout).stdout

    def out(self, args, timeout=300):
        return subprocess.run(self.base + ["exec-out"] + args, capture_output=True,
                              timeout=timeout).stdout


def providers(adb):
    out = adb.shell(f"cmd package query-services --brief -a {PROVIDER_ACTION}")
    names = []
    for line in out.replace("\r", "").splitlines():
        line = line.strip()
        if line.startswith("com.") and "/" in line:
            names.append(line)
    return names


def apk_path(adb, pkg):
    out = adb.shell(f"pm path {pkg}").replace("\r", "")
    for line in out.splitlines():
        if line.startswith("package:"):
            return line[len("package:"):]
    return None


def _size(adb, path):
    return int(adb.shell(f"stat -c %s {path}").strip())


def _read(adb, path, start, length):
    return adb.out(["sh", "-c", f"tail -c +{start + 1} {path} | head -c {length}"])


def read_asset(adb, apk, entry=ENTRY):
    """从远程 APK 里取出单个 zip 条目（支持 deflate / store）。"""
    total = _size(adb, apk)
    tail_len = min(total, 256 * 1024)
    cd_off = cd_size = None
    for attempt in (tail_len, min(total, 4 * 1024 * 1024)):
        tail = _read(adb, apk, total - attempt, attempt)
        idx = tail.rfind(b"PK\x05\x06")
        if idx >= 0:
            eocd = tail[idx:idx + 22]
            cd_size, cd_off = struct.unpack("<II", eocd[12:20])
            break
    if cd_off is None:
        return None
    cd = _read(adb, apk, cd_off, cd_size)
    pos = 0
    while pos + 46 <= len(cd):
        if cd[pos:pos + 4] != b"PK\x01\x02":
            return None
        method, = struct.unpack("<H", cd[pos + 10:pos + 12])
        comp_size, = struct.unpack("<I", cd[pos + 20:pos + 24])
        name_len, extra_len, comment_len = struct.unpack("<HHH", cd[pos + 28:pos + 34])
        local_off, = struct.unpack("<I", cd[pos + 42:pos + 46])
        name = cd[pos + 46:pos + 46 + name_len].decode("utf-8", "replace")
        if name == entry:
            hdr = _read(adb, apk, local_off, 30)
            n_len, e_len = struct.unpack("<HH", hdr[26:30])
            data = _read(adb, apk, local_off + 30 + n_len + e_len, comp_size)
            if method == 0:
                return data
            if method == 8:
                return zlib.decompress(data, -15)
            return None
        pos += 46 + name_len + extra_len + comment_len
    return None


def main():
    args = sys.argv[1:]
    as_json = "--json" in args
    serial = DEFAULT_SERIAL
    only = None
    if "--device" in args:
        serial = args[args.index("--device") + 1]
    if "--package" in args:
        only = args[args.index("--package") + 1]

    adb = Adb(serial)
    result = []
    for svc in providers(adb):
        pkg = svc.split("/")[0]
        if only and pkg != only:
            continue
        apk = apk_path(adb, pkg)
        raw = read_asset(adb, apk) if apk else None
        tools = []
        if raw:
            try:
                tools = json.loads(raw.decode("utf-8", "replace"))
            except ValueError:
                tools = []
        result.append({"service": svc, "package": pkg, "tools": tools})

    if as_json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return

    total = 0
    for item in result:
        tools = item["tools"]
        total += len(tools)
        print(f"## {item['package']}  ({len(tools)} 个工具)  {item['service']}")
        for t in tools:
            desc = t.get("description") or (t.get("descriptions") or {}).get("zh") or ""
            desc = " ".join(desc.split())[:60]
            print(f"   - {t.get('name')}: {desc}")
    print(f"\n合计 {len(result)} 个 provider / {total} 个工具")


if __name__ == "__main__":
    main()
