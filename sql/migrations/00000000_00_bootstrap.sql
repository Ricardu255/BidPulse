-- =====================================================================
-- 00000000_00 · 迁移记录表 bootstrap
-- =====================================================================
-- 全新的库直接执行 sql/all-schema.sql 即可（其中已包含本表与全部迁移记录）。
-- 本文件只用于「已有数据、但还没有 schema_migration 表」的存量库首次接入。
--
-- 幂等：可重复执行。
-- =====================================================================

CREATE TABLE IF NOT EXISTS schema_migration (
  version     VARCHAR(32)  NOT NULL COMMENT '迁移版本号，如 20261105_01',
  description VARCHAR(128) NOT NULL COMMENT '迁移说明',
  applied_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次执行时间',
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库迁移记录（append-only）';

INSERT IGNORE INTO schema_migration(version, description)
VALUES ('00000000_00', 'bootstrap: 迁移记录表');
