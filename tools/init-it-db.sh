#!/usr/bin/env bash
# =====================================================================
# 初始化集成测试库：建库 → 跑 baseline → 跑全部迁移
# =====================================================================
# 只允许 *_it 库名，避免误连 demo / 生产库。
#
# 用法（项目根目录）：
#   tools/init-it-db.sh
#
# 可覆盖的环境变量：
#   BIDPULSE_MYSQL_HOST      默认 127.0.0.1
#   BIDPULSE_MYSQL_PORT      默认 3306
#   BIDPULSE_MYSQL_USER      默认 root
#   BIDPULSE_MYSQL_PASSWORD  默认空（也接受 MYSQL_PASSWORD）
#   BIDPULSE_IT_DB           默认 bidpulse_it
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MYSQL_HOST="${BIDPULSE_MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${BIDPULSE_MYSQL_PORT:-3306}"
MYSQL_USER="${BIDPULSE_MYSQL_USER:-root}"
MYSQL_PASSWORD="${BIDPULSE_MYSQL_PASSWORD:-${MYSQL_PASSWORD:-}}"
DB_NAME="${BIDPULSE_IT_DB:-bidpulse_it}"

if ! [[ "$DB_NAME" =~ ^[A-Za-z0-9_]+_it$ ]]; then
  echo "拒绝初始化：库名必须以 _it 结尾且只含字母/数字/下划线（当前：$DB_NAME）" >&2
  exit 1
fi

if ! command -v mysql >/dev/null 2>&1; then
  echo "找不到 mysql 客户端。Ubuntu: apt-get install default-mysql-client；macOS: brew install mysql-client" >&2
  exit 1
fi

# 用 MYSQL_PWD 而不是 -p<password>，避免密码出现在进程列表里
if [ -n "$MYSQL_PASSWORD" ]; then
  export MYSQL_PWD="$MYSQL_PASSWORD"
fi

MYSQL=(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" --default-character-set=utf8mb4)

echo "==> 创建数据库 $DB_NAME"
"${MYSQL[@]}" -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` DEFAULT CHARACTER SET utf8mb4"

echo "==> 应用 baseline：sql/all-schema.sql"
"${MYSQL[@]}" "$DB_NAME" < "$ROOT/sql/all-schema.sql"

echo "==> 应用迁移：sql/migrations/*.sql"
shopt -s nullglob
migrations=("$ROOT"/sql/migrations/*.sql)
if [ ${#migrations[@]} -eq 0 ]; then
  echo "没有找到迁移文件：$ROOT/sql/migrations/*.sql" >&2
  exit 1
fi
for f in "${migrations[@]}"; do
  echo "    - $(basename "$f")"
  "${MYSQL[@]}" "$DB_NAME" < "$f"
done

echo
echo "==> 完成。集成测试环境变量："
echo "    export BIDPULSE_IT=true"
echo "    export BIDPULSE_JDBC_URL='jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$DB_NAME?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true'"
echo "    export BIDPULSE_REDIS_DATABASE=1"
echo "    export BIDPULSE_KAFKA_TOPIC=bidpulse_it_bid_event"
echo "    export BIDPULSE_KAFKA_DLT_TOPIC=bidpulse_it_bid_event.DLT"
