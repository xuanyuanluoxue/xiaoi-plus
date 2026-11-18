#!/usr/bin/env bash
# 本机发起 MCP 调用：端口发现 → adb forward → Bearer 鉴权 → POST /mcp
#
# 用法：
#   scripts/mcp-call.sh health                 # GET /health（带 token，自证身份）
#   scripts/mcp-call.sh init                   # initialize + ping
#   scripts/mcp-call.sh tools                  # tools/list，只打印工具名
#   scripts/mcp-call.sh call xiaoai_status     # tools/call，args 默认 {}
#   scripts/mcp-call.sh call xiaoai_logs '{"limit":5}'
#   scripts/mcp-call.sh raw '{"jsonrpc":"2.0","id":1,"method":"ping"}'
#
# 环境变量：
#   ANDROID_SERIAL  多设备时指定（默认取第一个 device）
#   XIAOI_TOKEN     桥 token；不传则读 ~/.config/xiaoi/token
#   XIAOI_PORT      跳过端口发现，直接用指定端口
#
# 只读优先：默认不调用会花额度或改状态的工具（xiaoai_submit / xiaoai_switch_model 等），
# 需要时自己用 call 显式发起。
set -uo pipefail

MODE="${1:-health}"
shift || true

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

command -v adb  >/dev/null || { echo "错误：需要 adb" >&2; exit 1; }
command -v curl >/dev/null || { echo "错误：需要 curl" >&2; exit 1; }

if [ -z "${XIAOI_TOKEN:-}" ] && [ -f "$HOME/.config/xiaoi/token" ]; then
    XIAOI_TOKEN="$(tr -d ' \r\n' < "$HOME/.config/xiaoi/token")"
fi
TOKEN="${XIAOI_TOKEN:-}"

SERIAL="${ANDROID_SERIAL:-$(adb devices | awk '$2=="device"{print $1; exit}')}"
[ -n "$SERIAL" ] || { echo "错误：没有已连接的设备" >&2; exit 1; }
ADB=(adb -s "$SERIAL")

PORT="${XIAOI_PORT:-}"
FWD=""
cleanup() { [ -n "$FWD" ] && "${ADB[@]}" forward --remove "tcp:$FWD" >/dev/null 2>&1 || true; }
trap cleanup EXIT

if [ -z "$PORT" ]; then
    # 与 contract-test.sh 同一套身份判定：带 token 认 facade=，无 token 用 /stats 排除非本服务
    for i in $(seq 1 15); do
        for p in $("${ADB[@]}" shell ss -ltn 2>/dev/null | awk 'NR>1{print $4}' \
                    | grep -oE '877[0-9]$' | sort -u); do
            "${ADB[@]}" forward "tcp:$p" "tcp:$p" >/dev/null 2>&1 || continue
            ok=0
            if [ -n "$TOKEN" ]; then
                b=$(curl -s -m 2 -H "Authorization: Bearer $TOKEN" \
                        "http://127.0.0.1:$p/health" 2>/dev/null || true)
                echo "$b" | grep -qE '^ok facade=(true|false) port=[0-9]+' && ok=1
            else
                c=$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$p/health" 2>/dev/null || true)
                s=$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$p/stats" 2>/dev/null || true)
                [ "$c" = "401" ] && [ "$s" = "401" ] && ok=1
            fi
            if [ "$ok" = "1" ]; then PORT="$p"; FWD="$p"; break; fi
            "${ADB[@]}" forward --remove "tcp:$p" >/dev/null 2>&1 || true
        done
        [ -n "$PORT" ] && break
        sleep 1
    done
fi
[ -n "$PORT" ] || { echo "错误：8771~8779 未发现可识别的能力桥" >&2; exit 1; }
BASE="http://127.0.0.1:$PORT"

post() {
    local body="$1" out status
    out="$(mktemp)"
    if [ -n "$TOKEN" ]; then
        status=$(curl -s -m 30 -o "$out" -w '%{http_code}' -X POST "$BASE/mcp" \
            -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
            --data "$body")
    else
        status=$(curl -s -m 30 -o "$out" -w '%{http_code}' -X POST "$BASE/mcp" \
            -H 'Content-Type: application/json' --data "$body")
    fi
    echo "HTTP $status"
    if command -v jq >/dev/null; then jq . "$out" 2>/dev/null || cat "$out"; else cat "$out"; fi
    echo
    rm -f "$out"
}

echo "==> 设备 $SERIAL · 端口 $PORT · token $([ -n "$TOKEN" ] && echo 已设置 || echo 未设置)"

case "$MODE" in
    health)
        if [ -n "$TOKEN" ]; then
            curl -s -m 3 -H "Authorization: Bearer $TOKEN" "$BASE/health"; echo
        else
            curl -s -m 3 -o /dev/null -w 'HTTP %{http_code}\n' "$BASE/health"
        fi
        ;;
    init)
        post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"mcp-call","version":"0"}}}'
        post '{"jsonrpc":"2.0","id":2,"method":"ping"}'
        ;;
    tools)
        out="$(mktemp)"
        if [ -n "$TOKEN" ]; then
            curl -s -m 5 -o "$out" -X POST "$BASE/mcp" \
                -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
                --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
        else
            echo "错误：tools/list 需要 token" >&2; rm -f "$out"; exit 1
        fi
        if command -v jq >/dev/null; then
            jq -r '.result.tools[] | "\(.name)\t\(.description[0:48])"' "$out"
        else
            cat "$out"
        fi
        rm -f "$out"
        ;;
    call)
        NAME="${1:-}"; ARGS="${2-}"
        [ -n "$NAME" ] || { echo "用法：mcp-call.sh call <tool> [json-args]" >&2; exit 2; }
        [ -n "$ARGS" ] || ARGS='{}'
        [ -n "$TOKEN" ] || { echo "错误：tools/call 需要 token" >&2; exit 1; }
        post "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$NAME\",\"arguments\":$ARGS}}"
        ;;
    raw)
        BODY="${1:-}"
        [ -n "$BODY" ] || { echo "用法：mcp-call.sh raw <json>" >&2; exit 2; }
        post "$BODY"
        ;;
    *)
        echo "未知子命令：$MODE（health|init|tools|call|raw）" >&2
        exit 2
        ;;
esac
