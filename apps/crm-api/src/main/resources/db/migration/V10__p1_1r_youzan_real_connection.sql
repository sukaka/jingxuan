-- P1-1R 有赞真实店铺授权、同步策略与任务。密钥和令牌仅存 AES-GCM 密文。
CREATE TABLE IF NOT EXISTS `crm_youzan_shop_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '有赞店铺 kdt_id',
  `shop_name` VARCHAR(128) NOT NULL COMMENT '店铺显示名称',
  `client_id` VARCHAR(128) NOT NULL COMMENT '有赞云应用 client_id',
  `client_secret_cipher` LONGTEXT NOT NULL COMMENT 'client_secret AES-GCM 密文',
  `access_token_cipher` LONGTEXT NULL COMMENT 'access_token AES-GCM 密文',
  `refresh_token_cipher` LONGTEXT NULL COMMENT 'refresh_token AES-GCM 密文',
  `token_expires_at` DATETIME(3) NULL COMMENT 'access_token 到期时间，UTC',
  `connection_status` VARCHAR(32) NOT NULL COMMENT 'UNVERIFIED/CONNECTED/FAILED',
  `sync_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否允许真实同步',
  `last_error` VARCHAR(1000) NULL COMMENT '脱敏连接错误',
  `last_connected_at` DATETIME(3) NULL COMMENT '最近连接成功时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_shop_config_1` (`tenant_id`,`shop_id`),
  UNIQUE KEY `uk_youzan_shop_config_2` (`client_id`,`shop_id`),
  KEY `idx_youzan_shop_config_1` (`tenant_id`,`sync_enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞店铺授权和加密令牌配置';

CREATE TABLE IF NOT EXISTS `crm_youzan_sync_policy` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '有赞店铺 kdt_id',
  `initial_start_time` DATETIME(3) NOT NULL COMMENT '首次历史导入开始时间，UTC',
  `interval_seconds` INT NOT NULL COMMENT '增量同步间隔秒数',
  `overlap_seconds` INT NOT NULL COMMENT '增量窗口重叠秒数',
  `page_size` INT NOT NULL COMMENT '订单列表每页条数，1-100',
  `enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用自动增量同步',
  `last_scheduled_at` DATETIME(3) NULL COMMENT '最近调度时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_sync_policy_1` (`tenant_id`,`shop_id`),
  KEY `idx_youzan_sync_policy_1` (`enabled`,`last_scheduled_at`),
  CONSTRAINT `fk_youzan_sync_policy_shop` FOREIGN KEY (`tenant_id`,`shop_id`)
    REFERENCES `crm_youzan_shop_config` (`tenant_id`,`shop_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞全量和增量同步策略';

CREATE TABLE IF NOT EXISTS `crm_youzan_sync_job` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '有赞店铺 kdt_id',
  `job_type` VARCHAR(32) NOT NULL COMMENT 'FULL/INCREMENTAL/RECONCILE/WEBHOOK',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED',
  `cursor_value` TEXT NULL COMMENT '时间窗和页码游标',
  `window_start` DATETIME(3) NULL COMMENT '同步窗口开始，UTC',
  `window_end` DATETIME(3) NULL COMMENT '同步窗口结束，UTC',
  `received_count` INT NOT NULL DEFAULT 0 COMMENT '接收事件数',
  `processed_count` INT NOT NULL DEFAULT 0 COMMENT '处理成功数',
  `duplicate_count` INT NOT NULL DEFAULT 0 COMMENT '重复事件数',
  `issue_count` INT NOT NULL DEFAULT 0 COMMENT '异常数',
  `last_error` VARCHAR(1000) NULL COMMENT '脱敏错误',
  `lease_owner` VARCHAR(96) NULL COMMENT '任务执行实例',
  `lease_expires_at` DATETIME(3) NULL COMMENT '执行租约到期时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `started_at` DATETIME(3) NULL COMMENT '开始时间，UTC',
  `finished_at` DATETIME(3) NULL COMMENT '完成时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_youzan_sync_job_1` (`tenant_id`,`shop_id`,`id`),
  KEY `idx_youzan_sync_job_2` (`tenant_id`,`shop_id`,`status`,`lease_expires_at`),
  CONSTRAINT `fk_youzan_sync_job_shop` FOREIGN KEY (`tenant_id`,`shop_id`)
    REFERENCES `crm_youzan_shop_config` (`tenant_id`,`shop_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞同步任务与互斥执行记录';

ALTER TABLE `crm_youzan_sync_cursor`
  MODIFY COLUMN `cursor_value` TEXT NULL COMMENT 'Mock页码或真实时间窗/页码JSON游标',
  ADD COLUMN `window_start` DATETIME(3) NULL COMMENT '当前同步窗口开始，UTC' AFTER `cursor_value`,
  ADD COLUMN `window_end` DATETIME(3) NULL COMMENT '当前同步窗口结束，UTC' AFTER `window_start`;

ALTER TABLE `crm_youzan_event`
  ADD COLUMN `provider_payload_masked_json` LONGTEXT NULL COMMENT '有赞官方原始报文递归脱敏JSON' AFTER `payload_cipher`,
  ADD COLUMN `provider_payload_cipher` LONGTEXT NULL COMMENT '有赞官方原始报文AES-GCM密文' AFTER `provider_payload_masked_json`;

INSERT INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
VALUES (1,'integration:youzan:manage','有赞店铺授权与同步配置')
ON DUPLICATE KEY UPDATE `description`=VALUES(`description`);
