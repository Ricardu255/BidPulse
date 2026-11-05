-- =====================================================================
-- 20261105_06 · audit_log（控制台/管理端写操作审计）
-- =====================================================================
-- 背景：早期库只有 7 张业务表，没有 audit_log；缺失时审计只打应用日志，
--       无法回答「谁在什么时候改了什么」。
-- 幂等：表已存在时跳过（CREATE TABLE IF NOT EXISTS）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS audit_log (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  actor      VARCHAR(64)  NOT NULL COMMENT '操作人用户名；匿名操作记 anonymous',
  role       VARCHAR(32)  NOT NULL COMMENT '操作时角色：VIEWER/OPERATOR/ADMIN/ANONYMOUS',
  method     VARCHAR(8)   NOT NULL COMMENT 'HTTP 方法；登录事件记 LOGIN',
  path       VARCHAR(255) NOT NULL COMMENT '请求路径',
  status     INT          NOT NULL COMMENT '响应状态码',
  ip         VARCHAR(64)  NOT NULL COMMENT '客户端 IP（X-Forwarded-For 首跳优先）',
  detail     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '查询串/登录用户名，不含密码',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
  PRIMARY KEY (id),
  KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='控制台与管理端写操作审计';

INSERT IGNORE INTO schema_migration(version, description)
VALUES ('20261105_06', 'audit_log 建表');
