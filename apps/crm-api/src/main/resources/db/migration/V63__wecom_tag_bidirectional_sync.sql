-- V63: 企业微信企业标签双向同步（官方接口与本地 Mock 适配器切换，严禁个人微信）。
-- 包含标签库映射表、标签同步批次并发锁表、客户标签推送任务队列。

CREATE TABLE `crm_wecom_tag_mapping` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企微企业ID',
  `crm_tag_code` VARCHAR(64) NOT NULL COMMENT 'CRM客户标签字典编码',
  `wecom_group_id` VARCHAR(128) NULL COMMENT '企微企业标签组ID',
  `wecom_group_name` VARCHAR(128) NULL COMMENT '企微企业标签组名称',
  `wecom_tag_id` VARCHAR(128) NULL COMMENT '企微企业标签ID（解除映射或停用时置空）',
  `wecom_tag_name` VARCHAR(128) NULL COMMENT '企微企业标签名称',
  `sync_status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '映射状态：ACTIVE/UNLINKED/DISABLED',
  `last_synced_at` DATETIME(3) NULL COMMENT '最近同步时间UTC',
  `created_by` BIGINT UNSIGNED NULL COMMENT '创建或最近维护操作人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_tag_map_crm` (`tenant_id`,`corp_id`,`crm_tag_code`),
  UNIQUE KEY `uk_wecom_tag_map_wecom` (`tenant_id`,`corp_id`,`wecom_tag_id`),
  KEY `idx_wecom_tag_map_status` (`tenant_id`,`corp_id`,`sync_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM客户标签与企微企业标签映射';

CREATE TABLE `crm_wecom_tag_sync_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企微企业ID',
  `sync_scope` VARCHAR(32) NOT NULL COMMENT '同步范围：CATALOG/CUSTOMER',
  `direction` VARCHAR(32) NOT NULL COMMENT '同步方向：PULL/PUSH/BIDIRECTIONAL',
  `trigger_source` VARCHAR(32) NOT NULL COMMENT '触发来源：MANUAL/CALLBACK/SCHEDULED',
  `adapter_name` VARCHAR(64) NOT NULL COMMENT '执行适配器名称',
  `status` VARCHAR(32) NOT NULL COMMENT '运行状态：RUNNING/SUCCESS/PARTIAL/ERROR',
  `running_corp_key` VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN `status` = 'RUNNING' THEN CONCAT(`corp_id`, ':', `sync_scope`) ELSE NULL END) STORED COMMENT '运行中企业+范围并发锁，完成后为空',
  `pulled_count` INT NOT NULL DEFAULT 0 COMMENT '从企微拉取更新数',
  `pushed_count` INT NOT NULL DEFAULT 0 COMMENT '推送至企微更新数',
  `skipped_count` INT NOT NULL DEFAULT 0 COMMENT '无变更或跳过数',
  `failed_count` INT NOT NULL DEFAULT 0 COMMENT '失败数',
  `error_masked` VARCHAR(1000) NULL COMMENT '脱敏错误摘要',
  `lease_until` DATETIME(3) NULL COMMENT '运行租约截止UTC',
  `started_at` DATETIME(3) NOT NULL COMMENT '开始时间UTC',
  `finished_at` DATETIME(3) NULL COMMENT '结束时间UTC',
  `created_by` BIGINT UNSIGNED NULL COMMENT '手工触发操作人，回调或定时为空',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_tag_sync_running` (`tenant_id`,`running_corp_key`),
  KEY `idx_wecom_tag_sync_run_recent` (`tenant_id`,`corp_id`,`started_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微标签双向同步运行批次';

CREATE TABLE `crm_wecom_customer_tag_push_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企微企业ID',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT 'CRM客户ID',
  `wecom_contact_id` BIGINT UNSIGNED NOT NULL COMMENT '企微联系人记录ID',
  `wecom_userid` VARCHAR(96) NOT NULL COMMENT '企微跟进成员userid',
  `external_userid` VARCHAR(192) NOT NULL COMMENT '客户企微external_userid',
  `add_tag_ids_json` JSON NOT NULL COMMENT '待追加的企微标签ID列表JSON',
  `remove_tag_ids_json` JSON NOT NULL COMMENT '待移除的企微标签ID列表JSON',
  `status` VARCHAR(32) NOT NULL COMMENT '任务状态：PENDING/PROCESSING/SUCCEEDED/RETRYABLE/FAILED',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '已执行尝试次数',
  `max_attempts` INT NOT NULL DEFAULT 5 COMMENT '最大重试次数',
  `next_retry_at` DATETIME(3) NULL COMMENT '下次重试时间UTC',
  `lease_until` DATETIME(3) NULL COMMENT '执行租约截止UTC',
  `error_masked` VARCHAR(1000) NULL COMMENT '脱敏错误摘要',
  `trigger_source` VARCHAR(32) NOT NULL COMMENT '触发来源：MANUAL/CRM_WRITE/BATCH_TAG/CALLBACK',
  `created_by` BIGINT UNSIGNED NULL COMMENT '触发操作人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_wecom_tag_push_poll` (`status`,`next_retry_at`,`id`),
  KEY `idx_wecom_tag_push_tenant_customer` (`tenant_id`,`customer_id`,`created_at`),
  KEY `idx_wecom_tag_push_tenant_status` (`tenant_id`,`corp_id`,`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM客户标签变更推送至企微任务队列';
