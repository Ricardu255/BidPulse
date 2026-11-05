-- =====================================================================
-- 20261105_05 · ad_target 增加唯一键 uk_ad_dim(ad_id, dim_type, dim_value)
-- =====================================================================
-- 背景：重复定向会让 Join 求交时同一广告被重复放大，「次高价」可能取到
--       自己的重复行，二价退化成「自身出价」= 一价。
-- 前置检查（有重复行时下面的 ALTER 会以 Duplicate entry 失败并中止，
-- 迁移记录不会写入；先清理重复行再重跑本文件）：
--   SELECT ad_id, dim_type, dim_value, COUNT(*) FROM ad_target
--   GROUP BY ad_id, dim_type, dim_value HAVING COUNT(*) > 1;
-- 幂等：唯一键已存在时跳过。
-- =====================================================================

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'ad_target'
        AND INDEX_NAME = 'uk_ad_dim'
    ),
    'SELECT 1',
    'ALTER TABLE ad_target ADD UNIQUE KEY uk_ad_dim (ad_id, dim_type, dim_value), ALGORITHM=INPLACE, LOCK=NONE'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migration(version, description)
VALUES ('20261105_05', 'ad_target.uk_ad_dim');
