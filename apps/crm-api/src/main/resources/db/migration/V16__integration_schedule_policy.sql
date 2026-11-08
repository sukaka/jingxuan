-- 有赞与企微可编辑定时同步策略。
ALTER TABLE `crm_youzan_sync_policy`
  ADD COLUMN `next_run_at` DATETIME(3) NULL COMMENT '下次计划执行时间，UTC；NULL按旧周期规则' AFTER `enabled`,
  ADD KEY `idx_youzan_sync_policy_next_run` (`enabled`,`next_run_at`);

CREATE TABLE IF NOT EXISTS `crm_wecom_sync_policy` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企微企业ID',
  `enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '定时策略总开关',
  `contact_sync_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '客户资料同步开关',
  `archive_sync_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '会话归档同步开关',
  `interval_seconds` INT NOT NULL COMMENT '执行间隔秒数，60-86400',
  `next_run_at` DATETIME(3) NULL COMMENT '下次计划执行时间，UTC',
  `last_scheduled_at` DATETIME(3) NULL COMMENT '最近调度时间，UTC',
  `last_status` VARCHAR(32) NOT NULL DEFAULT 'NEVER' COMMENT 'NEVER/SUCCESS/PARTIAL/ERROR',
  `last_error` VARCHAR(1000) NULL COMMENT '最近脱敏错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_sync_policy_1` (`tenant_id`,`corp_id`),
  KEY `idx_wecom_sync_policy_1` (`enabled`,`next_run_at`),
  KEY `idx_wecom_sync_policy_2` (`tenant_id`,`corp_id`,`last_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微客户资料与会话归档定时同步策略';
