CREATE TABLE `crm_wecom_staff_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '租户',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企业ID',
  `wecom_userid` VARCHAR(96) NOT NULL COMMENT '企微成员userid',
  `staff_user_id` BIGINT UNSIGNED NOT NULL COMMENT '人工确认映射的CRM员工',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否参与客户同步',
  `last_synced_at` DATETIME(3) NULL COMMENT '最近同步UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_staff_1` (`tenant_id`,`corp_id`,`wecom_userid`),
  KEY `idx_wecom_staff_1` (`tenant_id`,`corp_id`,`enabled`,`staff_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微成员与CRM员工人工映射';

ALTER TABLE `crm_wecom_contact`
  ADD COLUMN `name` VARCHAR(255) NULL COMMENT '企微返回的客户名称' AFTER `staff_user_id`,
  ADD COLUMN `avatar` VARCHAR(1024) NULL COMMENT '客户头像URL' AFTER `name`,
  ADD COLUMN `contact_type` TINYINT NULL COMMENT '1微信用户2企业微信用户' AFTER `avatar`,
  ADD COLUMN `gender` TINYINT NULL COMMENT '0未知1男2女' AFTER `contact_type`,
  ADD COLUMN `unionid` VARCHAR(192) NULL COMMENT '满足官方条件时返回的微信unionid' AFTER `gender`,
  ADD COLUMN `remark` VARCHAR(1000) NULL COMMENT '跟进成员备注' AFTER `unionid`,
  ADD COLUMN `description` TEXT NULL COMMENT '跟进成员描述' AFTER `remark`,
  ADD COLUMN `add_way` INT NULL COMMENT '官方添加来源编码' AFTER `description`,
  ADD COLUMN `channel_state` VARCHAR(512) NULL COMMENT '联系我state或获客渠道参数' AFTER `add_way`,
  ADD COLUMN `follow_created_at` DATETIME(3) NULL COMMENT '成员添加客户时间UTC' AFTER `channel_state`,
  ADD COLUMN `tag_snapshot_json` JSON NULL COMMENT '企业及规则组标签快照' AFTER `follow_created_at`,
  ADD COLUMN `follow_status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED_BY_STAFF/DELETED_BY_CUSTOMER' AFTER `tag_snapshot_json`,
  ADD COLUMN `last_sync_run_id` BIGINT UNSIGNED NULL COMMENT '最近确认存在的完整同步运行' AFTER `follow_status`,
  ADD KEY `idx_wecom_contact_3` (`tenant_id`,`corp_id`,`follow_status`),
  ADD KEY `idx_wecom_contact_4` (`tenant_id`,`corp_id`,`last_sync_run_id`);

CREATE TABLE `crm_wecom_contact_sync_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '租户',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企业ID',
  `run_type` VARCHAR(32) NOT NULL COMMENT 'FULL/RECONCILE/CALLBACK',
  `status` VARCHAR(32) NOT NULL COMMENT 'RUNNING/SUCCESS/ERROR',
  `cursor_value` VARCHAR(1024) NULL COMMENT '最后成功分页游标',
  `received_count` INT NOT NULL DEFAULT 0 COMMENT '接收跟进关系数',
  `upserted_count` INT NOT NULL DEFAULT 0 COMMENT '写入跟进关系数',
  `deactivated_count` INT NOT NULL DEFAULT 0 COMMENT '对账失效关系数',
  `error_masked` VARCHAR(1000) NULL COMMENT '脱敏错误',
  `started_at` DATETIME(3) NOT NULL COMMENT '开始UTC',
  `finished_at` DATETIME(3) NULL COMMENT '结束UTC',
  `created_by` BIGINT UNSIGNED NULL COMMENT '手工触发人，定时为空',
  `running_corp_id` VARCHAR(96) GENERATED ALWAYS AS (CASE WHEN `status`='RUNNING' AND `run_type` IN ('FULL','RECONCILE') THEN `corp_id` ELSE NULL END) STORED COMMENT '运行中企业并发锁，完成为空',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_sync_run_running` (`tenant_id`,`running_corp_id`),
  KEY `idx_wecom_sync_run_1` (`tenant_id`,`corp_id`,`id`),
  KEY `idx_wecom_sync_run_2` (`tenant_id`,`corp_id`,`status`,`run_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微客户同步运行历史';

CREATE TABLE `crm_wecom_callback_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '租户',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企业ID',
  `event_fingerprint` CHAR(64) NOT NULL COMMENT '验签解密事件SHA256去重指纹',
  `event_type` VARCHAR(64) NOT NULL COMMENT '企微Event',
  `change_type` VARCHAR(64) NULL COMMENT '企微ChangeType',
  `wecom_userid` VARCHAR(96) NULL COMMENT '企微成员userid',
  `external_userid` VARCHAR(192) NULL COMMENT '外部联系身份',
  `event_time` DATETIME(3) NULL COMMENT '官方事件时间UTC',
  `status` VARCHAR(32) NOT NULL COMMENT 'RECEIVED/PROCESSING/SUCCEEDED/RETRYABLE/FAILED/IGNORED',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '处理尝试次数',
  `next_retry_at` DATETIME(3) NULL COMMENT '下次重试UTC',
  `error_masked` VARCHAR(1000) NULL COMMENT '脱敏处理错误',
  `received_at` DATETIME(3) NOT NULL COMMENT '接收UTC',
  `processed_at` DATETIME(3) NULL COMMENT '完成UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_callback_1` (`tenant_id`,`corp_id`,`event_fingerprint`),
  KEY `idx_wecom_callback_1` (`status`,`next_retry_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微验签回调事件幂等与处理状态';
