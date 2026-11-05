-- =====================================================================
-- 20261105_02 · processed_event 增加 created_at 与 idx_created
-- =====================================================================
-- 背景：ProcessedEventCleaner 按保留期删除去重记录，需要「首次处理时间」。
-- 保留期必须不短于 Kafka retention.ms（默认 7 天），否则重放历史消息会重复计费。
-- 幂等：列 / 索引已存在时分别跳过。
-- =====================================================================

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'processed_event'
        AND COLUMN_NAME = 'created_at'
    ),
    'SELECT 1',
    'ALTER TABLE processed_event ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''首次处理时间，供保留期清理'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'processed_event'
        AND INDEX_NAME = 'idx_created'
    ),
    'SELECT 1',
    'ALTER TABLE processed_event ADD KEY idx_created (created_at), ALGORITHM=INPLACE, LOCK=NONE'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migration(version, description)
VALUES ('20261105_02', 'processed_event.created_at + idx_created');
