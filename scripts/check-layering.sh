#!/usr/bin/env bash
# 分层边界门禁（WP0）
#
# 目标：把「domain/application 不依赖 Android、UI 不直连 ConfigRepository」
# 变成可执行检查，并且**只拦新增违规**，不阻塞现有开发。
#
# 用法：
#   scripts/check-layering.sh            # 检查（新增/超基线失败，stale 只告警）
#   scripts/check-layering.sh --strict   # stale 也失败（WP 收尾/CI 用）
#   scripts/check-layering.sh --init     # 重新生成基线（人工执行；CI 拒绝）
#   scripts/check-layering.sh --self-test
#
# 基线格式（TSV，允许 # 注释）：
#   rule<TAB>file<TAB>token<TAB>count
#
# 说明：
# - 只看源码 import / 调用，不做字节码分析；真正的硬边界由后续 Gradle module 提供。
# - token 用完整 import 目标，新增一个 import 即使总数不变也能被发现。
# - 基线只减不增；修复后执行 --init 去除对应行，或在 WP commit 里手工删行。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE="$ROOT/scripts/layering-baseline.tsv"
# 临时文件一律落在项目内 tmp/
mkdir -p "$ROOT/tmp"

MODE="check"
STRICT=0

usage() {
    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
    case "$arg" in
        --init) MODE="init" ;;
        --strict) STRICT=1 ;;
        --self-test) MODE="self-test" ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数: $arg" >&2; usage; exit 2 ;;
    esac
done

# rule id | 相对目录 | 类型(import|literal) | grep 正则
RULES=(
    'ui_no_repository_import|ui/src/main/java/plus/xiaoi/module/ui|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.ConfigRepository'
    'ui_no_repository_get|ui/src/main/java/plus/xiaoi/module/ui|literal|ConfigRepository\.get\('
    'ui_no_control|ui/src/main/java/plus/xiaoi/module/ui|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.control\.'
    'ui_no_agent_service|ui/src/main/java/plus/xiaoi/module/ui|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.agent\.AgentService'
    'ui_no_permission_probe|ui/src/main/java/plus/xiaoi/module/ui|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.core\.PermissionProbe'
    'ui_no_status_cache|ui/src/main/java/plus/xiaoi/module/ui|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.StatusCache'
    'ui_no_config_sync|ui/src/main/java/plus/xiaoi/module/ui|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.ConfigSync'
    'ui_no_bridge_pkg|ui/src/main/java/plus/xiaoi/module/ui|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.bridge\.'
    'ui_no_infra|ui/src/main/java/plus/xiaoi/module/ui|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.(Http|BridgeClient|ModelValidator|Lan|ConfigStore)$'
    'domain_no_android|domain/src/main/java|import|^[[:space:]]*import[[:space:]]+android\.'
    'domain_no_xposed|domain/src/main/java|import|^[[:space:]]*import[[:space:]]+de\.robv\.'
    'domain_no_orgjson|domain/src/main/java|import|^[[:space:]]*import[[:space:]]+org\.json\.'
    'domain_no_io|domain/src/main/java|import|^[[:space:]]*import[[:space:]]+java\.(io|net|nio)\.'
    'domain_no_concurrent|domain/src/main/java|import|^[[:space:]]*import[[:space:]]+java\.util\.concurrent\.'
    'core_no_agent|domain/src/main/java/plus/xiaoi/module/core|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.agent\.'
    'agent_no_core|domain/src/main/java/plus/xiaoi/module/agent|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.core\.'
    'application_no_android|application/src/main/java|import|^[[:space:]]*import[[:space:]]+android\.'
    'application_no_xposed|application/src/main/java|import|^[[:space:]]*import[[:space:]]+de\.robv\.'
    'application_no_orgjson|application/src/main/java|import|^[[:space:]]*import[[:space:]]+org\.json\.'
    'application_no_io|application/src/main/java|import|^[[:space:]]*import[[:space:]]+java\.(io|net|nio)\.'
    'infra_no_android|infra/src/main/java|import|^[[:space:]]*import[[:space:]]+android\.'
    'infra_no_xposed|infra/src/main/java|import|^[[:space:]]*import[[:space:]]+de\.robv\.'
    'platform_no_compose|platform/src/main/java|import|^[[:space:]]*import[[:space:]]+androidx\.compose\.'
    'platform_no_miuix|platform/src/main/java|import|^[[:space:]]*import[[:space:]]+top\.yukonga\.miuix\.'
    'platform_no_ui|platform/src/main/java|import|^[[:space:]]*import[[:space:]]+plus\.xiaoi\.module\.ui\.'
)

# 输出所有违规行：rule<TAB>file<TAB>token<TAB>count
scan_rows() {
    local root="$1"
    local entry id dir kind pattern path file token count target
    for entry in "${RULES[@]}"; do
        IFS='|' read -r id dir kind pattern <<< "$entry"
        path="$root/$dir"
        [ -d "$path" ] || continue

        while IFS= read -r file; do
            [ -n "$file" ] || continue
            if [ "$kind" = "import" ]; then
                while IFS= read -r target; do
                    [ -n "$target" ] || continue
                    count="$(grep -cE "^[[:space:]]*import[[:space:]]+${target//./\\.}([[:space:]]|;|$)" "$file" 2>/dev/null || true)"
                    [ "${count:-0}" -gt 0 ] || continue
                    printf '%s\t%s\t%s\t%s\n' "$id" "${file#"$root"/}" "$target" "$count"
                done < <(grep -hE "$pattern" "$file" 2>/dev/null \
                    | sed -E 's/^[[:space:]]*import[[:space:]]+([^;[:space:]]+).*/\1/' \
                    | sort -u)
            else
                count="$(grep -oE "$pattern" "$file" 2>/dev/null | wc -l | tr -d ' ')"
                [ "${count:-0}" -gt 0 ] || continue
                printf '%s\t%s\t%s\t%s\n' "$id" "${file#"$root"/}" "ConfigRepository.get(" "$count"
            fi
        done < <(grep -rlE "$pattern" "$path" --include='*.java' --include='*.kt' 2>/dev/null || true)
    done | sort
}

print_hits() {
    local root="$1" rule="$2" rel="$3" token="$4"
    local entry id dir kind pattern path
    for entry in "${RULES[@]}"; do
        IFS='|' read -r id dir kind pattern <<< "$entry"
        [ "$id" = "$rule" ] || continue
        path="$root/$rel"
        [ -f "$path" ] || continue
        if [ "$kind" = "import" ]; then
            grep -nE "^[[:space:]]*import[[:space:]]+${token//./\\.}" "$path" 2>/dev/null \
                | sed "s|^|      $rel:|"
        else
            grep -nE "$pattern" "$path" 2>/dev/null | sed "s|^|      $rel:|"
        fi
        return
    done
}

run_check() {
    local root="$1" baseline="$2" strict="$3"
    local tmp cur base_file
    tmp="$(mktemp -d "$ROOT/tmp/layering.XXXXXX")"
    cur="$tmp/current.tsv"
    base_file="$tmp/baseline.tsv"
    scan_rows "$root" > "$cur"
    if [ -f "$baseline" ]; then
        grep -vE '^[[:space:]]*(#|$)' "$baseline" > "$base_file" || true
    else
        : > "$base_file"
    fi

    declare -A BASE CUR
    local r f t n
    while IFS=$'\t' read -r r f t n; do
        [ -n "${r:-}" ] || continue
        BASE["$r|$f|$t"]="${n:-0}"
    done < "$base_file"
    while IFS=$'\t' read -r r f t n; do
        [ -n "${r:-}" ] || continue
        CUR["$r|$f|$t"]="${n:-0}"
    done < "$cur"

    local new=0 stale=0 key count base
    for key in "${!CUR[@]}"; do
        count="${CUR[$key]}"
        base="${BASE[$key]:-0}"
        if [ "$count" -gt "$base" ]; then
            IFS='|' read -r r f t <<< "$key"
            echo "  ✗ 新增分层违规: $r $f ($t) 当前 $count > 基线 $base"
            print_hits "$root" "$r" "$f" "$t"
            new=$((new + 1))
        fi
    done
    for key in "${!BASE[@]}"; do
        count="${CUR[$key]:-0}"
        base="${BASE[$key]}"
        if [ "$count" -lt "$base" ]; then
            IFS='|' read -r r f t <<< "$key"
            echo "  ! 基线已过期: $r $f ($t) 当前 $count < 基线 $base；请删掉基线行"
            stale=$((stale + 1))
        fi
    done

    rm -rf "$tmp"

    if [ "$new" -gt 0 ]; then
        echo "==> 分层检查失败：$new 项新增违规"
        return 1
    fi
    if [ "$stale" -gt 0 ]; then
        if [ "$strict" -eq 1 ]; then
            echo "==> 分层检查失败：$stale 项基线过期（--strict）"
            return 1
        fi
        echo "  ! 有 $stale 项基线过期，建议在本 WP 内删除对应行"
    fi
    echo "==> 分层检查通过"
    return 0
}

# ---------------------------------------------------------------- 自测
if [ "$MODE" = "self-test" ]; then
    tmp="$(mktemp -d "$ROOT/tmp/layering.XXXXXX")"
    mkdir -p "$tmp/app/src/main/java/plus/xiaoi/module/ui"
    cat > "$tmp/app/src/main/java/plus/xiaoi/module/ui/Bad.kt" <<'EOF'
package plus.xiaoi.module.ui
import plus.xiaoi.module.ConfigRepository
fun bad() { ConfigRepository.get(null) }
EOF
    : > "$tmp/baseline.tsv"
    echo "==> check-layering 自测：期望发现 2 项违规"
    if run_check "$tmp" "$tmp/baseline.tsv" 1; then
        echo "自测失败：没有拦住新增违规" >&2
        rm -rf "$tmp"
        exit 1
    fi
    # 把当前扫描结果写成基线后应通过（且 stale 不存在）
    scan_rows "$tmp" > "$tmp/baseline.tsv"
    if run_check "$tmp" "$tmp/baseline.tsv" 1; then
        echo "  ✓ 自测通过：基线化后不再报新增"
        rm -rf "$tmp"
        exit 0
    fi
    echo "自测失败：基线化后仍报错" >&2
    rm -rf "$tmp"
    exit 1
fi

# ---------------------------------------------------------------- 主流程
if [ "$MODE" = "init" ]; then
    if [ -n "${CI:-}" ]; then
        echo "CI 环境禁止 --init；基线只能人工收缩" >&2
        exit 2
    fi
    {
        echo "# rule<TAB>file<TAB>token<TAB>count"
        echo "# 由 scripts/check-layering.sh --init 生成；只减不增。"
        scan_rows "$ROOT"
    } > "$BASELINE"
    echo "==> 已写入基线：$BASELINE（$(grep -vcE '^[[:space:]]*(#|$)' "$BASELINE") 行）"
    exit 0
fi

echo "==> 分层边界检查"
run_check "$ROOT" "$BASELINE" "$STRICT"