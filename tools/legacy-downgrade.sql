-- =====================================================================
-- 把「全新建库基线」（sql/all-schema.sql）退回成「迁移 01–08 之前」的形状
-- =====================================================================
-- 只给 tools/verify-migrations.sh 演练升级路径用，不要在生产库上执行。
--
-- 为什么需要它：CI 里 baseline 先建好全部新结构，再跑迁移时每一条
-- information_schema 检查都命中，ALTER 分支永远走不到。真出问题的恰恰是
-- 那段只在对存量库升级时才执行的 SQL（引号转义、ALGORITHM=INPLACE 兼容性、
-- 唯一键撞上重复行等）。本文件把结构还原成「老库」，让迁移完整跑一遍。
--
-- 顺序：先删索引再删列（依赖列的索引不能单独留下），最后清掉迁移记录。
-- =====================================================================

ALTER TABLE bid_log DROP COLUMN trace_json;
ALTER TABLE bid_log MODIFY age TINYINT NOT NULL DEFAULT 0
  COMMENT '年龄，请求未提供时为 0（迁移 01 之前的语义）';

DROP TABLE IF EXISTS audit_log;

ALTER TABLE billing_event DROP INDEX idx_ad_ts;
ALTER TABLE billing_event DROP COLUMN payload_hash;

ALTER TABLE processed_event DROP INDEX idx_created;
ALTER TABLE processed_event DROP COLUMN created_at;
ALTER TABLE processed_event DROP COLUMN payload_hash;

ALTER TABLE ad DROP INDEX idx_adv;

ALTER TABLE ad_target DROP INDEX uk_ad_dim;

DELETE FROM schema_migration;
