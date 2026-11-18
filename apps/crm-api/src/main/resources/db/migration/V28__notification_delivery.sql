-- 客户运营通知：逻辑通知、渠道投递当前状态与不可覆盖的最终尝试事实。
CREATE TABLE `crm_notification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `recipient_user_id` BIGINT UNSIGNED NOT NULL COMMENT '接收人CRM用户ID',
  `notification_type` VARCHAR(64) NOT NULL COMMENT '通知类型',
  `title` VARCHAR(200) NOT NULL COMMENT '标题（调用方负责脱敏）',
  `content_masked` VARCHAR(4000) NOT NULL COMMENT '脱敏正文',
  `source_type` VARCHAR(64) NOT NULL COMMENT '来源业务类型',
  `source_id` VARCHAR(128) NOT NULL COMMENT '来源业务ID',
  `dedupe_key` VARCHAR(128) NOT NULL COMMENT '业务去重键',
  `deep_link` VARCHAR(1024) NULL COMMENT 'CRM内相对路径',
  `channels` VARCHAR(128) NOT NULL COMMENT '请求渠道，逗号分隔且按名称排序',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `read_at` DATETIME(3) NULL COMMENT '首次已读时间UTC，不可回退',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_dedupe` (`tenant_id`,`recipient_user_id`,`dedupe_key`),
  KEY `idx_notification_recipient` (`tenant_id`,`recipient_user_id`,`created_at`,`id`),
  KEY `idx_notification_unread` (`tenant_id`,`recipient_user_id`,`read_at`,`id`),
  CONSTRAINT `fk_notification_recipient` FOREIGN KEY (`recipient_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户逻辑通知，内容事实不可覆盖';

CREATE TABLE `crm_notification_delivery` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `notification_id` BIGINT UNSIGNED NOT NULL COMMENT '逻辑通知',
  `recipient_user_id` BIGINT UNSIGNED NOT NULL COMMENT '接收人快照',
  `channel` VARCHAR(16) NOT NULL COMMENT 'IN_APP/WECOM_APP',
  `status` VARCHAR(24) NOT NULL COMMENT 'REQUESTED/PENDING/SENDING/RETRY_WAIT/SUCCEEDED/MOCKED/DISABLED/FAILED/UNKNOWN',
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计开始尝试次数',
  `automatic_retry_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前周期自动重试次数',
  `next_attempt_at` DATETIME(3) NULL COMMENT '下次允许投递时间UTC',
  `claim_owner` VARCHAR(128) NULL COMMENT 'worker租约持有者',
  `claim_expires_at` DATETIME(3) NULL COMMENT 'worker租约到期时间UTC',
  `last_started_at` DATETIME(3) NULL COMMENT '最近一次开始时间UTC',
  `trigger_type` VARCHAR(24) NOT NULL DEFAULT 'INITIAL' COMMENT '下次/当前尝试来源INITIAL/AUTOMATIC/MANUAL',
  `trigger_actor_user_id` BIGINT UNSIGNED NULL COMMENT '手工重试操作人，系统尝试为空',
  `provider` VARCHAR(32) NULL COMMENT 'IN_APP/OFFICIAL/MOCK/DISABLED',
  `provider_message_id` VARCHAR(255) NULL COMMENT '官方或明确标记的Mock消息ID',
  `last_error_masked` VARCHAR(512) NULL COMMENT '稳定脱敏错误，不含正文或Secret',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_delivery_channel` (`tenant_id`,`notification_id`,`channel`),
  KEY `idx_notification_delivery_work` (`tenant_id`,`status`,`next_attempt_at`,`id`),
  KEY `idx_notification_delivery_lease` (`status`,`claim_expires_at`,`id`),
  KEY `idx_notification_delivery_recipient` (`tenant_id`,`recipient_user_id`,`id`),
  CONSTRAINT `fk_notification_delivery_notification` FOREIGN KEY (`notification_id`) REFERENCES `crm_notification` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知渠道投递当前状态与租约';

CREATE TABLE `crm_notification_attempt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `delivery_id` BIGINT UNSIGNED NOT NULL COMMENT '渠道投递',
  `notification_id` BIGINT UNSIGNED NOT NULL COMMENT '逻辑通知',
  `attempt_no` INT UNSIGNED NOT NULL COMMENT '投递内累计尝试序号',
  `channel` VARCHAR(16) NOT NULL COMMENT 'IN_APP/WECOM_APP',
  `status` VARCHAR(24) NOT NULL COMMENT 'SUCCEEDED/MOCKED/DISABLED/RETRYABLE/FAILED/UNKNOWN',
  `provider` VARCHAR(32) NULL COMMENT 'IN_APP/OFFICIAL/MOCK/DISABLED',
  `provider_message_id` VARCHAR(255) NULL COMMENT '官方或明确标记的Mock消息ID',
  `error_masked` VARCHAR(512) NULL COMMENT '稳定脱敏错误',
  `trigger_type` VARCHAR(24) NOT NULL COMMENT 'INITIAL/AUTOMATIC/MANUAL/LEASE_RECOVERY',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '手工重试操作人，系统为空',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最终结果记录时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_attempt_no` (`tenant_id`,`delivery_id`,`attempt_no`),
  KEY `idx_notification_attempt_notification` (`tenant_id`,`notification_id`,`id`),
  CONSTRAINT `fk_notification_attempt_delivery` FOREIGN KEY (`delivery_id`) REFERENCES `crm_notification_delivery` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_notification_attempt_notification` FOREIGN KEY (`notification_id`) REFERENCES `crm_notification` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知投递最终尝试事实，只追加不覆盖';

CREATE TRIGGER `trg_notification_immutable` BEFORE UPDATE ON `crm_notification`
FOR EACH ROW
BEGIN
  IF NOT (OLD.`tenant_id` <=> NEW.`tenant_id` AND OLD.`recipient_user_id` <=> NEW.`recipient_user_id`
    AND OLD.`notification_type` <=> NEW.`notification_type` AND OLD.`title` <=> NEW.`title`
    AND OLD.`content_masked` <=> NEW.`content_masked` AND OLD.`source_type` <=> NEW.`source_type`
    AND OLD.`source_id` <=> NEW.`source_id` AND OLD.`dedupe_key` <=> NEW.`dedupe_key`
    AND OLD.`deep_link` <=> NEW.`deep_link` AND OLD.`channels` <=> NEW.`channels`
    AND OLD.`created_at` <=> NEW.`created_at`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Notification logical fields are immutable';
  END IF;
  IF OLD.`read_at` IS NOT NULL AND NOT (OLD.`read_at` <=> NEW.`read_at`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Notification read_at is immutable';
  END IF;
END;

CREATE TRIGGER `trg_notification_no_delete` BEFORE DELETE ON `crm_notification`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Notification is append only';

CREATE TRIGGER `trg_notification_attempt_no_update` BEFORE UPDATE ON `crm_notification_attempt`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Notification attempt is append only';

CREATE TRIGGER `trg_notification_attempt_no_delete` BEFORE DELETE ON `crm_notification_attempt`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Notification attempt is append only';
