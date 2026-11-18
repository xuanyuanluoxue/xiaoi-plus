#!/usr/bin/env bash
# 真机契约测试：把 docs/compatibility.md 里的人工契约步骤自动化成一条命令。
#
# 用法：
#   XIAOI_TOKEN=<桥token> scripts/contract-test.sh --save-baseline   # 拆分/重构前，对当前已装版本存基线
#   XIAOI_TOKEN=<桥token> scripts/contract-test.sh --check           # 装新包后，对比基线
#   scripts/contract-test.sh                                        # 无 token：只跑日志断言 + 免鉴权请求
#
# 环境变量：
#   XIAOI_TOKEN    桥的 Bearer token（设置页「能力桥 Token」）；不传则回退读取
#                  ~/.config/xiaoi/token（自建 runner / 日常使用免 export）
#   ANDROID_SERIAL 多设备时指定串号
#
# 副作用：强制重启小爱（force-stop + 重新拉起）、adb forward 端口、POST /island 会弹一次超级岛。
# 基线文件在 tests/baseline/，对比前会归一化 version / port / facade / cli，避免环境噪声。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE_DIR="$ROOT/tests/baseline"
MODE="run"
case "${1:-}" in
    --save-baseline) MODE="save" ;;
    --check)         MODE="check" ;;
    "")              ;;
    *) echo "用法：$0 [--save-baseline|--check]" >&2; exit 2 ;;
esac

command -v adb  >/dev/null || { echo "错误：需要 adb" >&2; exit 1; }
command -v curl >/dev/null || { echo "错误：需要 curl" >&2; exit 1; }
command -v jq   >/dev/null || { echo "错误：需要 jq" >&2; exit 1; }

# token 来源：环境变量优先，其次本机 token 文件（不进仓库）
if [ -z "${XIAOI_TOKEN:-}" ] && [ -f "$HOME/.config/xiaoi/token" ]; then
    XIAOI_TOKEN="$(tr -d ' \r\n' < "$HOME/.config/xiaoi/token")"
fi

TMP="$(mktemp -d)"
FWD_PORT=""
cleanup() {
    [ -n "$FWD_PORT" ] && $ADB forward --remove "tcp:$FWD_PORT" >/dev/null 2>&1 || true
    rm -rf "$TMP"
}
trap cleanup EXIT

SERIAL="${ANDROID_SERIAL:-$(adb devices | awk '$2=="device"{print $1; exit}')}"
[ -n "$SERIAL" ] || { echo "错误：没有已连接的设备" >&2; exit 1; }
ADB="adb -s $SERIAL"
PKG="com.miui.voiceassist"

PASS=0; FAIL=0
pass() { PASS=$((PASS+1)); echo "  ✓ $1"; }
fail() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }
note() { echo "  · $1"; }

# 归一化：抹平版本号/端口/就绪位等环境噪声；同时抹掉 baseUrl/model/text 等
# 用户特定值（基线文件会进仓库，不应包含真实端点等隐私；字段结构仍由 jq 断言覆盖）
normalize() {
    # MCP 工具的文本字段是「转义后嵌在 JSON 字符串里」的（标点形如 \"port\"），
    # 因此每个字段都要同时处理未转义与转义两种形态，否则版本噪声与隐私值会漏网。
    sed -e 's/"version":"[^"]*"/"version":"V"/g' \
        -e 's/\\"version\\":\\"[^"\\]*\\"/\\"version\\":\\"V\\"/g' \
        -e 's/"lan":\(true\|false\)/"lan":L/g' \
        -e 's/\\"lan\\":\(true\|false\)/\\"lan\\":L/g' \
        -e 's/"noAuth":\(true\|false\)/"noAuth":B/g' \
        -e 's/\\"noAuth\\":\(true\|false\)/\\"noAuth\\":B/g' \
        -e 's/"weakToken":\(true\|false\)/"weakToken":B/g' \
        -e 's/\\"weakToken\\":\(true\|false\)/\\"weakToken\\":B/g' \
        -e 's/"port":[0-9][0-9]*/"port":P/g' \
        -e 's/\\"port\\":[0-9][0-9]*/\\"port\\":P/g' \
        -e 's/"baseUrl":"[^"]*"/"baseUrl":"B"/g' \
        -e 's/\\"baseUrl\\":\\"[^"\\]*\\"/\\"baseUrl\\":\\"B\\"/g' \
        -e 's/"model":"[^"]*"/"model":"M"/g' \
        -e 's/\\"model\\":\\"[^"\\]*\\"/\\"model\\":\\"M\\"/g' \
        -e 's/"markerTemplate":"[^"]*"/"markerTemplate":"T"/g' \
        -e 's/\\"markerTemplate\\":\\"[^"\\]*\\"/\\"markerTemplate\\":\\"T\\"/g' \
        -e 's/version=[0-9][0-9.]*/version=V/g' \
        -e 's/port=[0-9][0-9]*/port=P/g' \
        -e 's/facade=\(true\|false\)/facade=B/g' \
        -e 's/ cli=\(true\|false\)/ cli=B/g' \
        -e 's/auth=\(off\|weak\|token\)/auth=A/g' \
        -e 's/\\"authMode\\":\\"[a-z]*\\"/\\"authMode\\":\\"A\\"/g' \
        -e 's/"authMode":"[a-z]*"/"authMode":"A"/g' \
        -e 's/agent=[A-Za-z0-9]*/agent=A/g' \
        -e 's/ tools=[0-9][0-9]*/ tools=N/g'
}

# 基线存/比：$TMP/body 与 $STATUS 由调用方先填好
save_or_check() {
    local key="$1" fatal="${2:-true}"
    if [ "$MODE" = "save" ]; then
        mkdir -p "$BASELINE_DIR"
        # 存「归一化后」的正文：基线会进仓库，不能带真实 baseUrl/model/token 状态。
        normalize < "$TMP/body" > "$BASELINE_DIR/$key.body"
        printf '%s\n' "$STATUS" > "$BASELINE_DIR/$key.status"
        note "基线已存：$key"
    elif [ "$MODE" = "check" ]; then
        if [ ! -f "$BASELINE_DIR/$key.body" ]; then
            note "$key：无基线，跳过对比（先跑 --save-baseline 可建立）"
            return
        fi
        local want_status; want_status="$(cat "$BASELINE_DIR/$key.status")"
        normalize < "$TMP/body" > "$TMP/got.n"
        normalize < "$BASELINE_DIR/$key.body" > "$TMP/want.n"
        if [ "$STATUS" = "$want_status" ] && cmp -s "$TMP/got.n" "$TMP/want.n"; then
            pass "$key：与基线一致"
        else
            fail "$key：与基线不一致（status $STATUS vs $want_status）"
            diff <(cat "$TMP/want.n") <(cat "$TMP/got.n") | sed 's/^/      /' || true
        fi
    fi
}

# GET 请求，结果写 $STATUS / $TMP/body；额外 curl 参数透传
http_get() {
    local path="$1"; shift
    STATUS=$(curl -s -o "$TMP/body" -w '%{http_code}' "$BASE$path" "$@")
}
http_post() {
    local path="$1" data="$2"; shift 2
    STATUS=$(curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE$path" \
        -H 'Content-Type: application/json' --data "$data" "$@")
}

echo "==> 设备：$SERIAL（模式：$MODE，token：$([ -n "${XIAOI_TOKEN:-}" ] && echo 已设置 || echo 未设置)）"

# ---------------------------------------------------------------- 1) 重启小爱 + 日志断言
echo "==> 重启小爱，采集模块日志"
$ADB shell am force-stop "$PKG"
$ADB logcat -c
$ADB shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

LOG=""
for i in $(seq 1 30); do
    LOG="$($ADB logcat -d 2>/dev/null | grep 'xiaoi+' || true)"
    if echo "$LOG" | grep -q '已启用 model=' || echo "$LOG" | grep -q '配置为空'; then
        break
    fi
    sleep 1
done

if echo "$LOG" | grep -q '已启用 model=' || echo "$LOG" | grep -q '配置为空'; then
    pass "模块已加载并完成配置判定"
else
    fail "30 秒内未见「已启用 model=」或「配置为空」日志"
fi
echo "$LOG" | grep -q 'provider 标识 hook 完成' \
    && pass "provider 标识 hook 完成" || fail "未见「provider 标识 hook 完成」"
echo "$LOG" | grep -qE '能力桥已监听 (127\.0\.0\.1|0\.0\.0\.0):' \
    && pass "能力桥已监听" || fail "未见「能力桥已监听」"

# ---------------------------------------------------------------- 2) 端口发现 + forward
# 8770~8779 可能被其它应用占用（实测 8770 会返回非本服务的响应），
# 因此不能只按 ss 取第一个：必须逐个 forward 后用 /health 自证身份。
PORT=""
for i in $(seq 1 15); do
    CANDIDATES="$($ADB shell ss -ltn 2>/dev/null | awk 'NR>1{print $4}' | grep -oE '877[0-9]$' | sort -u | tr '\n' ' ' || true)"
    for p in $CANDIDATES; do
        $ADB forward "tcp:$p" "tcp:$p" >/dev/null 2>&1 || continue
        if [ -n "${XIAOI_TOKEN:-}" ]; then
            # 带 token：直接看身份。桥回 "ok facade=<b> port=N"；
            # 其它服务（如小爱自带的 8770）不是这个前缀，必须排除。
            BODY=$(curl -s -m 2 -H "Authorization: Bearer $XIAOI_TOKEN" \
                "http://127.0.0.1:$p/health" 2>/dev/null || true)
            if echo "$BODY" | grep -qE '^ok facade=(true|false) port=[0-9]+'; then
                PORT="$p"; break
            fi
        else
            # 无 token：401 体可能都一样（unauthorized），再用 /stats 区分——
            # 桥对 /stats 回 401（路径存在），其它服务多为 404/200。
            CODE=$(curl -s -m 2 -o /dev/null -w '%{http_code}' \
                "http://127.0.0.1:$p/health" 2>/dev/null || true)
            BODY2=$(curl -s -m 2 "http://127.0.0.1:$p/health" 2>/dev/null || true)
            STATS=$(curl -s -m 2 -o /dev/null -w '%{http_code}' \
                "http://127.0.0.1:$p/stats" 2>/dev/null || true)
            if [ "$CODE" = "401" ] && [ "$BODY2" = "unauthorized" ] && [ "$STATS" = "401" ]; then
                PORT="$p"; break
            fi
        fi
        $ADB forward --remove "tcp:$p" >/dev/null 2>&1 || true
    done
    [ -n "$PORT" ] && break
    sleep 1
done
if [ -n "$PORT" ]; then
    pass "桥端口：$PORT（已用 /health 自证身份）"
else
    fail "8771~8779 未发现可识别的桥（其它应用可能占用了端口）"
fi

if [ -n "$PORT" ]; then
    $ADB forward "tcp:$PORT" "tcp:$PORT" >/dev/null
    FWD_PORT="$PORT"
    BASE="http://127.0.0.1:$PORT"

    if [ -n "${XIAOI_TOKEN:-}" ]; then
        A_HDR=(-H "Authorization: Bearer $XIAOI_TOKEN")
    else
        A_HDR=()
    fi

    # ------------------------------------------------------------ 3) 免鉴权行为
    # 「免鉴权」开关打开时，无 token 不再是 401；本组按当前配置调整，不算失败。
    NO_AUTH=""
    if [ -n "${XIAOI_TOKEN:-}" ]; then
        CFG_JSON=$(curl -s -m 3 -X POST "$BASE/mcp" -H "Authorization: Bearer $XIAOI_TOKEN" \
            -H 'Content-Type: application/json' \
            --data '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"xiaoai_config","arguments":{}}}' 2>/dev/null || true)
        NO_AUTH=$(printf '%s' "$CFG_JSON" | jq -r '.result.content[0].text | fromjson | .noAuth' 2>/dev/null || true)
    fi
    echo "==> 免鉴权请求（不依赖 token）$([ "$NO_AUTH" = "true" ] && echo '· 已开免鉴权，跳过 401 断言')"
    if [ "$NO_AUTH" = "true" ]; then
        note "当前配置 noAuth=true：无 token 的请求是设计允许的"
    else
        http_get /health
        [ "$STATUS" = "401" ] && pass "GET /health 无 token → 401" || fail "GET /health 无 token → 期望 401，实际 $STATUS"
        save_or_check health_no_token
    fi

    http_get /mcp
    [ "$STATUS" = "405" ] && pass "GET /mcp → 405" || fail "GET /mcp → 期望 405，实际 $STATUS"
    save_or_check mcp_get_405

    if [ "$NO_AUTH" != "true" ]; then
        http_post /mcp '{"jsonrpc":"2.0","id":1,"method":"ping"}'
        [ "$STATUS" = "401" ] && pass "POST /mcp 无 token → 401" || fail "POST /mcp 无 token → 期望 401，实际 $STATUS"

        # /stats 与其他 GET 端点同一条鉴权线
        http_get "/stats?days=1"
        [ "$STATUS" = "401" ] && pass "GET /stats 无 token → 401" || fail "GET /stats 无 token → 期望 401，实际 $STATUS"
    fi

    # WP4：/config 固定 410，即使没有 token 也不解析 body / 不写 prefs
    http_post /config 'not json at all'
    [ "$STATUS" = "410" ] && pass "POST /config 无 token → 410 Gone" \
        || fail "POST /config 无 token → 期望 410，实际 $STATUS"

    # ------------------------------------------------------------ 4) token 门内
    if [ -n "${XIAOI_TOKEN:-}" ]; then
        echo "==> 鉴权请求（token 已提供）"

        # 等 facade 就绪（重启后服务绑定是异步的；cli 就绪由 xiaoai_status 体现，/health 不含该字段）
        for i in $(seq 1 30); do
            http_get /health "${A_HDR[@]}"
            grep -q 'facade=true' "$TMP/body" && break
            sleep 1
        done
        if grep -q 'facade=true' "$TMP/body"; then
            pass "facade 已就绪"
        else
            fail "facade 未在 30 秒内就绪：$(cat "$TMP/body")"
        fi
        save_or_check health

        http_post /mcp '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"contract","version":"0"}}}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '.result.protocolVersion=="2025-03-26" and .result.serverInfo.name=="xiaoi-plus"' "$TMP/body" >/dev/null \
            && pass "MCP initialize" || fail "MCP initialize 异常：$(cat "$TMP/body")"
        save_or_check mcp_initialize

        http_post /mcp '{"jsonrpc":"2.0","id":2,"method":"ping"}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '.result=={}' "$TMP/body" >/dev/null \
            && pass "MCP ping" || fail "MCP ping 异常：$(cat "$TMP/body")"
        save_or_check mcp_ping

        # own=true：只回 8771 自己的工具，基线因此与「对端是否在线」无关。
        http_post /mcp '{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{"own":true}}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '(.result.tools|length)==9' "$TMP/body" >/dev/null \
            && pass "tools/list(own) 共 9 个桥工具" || fail "tools/list(own) 工具数异常：$(cat "$TMP/body")"
        jq -e '[.result.tools[].name] | sort == (["xiaoai_cli","xiaoai_config","xiaoai_island","xiaoai_logs","xiaoai_stats","xiaoai_status","xiaoai_submit","xiaoai_switch_model","xiaoai_todo_create"] | sort)' "$TMP/body" >/dev/null \
            && pass "工具名单齐全" || fail "工具名单不齐：$(jq -r '.result.tools[].name' "$TMP/body" | tr '\n' ' ')"
        save_or_check mcp_tools_list

        # 0.16.0 单端口统一：合并视图 9（对端离线）~ 15（两端齐全）
        http_post /mcp '{"jsonrpc":"2.0","id":31,"method":"tools/list"}' "${A_HDR[@]}"
        MERGED=$(jq -r '.result.tools|length' "$TMP/body" 2>/dev/null || echo 0)
        if [ "$STATUS" = "200" ] && [ "$MERGED" -ge 9 ] && [ "$MERGED" -le 15 ]; then
            pass "合并工具数 $MERGED（9=App 未运行，15=两端齐全）"
            jq -e '[.result.tools[].name] | contains(["xiaoai_status","xiaoai_config","xiaoai_logs"])' "$TMP/body" >/dev/null \
                && pass "合并视图含桥工具" || fail "合并视图缺少桥工具"
        else
            fail "合并工具数异常：$MERGED"
        fi

        http_post /mcp '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"xiaoai_status","arguments":{}}}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '.result.isError==false and (.result.content[0].text|test("facade=.* port="))' "$TMP/body" >/dev/null \
            && pass "xiaoai_status" || fail "xiaoai_status 异常：$(cat "$TMP/body")"
        save_or_check mcp_status

        http_post /mcp '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"xiaoai_config","arguments":{}}}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '(.result.content[0].text|fromjson) | has("model") and has("baseUrl") and has("marker") and has("bridge") and has("apiKeySet") and has("tokenSet")' "$TMP/body" >/dev/null \
            && pass "xiaoai_config 字段齐全" || fail "xiaoai_config 异常：$(cat "$TMP/body")"
        save_or_check mcp_config

        http_post /mcp '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"xiaoai_logs","arguments":{"limit":5}}}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '(.result.content[0].text|fromjson) | has("count") and has("lastSeq") and has("lines")' "$TMP/body" >/dev/null \
            && pass "xiaoai_logs 形状正确" || fail "xiaoai_logs 异常：$(cat "$TMP/body")"

        http_post /mcp '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '.result.isError==true and (.result.content[0].text|startswith("unknown tool:"))' "$TMP/body" >/dev/null \
            && pass "未知工具 → isError" || fail "未知工具处理异常：$(cat "$TMP/body")"
        save_or_check mcp_unknown_tool

        http_post /mcp '{"jsonrpc":"2.0","id":8,"method":"no/such/method"}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '.error.code==-32601' "$TMP/body" >/dev/null \
            && pass "未知方法 → -32601" || fail "未知方法处理异常：$(cat "$TMP/body")"
        save_or_check mcp_unknown_method

        http_post /mcp 'this is not json' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '.error.code==-32700' "$TMP/body" >/dev/null \
            && pass "坏 JSON → -32700" || fail "坏 JSON 处理异常：$(cat "$TMP/body")"
        save_or_check mcp_parse_error

        http_post /mcp '{"jsonrpc":"2.0","id":9}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '.error.code==-32600' "$TMP/body" >/dev/null \
            && pass "缺 method → -32600" || fail "缺 method 处理异常：$(cat "$TMP/body")"
        save_or_check mcp_missing_method

        # 通知（无 id）：JSON-RPC 允许 202 空体
        http_post /mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' "${A_HDR[@]}"
        [ "$STATUS" = "202" ] && [ ! -s "$TMP/body" ] \
            && pass "通知 → 202 空体" || fail "通知处理异常：status=$STATUS body=$(cat "$TMP/body")"
        save_or_check mcp_notification

        http_get "/logs?limit=3" "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e 'has("count") and has("lastSeq") and has("lines")' "$TMP/body" >/dev/null \
            && pass "GET /logs 形状正确" || fail "GET /logs 异常：$(cat "$TMP/body")"

        # 调用量统计：数值随真实使用变化，只断言结构（不进基线）
        http_get "/stats?days=1" "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e 'has("days") and has("today") and has("total") and has("sub")
            and has("series") and has("models")
            and (.today|has("third") and has("official"))
            and (.total|has("third") and has("official"))' "$TMP/body" >/dev/null \
            && pass "GET /stats 形状正确" || fail "GET /stats 异常：$(cat "$TMP/body")"

        http_post /mcp '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"xiaoai_stats","arguments":{"days":7}}}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e '.result.isError==false and (.result.content[0].text|fromjson|has("today"))' "$TMP/body" >/dev/null \
            && pass "xiaoai_stats 可调用" || fail "xiaoai_stats 异常：$(cat "$TMP/body")"

        http_get /cli "${A_HDR[@]}"
        [ "$STATUS" = "400" ] && pass "GET /cli 缺 cmd → 400" || fail "GET /cli 缺 cmd → 期望 400，实际 $STATUS"
        save_or_check cli_no_cmd

        http_get /no-such-path "${A_HDR[@]}"
        [ "$STATUS" = "404" ] && pass "未知路径 → 404" || fail "未知路径 → 期望 404，实际 $STATUS"
        save_or_check unknown_path

        # WP4 破坏性变更：旧 App/脚本的 POST /config 一律 410 Gone；
        # body 不进入解析、不写 prefs。GET /config 仍是 405。
        # 注：WP4 基线未在真机 --save-baseline 重跑，config_auth_probe.* 按新协议手工更新。
        http_post /config '{"model":"contract-should-not-apply"}' "${A_HDR[@]}"
        [ "$STATUS" = "410" ] && pass "POST /config → 410 Gone（不再接收配置）" \
            || fail "POST /config → 期望 410，实际 $STATUS"
        save_or_check config_auth_probe

        http_get /config "${A_HDR[@]}"
        [ "$STATUS" = "405" ] && pass "GET /config → 405" \
            || fail "GET /config → 期望 405，实际 $STATUS"

        http_post /island '{}' "${A_HDR[@]}"
        [ "$STATUS" = "200" ] && jq -e 'has("ok") and has("text")' "$TMP/body" >/dev/null \
            && pass "POST /island 形状正确" || fail "POST /island 异常：$(cat "$TMP/body")"
        # 不存基线：/island 的 text 是当前模型名，属于用户隐私且会随切换变化。
    else
        note "未提供 XIAOI_TOKEN，跳过鉴权类检查（基线只覆盖免鉴权部分）"
    fi
fi

# ---------------------------------------------------------------- 5) 汇总
echo
echo "==> 结果：$PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ] || exit 1
