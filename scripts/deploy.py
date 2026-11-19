#!/usr/bin/env python3
"""一键构建 → 校验签名 → 安装到手机 → 拉起 App（可选启动设备动作服务）。

只用标准库；由 scripts/deploy.cmd 在仓库内 .venv 里执行。

用法：
  scripts\\deploy.cmd                        # 构建 + 校验 + 安装 + 打开
  scripts\\deploy.cmd --skip-build           # 用现有 APK，只装并打开
  scripts\\deploy.cmd --no-verify            # 跳过签名指纹比对（临时密钥构建）
  scripts\\deploy.cmd --serial 192.168.0.118:5555
  scripts\\deploy.cmd --no-agent             # 不启动 AgentService

设备选择顺序：--serial > 环境变量 XIAOI_SERIAL > 当前唯一在线设备。
一个都没有时，用 XIAOI_DEPLOY_CONNECT（host:port）尝试 adb connect。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/release/app-release.apk"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
PIN_FILE = ROOT / "scripts/expected-signer.sha256"
TOKEN_FILE = ROOT / ".secrets/xiaoi-token"
JDK_CANDIDATES = [
    Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"),
    Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.18.8-hotspot"),
]
ADB_CANDIDATES = [Path(r"D:\code\tools\adb\adb.exe")]
IS_WINDOWS = os.name == "nt"


def say(msg: str) -> None:
    print(msg, flush=True)


def step(msg: str) -> None:
    print(f"\n==> {msg}", flush=True)


def fail(msg: str) -> "NoReturn":  # type: ignore[valid-type]
    print(f"错误：{msg}", file=sys.stderr, flush=True)
    raise SystemExit(1)


def run(cmd: list[str], cwd: Path | None = None, env: dict | None = None,
        capture: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd, cwd=str(cwd or ROOT), env=env, text=True,
        capture_output=capture, encoding="utf-8", errors="replace",
    )


# --------------------------------------------------------------------- 环境

def ensure_java() -> dict:
    env = dict(os.environ)
    if env.get("JAVA_HOME") and (Path(env["JAVA_HOME"]) / "bin").exists():
        say(f"JAVA_HOME = {env['JAVA_HOME']}")
        return env
    for cand in JDK_CANDIDATES:
        if (cand / "bin").exists():
            env["JAVA_HOME"] = str(cand)
            say(f"JAVA_HOME（自动探测）= {cand}")
            return env
    if shutil.which("java"):
        say("JAVA_HOME 未设置，使用 PATH 上的 java")
        return env
    fail("找不到 JDK 21：请设置 JAVA_HOME")


def find_adb() -> str:
    env_adb = os.environ.get("ADB")
    if env_adb and Path(env_adb).exists():
        return env_adb
    found = shutil.which("adb")
    if found:
        return found
    for cand in ADB_CANDIDATES:
        if cand.exists():
            return str(cand)
    fail("找不到 adb：请把 platform-tools 加进 PATH 或设置 ADB")


def find_apksigner() -> Path | None:
    roots = []
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(key):
            roots.append(Path(os.environ[key]))
    local = ROOT / "local.properties"
    if local.exists():
        m = re.search(r"sdk\.dir=(.+)", local.read_text(encoding="utf-8", errors="ignore"))
        if m:
            roots.append(Path(m.group(1).strip()))
    roots.append(Path.home() / "AppData/Local/Android/Sdk")
    name = "apksigner.bat" if IS_WINDOWS else "apksigner"
    for root in roots:
        bt = root / "build-tools"
        if not bt.is_dir():
            continue
        for d in sorted(bt.iterdir(), reverse=True):
            cand = d / name
            if cand.exists():
                return cand
    return None


# --------------------------------------------------------------------- 构建

def build(env: dict) -> None:
    step("构建 release APK")
    cmd = [str(ROOT / ("gradlew.bat" if IS_WINDOWS else "gradlew")),
           ":app:assembleRelease", "--console=plain"]
    r = run(cmd, env=env)
    if r.returncode != 0:
        fail(f"Gradle 构建失败（exit={r.returncode}），见上方输出")
    if not APK.exists():
        fail(f"构建结束但找不到产物：{APK}")


def apk_version() -> tuple[str, str]:
    text = MANIFEST.read_text(encoding="utf-8", errors="ignore")
    name = re.search(r'android:versionName="([^"]+)"', text)
    code = re.search(r'android:versionCode="(\d+)"', text)
    return (name.group(1) if name else "?", code.group(1) if code else "?")


def verify_signature(env: dict) -> str:
    step("校验签名指纹")
    if not PIN_FILE.exists():
        fail(f"缺少指纹文件：{PIN_FILE}")
    expected = [
        ln.strip().lower() for ln in PIN_FILE.read_text(encoding="utf-8").splitlines()
        if ln.strip() and not ln.strip().startswith("#")
    ]
    signer = find_apksigner()
    if signer is None:
        fail("找不到 apksigner：检查 ANDROID_SDK_ROOT / local.properties")
    r = run([str(signer), "verify", "--print-certs", str(APK)], env=env, capture=True)
    if r.returncode != 0:
        fail("apksigner verify 失败（未签名或包结构损坏）")
    digests = [m.strip().lower() for m in re.findall(r"SHA-256 digest:\s*([0-9a-fA-F]+)", r.stdout)]
    if not digests:
        fail("apksigner 没输出证书指纹")
    hit = next((d for d in digests if d in expected), None)
    if hit is None:
        fail(f"签名指纹不匹配：APK={digests[0][:16]}… 期望={expected[0][:16]}…（临时密钥构建请加 --no-verify）")
    say(f"签名指纹匹配：{hit[:16]}…")
    return hit


# --------------------------------------------------------------------- 设备

def adb_devices(adb: str) -> list[str]:
    out = run([adb, "devices"], capture=True).stdout
    serials = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def pick_device(adb: str, want: str | None) -> str:
    step("选择设备")
    if want:
        serials = adb_devices(adb)
        if want not in serials:
            say(f"尝试连接 {want} …")
            run([adb, "connect", want], capture=True)
            serials = adb_devices(adb)
        if want not in serials:
            fail(f"设备不可用：{want}")
        say(f"设备：{want}")
        return want

    serials = adb_devices(adb)
    if not serials:
        hint = os.environ.get("XIAOI_DEPLOY_CONNECT")
        if hint:
            say(f"尝试连接 {hint} …")
            run([adb, "connect", hint], capture=True)
            serials = adb_devices(adb)
    if not serials:
        fail("没有在线设备：插 USB、或设置 XIAOI_SERIAL / XIAOI_DEPLOY_CONNECT")
    if len(serials) > 1:
        fail(f"检测到多台设备 {serials}，请用 --serial 指定")
    say(f"设备：{serials[0]}")
    return serials[0]


def install(adb: str, serial: str) -> None:
    step("安装 APK")
    r = run([adb, "-s", serial, "install", "-r", str(APK)], capture=True)
    out = (r.stdout or "") + (r.stderr or "")
    if r.returncode != 0 or "Success" not in out:
        fail("安装失败：\n" + out.strip())
    say("安装成功")


def launch(adb: str, serial: str) -> None:
    step("打开 App")
    r = run([adb, "-s", serial, "shell",
             "am start -W -n plus.xiaoi.module/.MainActivity"], capture=True)
    out = (r.stdout or "") + (r.stderr or "")
    if "Error" in out or "Exception" in out:
        fail("启动失败：\n" + out.strip())
    say("已打开 MainActivity")


def start_agent(adb: str, serial: str) -> None:
    step("启动设备动作服务（15 工具齐全）")
    r = run([adb, "-s", serial, "shell",
             "am startservice -n plus.xiaoi.module/.agent.AgentService"], capture=True)
    out = ((r.stdout or "") + (r.stderr or "")).strip()
    say(out.splitlines()[0] if out else "已请求启动；若失败请手动打开一次配置页")


def health(adb: str, serial: str) -> None:
    step("探活（8771 /health）")
    run([adb, "-s", serial, "forward", "tcp:8771", "tcp:8771"], capture=True)
    token = ""
    if TOKEN_FILE.exists():
        token = TOKEN_FILE.read_text(encoding="utf-8", errors="ignore").strip()
    req = urllib.request.Request("http://127.0.0.1:8771/health")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    last = ""
    # App 刚起来时控制面 Binder 还没连上（agent=down），等几秒再报，避免误读成失败。
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=4) as resp:
                last = resp.read().decode("utf-8", "replace").strip()
                if "agent=ipc" in last or attempt == 3:
                    break
        except Exception as exc:  # noqa: BLE001 - 探活失败不阻塞部署
            last = f"（探活失败，不影响安装）{type(exc).__name__}: {exc}"
        time.sleep(2)
    say(last)
    # /health 的 tools 只统计桥自身工具（设计如此）；合并视图要看 tools/list。
    body = b'{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
    req2 = urllib.request.Request("http://127.0.0.1:8771/mcp", data=body,
                                  headers={"Content-Type": "application/json"})
    if token:
        req2.add_header("Authorization", "Bearer " + token)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req2, timeout=4) as resp:
                tools = json.loads(resp.read().decode("utf-8", "replace"))["result"]["tools"]
            n = len(tools)
            if n >= 15 or attempt == 3:
                say(f"工具：{n} 个" + ("（桥 9 + 设备动作 6）" if n >= 15 else "（设备动作未就绪，稍等或打开一次配置页）"))
                break
        except Exception as exc:  # noqa: BLE001
            if attempt == 3:
                say(f"（tools/list 失败）{type(exc).__name__}: {exc}")
        time.sleep(2)


# --------------------------------------------------------------------- 主流程

def main() -> int:
    ap = argparse.ArgumentParser(add_help=True, description="构建并部署 xiaoi-plus 到手机")
    ap.add_argument("--serial", help="adb 序列号/地址，如 192.168.0.118:5555")
    ap.add_argument("--skip-build", action="store_true", help="跳过构建，直接用现有 APK")
    ap.add_argument("--no-verify", action="store_true", help="跳过签名指纹比对")
    ap.add_argument("--no-launch", action="store_true", help="安装后不打开 App")
    ap.add_argument("--no-agent", action="store_true", help="不启动 AgentService")
    ap.add_argument("--no-health", action="store_true", help="不探活")
    args = ap.parse_args()

    say(f"仓库：{ROOT}")
    env = ensure_java()
    adb = find_adb()

    if args.skip_build:
        step("跳过构建")
        if not APK.exists():
            fail(f"--skip-build 但产物不存在：{APK}")
    else:
        build(env)

    version, code = apk_version()
    size = APK.stat().st_size
    say(f"产物：{APK.relative_to(ROOT)}  {size:,} B  版本 {version} ({code})")

    if args.no_verify:
        step("跳过签名校验（--no-verify）")
    else:
        verify_signature(env)

    serial = pick_device(adb, args.serial or os.environ.get("XIAOI_SERIAL"))
    install(adb, serial)
    if not args.no_launch:
        launch(adb, serial)
    if not args.no_agent:
        start_agent(adb, serial)
    if not args.no_health:
        health(adb, serial)

    say("\n完成。")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    raise SystemExit(main())
