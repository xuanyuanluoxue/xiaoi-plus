#!/usr/bin/env python3
"""从小爱 APK 的 assets 生成工具/Agent 目录（Markdown）。

输入（由 extract-assets.sh 解出，位于 artifacts/reverse/<版本>/assets/）：
  assets/tool_enriched.json   481 个工具（含参数 schema、BM25 文本、分组）
  assets/app_tools.json       18 个进程内 App 工具（UIAgentServiceForOSBot）
  assets/agents/*/config.json Agent 定义

输出到 stdout；仓库内的静态副本在 reverse/data/。

用法：
  python3 reverse/scripts/catalog.py --version 8.2.70.8421
  python3 reverse/scripts/catalog.py --version 8.2.70.8421 --out reverse/data/tool-catalog.md
"""
import argparse
import collections
import glob
import io
import json
import os

RE_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BASE = os.path.join(RE_ROOT, "artifacts", "reverse")


def load(version):
    d = os.path.join(BASE, "xiaoi-" + version, "assets")
    with io.open(os.path.join(d, "assets", "tool_enriched.json"), encoding="utf-8") as f:
        enriched = json.load(f)
    app_tools = []
    p = os.path.join(d, "assets", "app_tools.json")
    if os.path.exists(p):
        with io.open(p, encoding="utf-8") as f:
            app_tools = json.load(f)
    agents = []
    for cfg in sorted(glob.glob(os.path.join(d, "assets", "agents", "*", "config.json"))):
        with io.open(cfg, encoding="utf-8") as f:
            agents.append(json.load(f))
    return enriched, app_tools, agents


def server_of(name):
    return name.split("__")[0] if "__" in name else "(顶层)"


def render(version, enriched, app_tools, agents):
    tools = enriched["tools"]
    out = io.StringIO()
    w = out.write

    w("# 工具目录（小爱 %s）\n\n" % version)
    w("> 由 `reverse/scripts/catalog.py` 从 APK assets 生成，勿手工编辑。\n")
    w("> 来源：`assets/tool_enriched.json`（%d 个工具）、`assets/app_tools.json`（%d 个进程内工具）。\n\n"
      % (len(tools), len(app_tools)))

    groups = collections.Counter(t.get("group") or "?" for t in tools)
    w("## 按分组统计\n\n| 分组 | 工具数 |\n|---|---|\n")
    for k, v in groups.most_common():
        w("| %s | %d |\n" % (k, v))

    servers = collections.Counter(server_of(t["name"]) for t in tools)
    w("\n## 按服务前缀统计（%d 个）\n\n| 前缀 | 工具数 |\n|---|---|\n" % len(servers))
    for k, v in servers.most_common():
        w("| %s | %d |\n" % (k, v))

    w("\n## 工具清单\n\n按服务前缀分组；描述取 `description` 首 90 字。\n\n")
    bysrv = collections.defaultdict(list)
    for t in tools:
        bysrv[server_of(t["name"])].append(t)
    for srv in sorted(bysrv, key=lambda s: (-len(bysrv[s]), s)):
        w("### %s（%d）\n\n| 工具 | 分组 | 说明 |\n|---|---|---|\n" % (srv, len(bysrv[srv])))
        for t in sorted(bysrv[srv], key=lambda x: x["name"]):
            desc = (t.get("description") or "").replace("\n", " ").replace("|", "\\|")
            if len(desc) > 90:
                desc = desc[:90] + "…"
            w("| `%s` | %s | %s |\n" % (t["name"], t.get("group") or "", desc))
        w("\n")

    w("## 进程内 App 工具（UIAgentServiceForOSBot）\n\n")
    w("`assets/app_tools.json` 声明的方法，直接在小爱进程内调用，见 `docs/08-internal-api.md`。\n\n")
    w("| 工具名 | 方法 | 参数 | 说明 |\n|---|---|---|---|\n")
    for t in app_tools:
        desc = ((t.get("descriptions") or {}).get("zh") or "").replace("\n", " ").replace("|", "\\|")
        if len(desc) > 90:
            desc = desc[:90] + "…"
        w("| `%s` | `%s` | %s | %s |\n" % (t.get("name"), t.get("method"), t.get("paramCount"), desc))

    w("\n## Agent 定义（assets/agents/）\n\n| id | 名称 | 模式 | 最大深度 | 最大轮次 |\n|---|---|---|---|---|\n")
    for a in agents:
        w("| `%s` | %s | %s | %s | %s |\n" % (
            a.get("id"), a.get("name"), a.get("execution_mode"),
            a.get("max_agent_depth"), a.get("max_iterations")))

    return out.getvalue()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="8.2.70.8421")
    ap.add_argument("--out")
    args = ap.parse_args()
    text = render(args.version, *load(args.version))
    if args.out:
        path = args.out if os.path.isabs(args.out) else os.path.join(RE_ROOT, args.out)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        io.open(path, "w", encoding="utf-8", newline="\n").write(text)
        print("写入 %s（%d 字符）" % (path, len(text)))
    else:
        print(text)


if __name__ == "__main__":
    main()
