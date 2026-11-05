-- =====================================================================
-- 20261105_08 · bid_log.trace_json（决策可解释性）
--
-- 背景：
--   控制台发起的竞价需要回答「为什么是它赢 / 为什么走兜底」，而 bid_log
--   只有计数和耗时汇总。本迁移给 bid_log 增加一个可空的 JSON 列，随竞价明细
--   异步落库；外部 /bid 不采集，存量行保持 NULL。
--
-- 只加列，不改数据：
--   - 轨迹是「可采样」数据，丢一条不影响计费与对账；
--   - JSON 列不建索引，避免给写入热路径增加维护成本；
--   - 候选封顶 10 条且不含用户标识，控制体积与隐私。
-- =====================================================================

SET @ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bid_log'
             AND COLUMN_NAME = 'trace_json'),
    'SELECT 1',
    'ALTER TABLE bid_log ADD COLUMN trace_json JSON NULL COMMENT ''决策轨迹 JSON，仅控制台竞价写入；可采样、不参与对账'' AFTER bid_at'
  )
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migration(version, description)
VALUES ('20261105_08', 'bid_log.trace_json 决策轨迹');
