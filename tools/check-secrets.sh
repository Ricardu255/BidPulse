#!/usr/bin/env bash
# =====================================================================
# 轻量密钥扫描（CI release gate 的一部分）
# =====================================================================
# 只拦截「高置信度」模式，避免把文档里的示例值当成泄露。
# 扫描范围是 git 跟踪的文件；未跟踪文件请先 git add 再跑。
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
  # OpenAI / Anthropic 风格密钥
  'sk-[A-Za-z0-9_-]{32,}'
  # Google API key
  'AIza[0-9A-Za-z_-]{35}'
)

fail=0
for pattern in "${patterns[@]}"; do
  # 示例文件只放占位符，允许出现形似密钥的示例
  if git grep -nIE "$pattern" -- . ':!.env.example' ':!**/*.example' 2>/dev/null; then
    fail=1
  fi
done

# 本项目特定：application*.yml 中的 secret/password 必须是环境变量占位符，
# 不能写成以字母/数字开头的非空字面量（空值、${ENV:default} 都允许）。
if git grep -nIE '^[[:space:]]*(secret|password):[[:space:]]*["'"'"']?[A-Za-z0-9]' \
    -- 'src/main/resources/application*.yml' 2>/dev/null; then
  echo "^^ 发现疑似硬编码的密码/密钥，应改为 \${ENV_VAR:} 形式" >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "密钥扫描失败：请移除上述内容，改用环境变量或 /run/secrets 注入。" >&2
  exit 1
fi

echo "密钥扫描通过：未发现高置信度密钥。"
