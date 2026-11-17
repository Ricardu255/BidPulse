#!/usr/bin/env bash
# =====================================================================
# 轻量密钥扫描（CI release gate 的一部分）
# =====================================================================
# 只拦截「高置信度」模式，避免把文档里的示例值当成泄露。
# 扫描范围是 git 跟踪的文件；未跟踪文件请先 git add 再跑。
#
# 假阳性豁免：在该行任意位置写注释 `gitleaks:allow`，本脚本会跳过这一行。
# 例如：  EXAMPLE_TOKEN=sk-xxxxxxxx...   # gitleaks:allow 文档示例
#
# 用法（项目根目录）：
#   tools/check-secrets.sh
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v git >/dev/null 2>&1; then
  echo "需要 git" >&2
  exit 1
fi

patterns=(
  # AWS 访问密钥
  'AKIA[0-9A-Z]{16}'
  'ASIA[0-9A-Z]{16}'
  # 私钥文件内容
  '-----BEGIN (RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----'
  # GitHub token
  'gh[pousr]_[A-Za-z0-9]{36,}'
  'github_pat_[A-Za-z0-9_]{40,}'
  # Slack token
  'xox[baprs]-[A-Za-z0-9-]{10,}'
  # OpenAI / Anthropic 风格密钥。
  # 前置边界 [^A-Za-z0-9_-] 是必须的：否则 risk-…、disk-…、task-… 这类连字符
  # 单词后面跟 32+ 个词字符就会被误判成密钥（文档里很容易出现）。
  '(^|[^A-Za-z0-9_-])sk-[A-Za-z0-9_-]{32,}'
  # Google API key
  'AIza[0-9A-Za-z_-]{35}'
)

fail=0

# 跑一次 git grep 并区分「无匹配（1）」与「执行出错（>1）」：
# 早期版本把任何非零都当「无匹配」，pathspec 写错时门禁会静默失效。
# 返回值：0 = 未命中或已豁免；1 = 命中。
scan() {
  local pattern="$1"
  shift
  local hits rc=0
  hits="$(git grep -nIE "$pattern" -- "$@" 2>/dev/null)" || rc=$?
  if [ "$rc" -gt 1 ]; then
    echo "密钥扫描失败：git grep 执行出错（exit $rc，pattern=$pattern）" >&2
    exit 1
  fi
  if [ "$rc" -eq 1 ]; then
    return 0
  fi
  # 行内标注 gitleaks:allow 视为已确认的误报
  hits="$(printf '%s\n' "$hits" | grep -v 'gitleaks:allow' || true)"
  if [ -z "$hits" ]; then
    return 0
  fi
  printf '%s\n' "$hits"
  return 1
}

for pattern in "${patterns[@]}"; do
  # 示例文件只放占位符，允许出现形似密钥的示例
  if ! scan "$pattern" . ':!.env.example' ':!**/*.example'; then
    fail=1
  fi
done

# 本项目特定：application*.yml 中的 secret/password 必须是环境变量占位符，
# 不能写成以字母/数字开头的非空字面量（空值、${ENV:default} 都允许）。
if ! scan '^[[:space:]]*(secret|password):[[:space:]]*["'"'"']?[A-Za-z0-9]' \
    'src/main/resources/application*.yml'; then
  echo "^^ 形似硬编码的密码/密钥：应改为 \${ENV_VAR:} 形式，或加 gitleaks:allow 说明原因" >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "密钥扫描失败：请移除上述内容，改用环境变量或 /run/secrets 注入。" >&2
  exit 1
fi

echo "密钥扫描通过：未发现高置信度密钥。"
