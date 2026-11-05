-- =====================================================================
-- 20261105_01 · bid_log.age 放宽为可空
-- =====================================================================
-- 背景：只传 ageBucket 的请求此前被伪造成 age=0，污染年龄分布统计。
-- 幂等：age 已经可空时跳过（fresh baseline 已包含该结构）。
-- =====================================================================

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'bid_log'
        AND COLUMN_NAME = 'age'
        AND IS_NULLABLE = 'YES'
    ),
    'SELECT 1',
    'ALTER TABLE bid_log MODIFY age TINYINT NULL DEFAULT NULL COMMENT ''年龄，请求未提供（仅传 ageBucket）时为 NULL'''
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migration(version, description)
VALUES ('20261105_01', 'bid_log.age 放宽为可空');
