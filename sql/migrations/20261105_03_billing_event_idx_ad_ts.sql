-- =====================================================================
-- 20261105_03 · billing_event 增加 idx_ad_ts(ad_id, ts)
-- =====================================================================
-- 背景：BudgetReconJob 对账与冷启动重建都按「某广告某日」汇总消耗，
--       没有索引时每次都是全表扫描，账单表越大越慢。
-- 幂等：索引已存在时跳过。
-- =====================================================================

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'billing_event'
        AND INDEX_NAME = 'idx_ad_ts'
    ),
    'SELECT 1',
    'ALTER TABLE billing_event ADD KEY idx_ad_ts (ad_id, ts), ALGORITHM=INPLACE, LOCK=NONE'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migration(version, description)
VALUES ('20261105_03', 'billing_event.idx_ad_ts');
