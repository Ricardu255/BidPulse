-- =====================================================================
-- 20261105_04 · ad 增加 idx_adv(advertiser_id)
-- =====================================================================
-- 背景：管理端 /admin/ad 列表按 advertiser_id 过滤、id 倒序分页。
--       该索引只服务管理后台，不参与竞价检索路径（ad 检索仍走主键 eq_ref）。
-- 幂等：索引已存在时跳过。
-- =====================================================================

SET @ddl = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'ad'
        AND INDEX_NAME = 'idx_adv'
    ),
    'SELECT 1',
    'ALTER TABLE ad ADD KEY idx_adv (advertiser_id), ALGORITHM=INPLACE, LOCK=NONE'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migration(version, description)
VALUES ('20261105_04', 'ad.idx_adv');
