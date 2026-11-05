# 数据库迁移（append-only）

`sql/all-schema.sql` **只用于全新的空库**：它带 `DROP TABLE`，会清掉业务数据。
已有数据的库（本机 demo、测试环境、生产）一律通过本目录的迁移脚本升级。

## 规则

1. **只追加，不改历史**：已经合入主干的迁移文件不允许再修改。要修正就新增一个版本。
2. **幂等**：同一脚本重复执行不报错、不重复改结构。用 `information_schema` 判断
   「列 / 索引是否已存在」，存在时执行 `SELECT 1` 跳过。
3. **不丢数据**：禁止 `DROP TABLE`、`TRUNCATE`、无 `WHERE` 的 `DELETE`。
   必须删列/改类型时，先在 PR 描述里写清备份与回滚方案。
4. **在线 DDL**：加索引使用 `ALGORITHM=INPLACE, LOCK=NONE`，避免阻塞竞价读写。
5. **可重入记录**：每个脚本末尾用 `INSERT IGNORE INTO schema_migration` 记录版本，
   同一版本只记一次。DDL 失败时 `mysql` 客户端会中止，不会写入记录。
6. **保留期约束**：`processed_event` 的保留天数不得短于 Kafka `retention.ms`
   （默认 7 天），否则 broker 重放历史消息时会重复计费/聚合。

## 命名

```
YYYYMMDD_NN_snake_case_description.sql
```

- `YYYYMMDD`：迁移编写日期，不是执行日期。
- `NN`：同一天内的两位序号，从 `01` 开始。
- 文件名按字典序即执行顺序。

## 执行

存量库首次接入时，先建迁移记录表（只需一次）：

```bash
mysql -h127.0.0.1 -uroot bidpulse < sql/migrations/00000000_00_bootstrap.sql
```

然后按文件名顺序执行全部迁移（可重复执行）：

```bash
for f in sql/migrations/*.sql; do
  echo "==> $f"
  mysql -h127.0.0.1 -uroot bidpulse < "$f"
done
```

集成测试库用脚本一键初始化（建库 + baseline + 迁移）：

```bash
tools/init-it-db.sh
```

CI 在每次构建都会对 `bidpulse_it` 重跑一遍 baseline 与全部迁移，
因此**任何不幂等的迁移都会在 CI 直接失败**。

## 新迁移模板

```sql
-- YYYYMMDD_NN · 一句话说明
-- 背景：为什么需要这次变更。
-- 幂等：已满足条件时跳过。

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'your_table'
        AND COLUMN_NAME = 'your_column'
    ),
    'SELECT 1',
    'ALTER TABLE your_table ADD COLUMN your_column INT NULL COMMENT ''说明'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migration(version, description)
VALUES ('YYYYMMDD_NN', '一句话说明');
```

## 当前版本

| 版本 | 说明 |
| --- | --- |
| `00000000_00` | bootstrap：创建 `schema_migration` 记录表 |
| `20261105_01` | `bid_log.age` 放宽为可空（只传 ageBucket 时不再伪造成 0） |
| `20261105_02` | `processed_event.created_at` + `idx_created`（保留期清理） |
| `20261105_03` | `billing_event.idx_ad_ts`（对账/冷启动按广告+时间汇总） |
| `20261105_04` | `ad.idx_adv`（管理端按广告主分页） |
| `20261105_05` | `ad_target.uk_ad_dim`（防重复定向导致二价退化） |
| `20261105_06` | `audit_log` 建表（早期库缺失时补建） |
