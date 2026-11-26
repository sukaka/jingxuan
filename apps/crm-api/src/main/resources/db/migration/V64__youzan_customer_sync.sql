-- V64: 有赞客户拉取与 CRM 正式建档同步（持久化断点游标、并发互斥批次与脱敏逐条结果明细）。
-- 约束：
-- 1. 绝不落明文手机号与明文微信 OpenID；crm_youzan_customer_sync_item 仅存脱敏展示串与外键 ID；
-- 2. running_shop_key 生成列确保同租户、同店铺、同模式同一时刻最多 1 个 RUNNING 批次；
-- 3. 客户建档严格复用 crm_customer / crm_customer_identity / crm_youzan_customer_link，不自动覆盖老客户。

CREATE TABLE `crm_youzan_customer_sync_cursor` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '有赞店铺 kdt_id',
  `source_mode` VARCHAR(32) NOT NULL COMMENT '拉取模式：SCRM_LIST/ORDER_BUYERS',
  `cursor_json` TEXT NULL COMMENT '断点游标JSON（anchorStart/anchorEnd/windowStart/windowEnd/pageNo/itemOffset）',
  `has_more` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '当前扫描周期是否仍有未拉取记录',
  `last_anchor_at` DATETIME(3) NULL COMMENT '上次扫描周期锁定的截止时间UTC（完成后自动作为下轮增量起点）',
  `last_synced_at` DATETIME(3) NULL COMMENT '最近一次拉取完成时间UTC',
  `total_imported_count` BIGINT NOT NULL DEFAULT 0 COMMENT '累计通过本通道新建的CRM客户数',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_yz_cust_cursor` (`tenant_id`,`shop_id`,`source_mode`),
  KEY `idx_yz_cust_cursor_shop` (`tenant_id`,`shop_id`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞客户拉取断点游标与增量进度';

CREATE TABLE `crm_youzan_customer_sync_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '有赞店铺 kdt_id',
  `source_mode` VARCHAR(32) NOT NULL COMMENT '拉取模式：SCRM_LIST/ORDER_BUYERS',
  `pull_mode` VARCHAR(16) NOT NULL COMMENT '批次模式：BATCH/ALL',
  `requested_limit` INT NULL COMMENT 'BATCH模式目标拉取条数，ALL模式为空',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '本次新建客户默认归属管家ID',
  `adapter_name` VARCHAR(64) NOT NULL COMMENT '实际执行适配器名称',
  `status` VARCHAR(32) NOT NULL COMMENT '运行状态：RUNNING/SUCCEEDED/PARTIAL/FAILED',
  `running_shop_key` VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN `status` = 'RUNNING' THEN CONCAT(`shop_id`, ':', `source_mode`) ELSE NULL END) STORED COMMENT '运行中店铺+模式并发互斥锁，结束后为空',
  `fetched_count` INT NOT NULL DEFAULT 0 COMMENT '本次拉取处理的去重客户记录数',
  `created_count` INT NOT NULL DEFAULT 0 COMMENT '本次新建CRM正式客户数',
  `skipped_existing_count` INT NOT NULL DEFAULT 0 COMMENT '已绑定yz_open_id跳过数',
  `mobile_conflict_count` INT NOT NULL DEFAULT 0 COMMENT '手机号命中CRM老客户冲突数（跳过待人工确认）',
  `failed_count` INT NOT NULL DEFAULT 0 COMMENT '无效记录或建档失败数',
  `has_more` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '本次结束后是否仍有更多可继续拉取客户',
  `cursor_after_json` TEXT NULL COMMENT '本次结束后的断点游标快照JSON',
  `warnings_json` JSON NULL COMMENT '非致命告警摘要JSON数组',
  `error_masked` VARCHAR(1000) NULL COMMENT '脱敏错误摘要',
  `lease_until` DATETIME(3) NULL COMMENT '并发租约到期时间UTC',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '触发操作人ID',
  `started_at` DATETIME(3) NOT NULL COMMENT '开始时间UTC',
  `finished_at` DATETIME(3) NULL COMMENT '结束时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_yz_cust_run_running` (`tenant_id`,`running_shop_key`),
  KEY `idx_yz_cust_run_recent` (`tenant_id`,`shop_id`,`started_at`,`id`),
  KEY `idx_yz_cust_run_mode` (`tenant_id`,`shop_id`,`source_mode`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞客户拉取建档运行批次';

CREATE TABLE `crm_youzan_customer_sync_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `run_id` BIGINT UNSIGNED NOT NULL COMMENT '所属运行批次ID',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '有赞店铺 kdt_id',
  `external_customer_id` VARCHAR(128) NOT NULL COMMENT '有赞买家yz_open_id',
  `outcome` VARCHAR(32) NOT NULL COMMENT '处理结果：CREATED/SKIPPED_EXISTING/MOBILE_CONFLICT/FAILED',
  `display_name_masked` VARCHAR(96) NULL COMMENT '脱敏展示称呼/昵称',
  `mobile_masked` VARCHAR(32) NULL COMMENT '脱敏手机号138****5678',
  `crm_customer_id` BIGINT UNSIGNED NULL COMMENT '新建或已关联的CRM客户ID',
  `crm_customer_no` VARCHAR(48) NULL COMMENT '新建或已关联的CRM客户编号',
  `conflict_customer_ids_json` JSON NULL COMMENT '手机号命中的已有CRM客户ID列表JSON',
  `conflict_customer_nos_json` JSON NULL COMMENT '手机号命中的已有CRM客户编号列表JSON',
  `detail_masked` VARCHAR(500) NULL COMMENT '脱敏结果说明或失败原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  KEY `idx_yz_cust_item_run` (`tenant_id`,`run_id`,`id`),
  KEY `idx_yz_cust_item_outcome` (`tenant_id`,`run_id`,`outcome`,`id`),
  CONSTRAINT `fk_yz_cust_item_run` FOREIGN KEY (`run_id`) REFERENCES `crm_youzan_customer_sync_run` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞客户拉取建档批次逐条脱敏结果明细';
