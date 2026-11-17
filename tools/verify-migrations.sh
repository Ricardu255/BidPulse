#!/usr/bin/env bash
# =====================================================================
# 迁移升级演练：在「老库」形状上真正跑一遍 sql/migrations/*.sql
# =====================================================================
# 为什么需要它：
#   tools/init-it-db.sh 与 console-e2e-live 都是「先建 baseline、再跑迁移」，
#   迁移里的 information_schema 守卫全部命中，ALTER 分支永远不执行。
#   真正只在对存量库升级时才跑的 SQL（引号转义、ALGORITHM=INPLACE 兼容性、
#   唯一键撞上重复行）因此从不进 CI。
#
# 本脚本的做法：
#   1) 用 sql/all-schema.sql 建库（此时是「全新」结构）；
#   2) 用 tools/legacy-downgrade.sql 把它退回「迁移 01–08 之前」的老库；
#   3) 按文件名顺序真正执行全部迁移（含 bootstrap）；
#   4) 断言列/索引/唯一性/迁移记录都恢复到预期；
#   5) 再执行一遍迁移，确认全部幂等；
#   6) 删除演练库（--keep 可保留现场）。
#
# 用法（项目根目录）：
#   tools/verify-migrations.sh
#   tools/verify-migrations.sh --keep
#
# 可覆盖的环境变量（与 init-it-db.sh 一致）：
#   BIDPULSE_MYSQL_HOST      默认 127.0.0.1
#   BIDPULSE_MYSQL_PORT      默认 3306
#   BIDPULSE_MYSQL_USER      默认 root
#   BIDPULSE_MYSQL_PASSWORD  默认空（也接受 MYSQL_PASSWORD）
#   BIDPULSE_MIGRATION_DB    默认 bidpulse_migration
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MYSQL_HOST="${BIDPULSE_MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${BIDPULSE_MYSQL_PORT:-3306}"
MYSQL_USER="${BIDPULSE_MYSQL_USER:-root}"
MYSQL_PASSWORD="${BIDPULSE_MYSQL_PASSWORD:-${MYSQL_PASSWORD:-}}"
DB_NAME="${BIDPULSE_MIGRATION_DB:-bidpulse_migration}"

KEEP=0
for arg in "$@"; do
  case "$arg" in
    --keep) KEEP=1 ;;
  esac
done

# 只允许 *_migration 库名：本脚本开头就 DROP DATABASE，误连生产库代价极大
if ! [[ "$DB_NAME" =~ ^[A-Za-z0-9_]+_migration$ ]]; then
  echo "拒绝执行：库名必须以 _migration 结尾且只含字母/数字/下划线（当前：$DB_NAME）" >&2
  exit 1
fi

if ! command -v mysql >/dev/null 2>&1; then
  echo "找不到 mysql 客户端。Ubuntu: apt-get install default-mysql-client；macOS: brew install mysql-client" >&2
  exit 1
fi

if [ -n "$MYSQL_PASSWORD" ]; then
  export MYSQL_PWD="$MYSQL_PASSWORD"
fi

MYSQL=(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" --default-character-set=utf8mb4)

echo "==> 重建演练库 $DB_NAME"
"${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` DEFAULT CHARACTER SET utf8mb4"

echo "==> 建立全新建库基线：sql/all-schema.sql"
"${MYSQL[@]}" "$DB_NAME" < "$ROOT/sql/all-schema.sql"

echo "==> 退回老库形状：tools/legacy-downgrade.sql"
"${MYSQL[@]}" "$DB_NAME" < "$ROOT/tools/legacy-downgrade.sql"

shopt -s nullglob
migrations=("$ROOT"/sql/migrations/*.sql)
if [ ${#migrations[@]} -eq 0 ]; then
  echo "没有找到迁移文件：$ROOT/sql/migrations/*.sql" >&2
  exit 1
fi

echo "==> 在存量库上执行迁移（这一步才会真正走 ALTER 分支）"
for f in "${migrations[@]}"; do
  echo "    - $(basename "$f")"
  "${MYSQL[@]}" "$DB_NAME" < "$f"
done

echo "==> 断言升级后的结构"
checks=$(cat <<'SQL'
SELECT 'bid_log.age 可空' AS item, COUNT(*) AS ok
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bid_log'
   AND COLUMN_NAME = 'age' AND IS_NULLABLE = 'YES'
UNION ALL
SELECT 'bid_log.trace_json 列', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bid_log'
   AND COLUMN_NAME = 'trace_json'
UNION ALL
SELECT 'processed_event.created_at 列', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'processed_event'
   AND COLUMN_NAME = 'created_at'
UNION ALL
SELECT 'processed_event.idx_created 索引', COUNT(DISTINCT INDEX_NAME)
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'processed_event'
   AND INDEX_NAME = 'idx_created'
UNION ALL
SELECT 'processed_event.payload_hash 列', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'processed_event'
   AND COLUMN_NAME = 'payload_hash'
UNION ALL
SELECT 'billing_event.payload_hash 列', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'billing_event'
   AND COLUMN_NAME = 'payload_hash'
UNION ALL
SELECT 'billing_event.idx_ad_ts 索引', COUNT(DISTINCT INDEX_NAME)
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'billing_event'
   AND INDEX_NAME = 'idx_ad_ts'
UNION ALL
SELECT 'ad.idx_adv 索引', COUNT(DISTINCT INDEX_NAME)
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ad'
   AND INDEX_NAME = 'idx_adv'
UNION ALL
SELECT 'ad_target.uk_ad_dim 唯一键', COUNT(DISTINCT INDEX_NAME)
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ad_target'
   AND INDEX_NAME = 'uk_ad_dim' AND NON_UNIQUE = 0
UNION ALL
SELECT 'audit_log 表', COUNT(*)
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
SQL
)

failed=0
while IFS=$'\t' read -r item ok; do
  [ -z "${item:-}" ] && continue
  if [ "$ok" = "1" ]; then
    printf '    ✓ %s\n' "$item"
  else
    printf '    ✗ %s（查到 %s 条）\n' "$item" "${ok:-0}"
    failed=1
  fi
done < <("${MYSQL[@]}" -N -B -e "$checks" "$DB_NAME")

expected_versions=${#migrations[@]}
actual_versions="$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM schema_migration" "$DB_NAME")"
if [ "$actual_versions" = "$expected_versions" ]; then
  printf '    ✓ schema_migration 记录数 %s\n' "$actual_versions"
else
  printf '    ✗ schema_migration 记录数 %s，期望 %s\n' "$actual_versions" "$expected_versions"
  failed=1
fi

echo "==> 再执行一遍迁移（幂等性）"
for f in "${migrations[@]}"; do
  "${MYSQL[@]}" "$DB_NAME" < "$f"
done
echo "    ✓ 二次执行未报错"

versions_after="$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM schema_migration" "$DB_NAME")"
if [ "$versions_after" = "$actual_versions" ]; then
  printf '    ✓ 迁移记录未被重复写入（%s）\n' "$versions_after"
else
  printf '    ✗ 迁移记录从 %s 变成 %s，说明 INSERT 不是幂等的\n' "$actual_versions" "$versions_after"
  failed=1
fi

if [ "$KEEP" -eq 0 ]; then
  echo "==> 清理演练库 $DB_NAME"
  "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`"
else
  echo "==> 保留演练库 $DB_NAME（--keep）"
fi

if [ "$failed" -ne 0 ]; then
  echo
  echo "迁移升级演练失败：存量库升级路径有问题，别把它带上线。" >&2
  exit 1
fi

echo
echo "==> 通过：存量库升级路径可用，且迁移可重复执行"
