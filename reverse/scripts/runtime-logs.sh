#!/usr/bin/env bash
# 从 8771 取运行时证据（合并日志、提示词统计、调用量），不碰设备文件。
set -uo pipefail
source "$(dirname "$0")/env.sh"

GREP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --grep) GREP="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

TOKEN_FILE="$RE_ROOT/.secrets/xiaoi-token"
TOKEN=""
[ -s "$TOKEN_FILE" ] && TOKEN="$(tr -d ' \r\n' < "$TOKEN_FILE")"

adb -s "$DEVICE" forward tcp:8771 tcp:8771 >/dev/null 2>&1
BASE="http://127.0.0.1:8771"
H=(-H "Content-Type: application/json")
[ -n "$TOKEN" ] && H+=(-H "Authorization: Bearer $TOKEN")

echo "=== /health ==="
curl -s --noproxy '*' "${H[@]}" "$BASE/health"; echo

call() {
  curl -s --noproxy '*' -X POST "$BASE/mcp" "${H[@]}" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}"
}

if [ -n "$GREP" ]; then
  echo
  echo "=== 合并日志（grep: $GREP）==="
  call xiaoai_logs '{"limit":300}' | python3 -c "
import json,sys
o=json.loads(json.load(sys.stdin)['result']['content'][0]['text'])
pat=sys.argv[1]
for l in o['lines']:
    if pat in l['msg']:
        print('%s %s %s' % (l['pid'], l['proc'].split(':')[-1], l['msg'][:240]))
" "$GREP"
else
  echo
  echo "=== 提示词/记忆状态 ==="
  call xiaoai_prompt '{}' | python3 -c "import json,sys;print(json.load(sys.stdin)['result']['content'][0]['text'][:400])"
  echo
  echo "=== 调用量（今日）==="
  call xiaoai_stats '{"days":1}' | python3 -c "import json,sys;d=json.loads(json.load(sys.stdin)['result']['content'][0]['text']);print(d['today'])"
fi
