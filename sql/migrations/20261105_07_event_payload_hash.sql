-- =====================================================================
-- 20261105_07 · 事件载荷指纹（资金/幂等正确性）
--
-- 背景：
--   processed_event 原来只记 (event_id, grp)。同一个 event_id 被 producer bug
--   或恶意重放成不同载荷时，第二条会被静默丢弃；billing_event 的
--   ON DUPLICATE KEY UPDATE pay_price=VALUES(pay_price) 还会在去重记录
--   过期后静默改写金额。
--
-- 本迁移只加列，不改数据：
--   processed_event.payload_hash  消费组去重时比对载荷
--   billing_event.payload_hash    计费流水自证载荷，拒绝迟到重放改金额
--
-- 老数据保持 NULL：消费端遇到 NULL 按「旧版记录」处理并回填，
-- 不把历史数据误判为冲突。新写入一律带 64 位 SHA-256。
-- =====================================================================

SET @ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'processed_event'
             AND COLUMN_NAME = 'payload_hash'),
    'SELECT 1',
    'ALTER TABLE processed_event ADD COLUMN payload_hash CHAR(64) NULL COMMENT ''规范化载荷 SHA-256，用于识别同 eventId 不同载荷'' AFTER grp'
  )
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'billing_event'
             AND COLUMN_NAME = 'payload_hash'),
    'SELECT 1',
    'ALTER TABLE billing_event ADD COLUMN payload_hash CHAR(64) NULL COMMENT ''规范化载荷 SHA-256，用于拒绝迟到重放改写金额'' AFTER ts'
  )
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migration(version, description)
VALUES ('20261105_07', 'processed_event/billing_event payload_hash');
