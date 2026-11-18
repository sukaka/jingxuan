CREATE TABLE `crm_ai_chat_delegation_policy` (
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '租户全局托管开关，默认关闭',
  `emergency_stopped` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '紧急停用，优先于所有其他开关',
  `min_interval_seconds` INT UNSIGNED NOT NULL DEFAULT 60 COMMENT '同客户两次发送尝试最小间隔秒数',
  `daily_limit` INT UNSIGNED NOT NULL DEFAULT 10 COMMENT '同客户每自然日允许进入外部发送的来源消息数',
  `allowed_start` TIME NOT NULL DEFAULT '09:00:00' COMMENT '允许发送时段开始，含开始',
  `allowed_end` TIME NOT NULL DEFAULT '21:00:00' COMMENT '允许发送时段结束，不含结束',
  `zone_id` VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '频控和时段的明确时区',
  `blocked_terms_json` JSON NOT NULL COMMENT '额外禁发普通词，不执行正则',
  `updated_by` BIGINT UNSIGNED NOT NULL COMMENT '最近配置修改人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '整组策略乐观锁版本',
  PRIMARY KEY (`tenant_id`),
  CONSTRAINT `fk_ai_delegation_policy_actor` FOREIGN KEY (`updated_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI聊天托管租户策略；不包含模型密钥';

CREATE TABLE `crm_ai_chat_delegation_scope` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `scope_type` VARCHAR(16) NOT NULL COMMENT 'TEAM/STAFF',
  `scope_id` BIGINT UNSIGNED NOT NULL COMMENT '团队或管家用户ID',
  `enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '该团队或管家是否显式允许托管',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_delegation_scope` (`tenant_id`,`scope_type`,`scope_id`),
  KEY `idx_ai_delegation_scope_staff` (`tenant_id`,`scope_id`,`scope_type`),
  CONSTRAINT `fk_ai_delegation_scope_policy` FOREIGN KEY (`tenant_id`) REFERENCES `crm_ai_chat_delegation_policy` (`tenant_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI聊天托管团队与管家开关；无记录视为关闭';

CREATE TABLE `crm_ai_chat_delegation_customer` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT 'CRM客户唯一ID',
  `mode` VARCHAR(24) NOT NULL COMMENT 'ACTIVE/PAUSED/HUMAN_TAKEOVER',
  `reason` VARCHAR(1000) NOT NULL COMMENT '人工暂停、接管或恢复理由',
  `updated_by` BIGINT UNSIGNED NOT NULL COMMENT '操作人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_delegation_customer` (`tenant_id`,`customer_id`),
  CONSTRAINT `fk_ai_delegation_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_delegation_customer_actor` FOREIGN KEY (`updated_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户级AI托管暂停与人工接管；无记录为ACTIVE';

CREATE TABLE `crm_ai_chat_delegation_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `source_message_id` BIGINT UNSIGNED NOT NULL COMMENT '合法企微归档来源消息',
  `customer_id` BIGINT UNSIGNED NULL COMMENT '已人工匹配的CRM客户',
  `staff_user_id` BIGINT UNSIGNED NOT NULL COMMENT '归档归属管家',
  `team_id` BIGINT UNSIGNED NULL COMMENT '接收时客户团队快照',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企业ID',
  `external_userid` VARCHAR(192) NOT NULL COMMENT '企微外部联系身份',
  `conversation_id` VARCHAR(128) NULL COMMENT '会话标识',
  `status` VARCHAR(32) NOT NULL COMMENT 'QUEUED/DEFERRED/GENERATING/READY_TO_SEND/SENDING/SENT/MOCKED/DISABLED/SKIPPED/FAILED/RETRY_GENERATE/RETRY_SEND/UNKNOWN',
  `reason_masked` VARCHAR(500) NULL COMMENT '稳定阻断码或脱敏错误，不含聊天正文',
  `reply_cipher` TEXT NULL COMMENT 'AI文本AES-GCM密文，仅在服务端解密发送',
  `intent` VARCHAR(48) NULL COMMENT '经独立门禁允许的意图',
  `risk_flags_json` JSON NULL COMMENT 'AI风险标记快照；非空即禁止自动发送',
  `ai_provider` VARCHAR(48) NULL COMMENT '实际模型供应商',
  `ai_model` VARCHAR(128) NULL COMMENT '实际模型或Mock版本',
  `send_provider` VARCHAR(32) NULL COMMENT 'OFFICIAL/MOCK/DISABLED',
  `provider_message_id` VARCHAR(192) NULL COMMENT '官方消息引用；Mock始终为空',
  `send_attempted_at` DATETIME(3) NULL COMMENT '最近实际调用外部发送前记录时间UTC，用于频控',
  `generation_attempts` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'AI生成尝试次数',
  `send_attempts` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '发送尝试次数',
  `next_attempt_at` DATETIME(3) NULL COMMENT '下次允许执行时间UTC；UNKNOWN必须为空',
  `lease_owner` VARCHAR(64) NULL COMMENT '当前执行实例',
  `lease_expires_at` DATETIME(3) NULL COMMENT '当前执行租约到期UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_delegation_source` (`tenant_id`,`source_message_id`),
  KEY `idx_ai_delegation_generate` (`status`,`next_attempt_at`,`lease_expires_at`,`id`),
  KEY `idx_ai_delegation_scope` (`tenant_id`,`customer_id`,`staff_user_id`,`id`),
  KEY `idx_ai_delegation_rate` (`tenant_id`,`customer_id`,`send_attempted_at`,`id`),
  CONSTRAINT `fk_ai_delegation_run_message` FOREIGN KEY (`source_message_id`) REFERENCES `crm_wecom_message` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_delegation_run_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_ai_delegation_unknown_no_retry` CHECK (`status` <> 'UNKNOWN' OR `next_attempt_at` IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI聊天托管处理事实；UNKNOWN不自动重发';

CREATE TABLE `crm_ai_chat_delegation_attempt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `run_id` BIGINT UNSIGNED NOT NULL COMMENT '托管处理流水',
  `phase` VARCHAR(24) NOT NULL COMMENT 'RECEIVE/GENERATE/COMPLIANCE/SEND/LEASE_RECOVERY',
  `result` VARCHAR(32) NOT NULL COMMENT '各阶段最终结果',
  `provider` VARCHAR(48) NULL COMMENT '模型或发送提供方',
  `provider_message_id` VARCHAR(192) NULL COMMENT '官方消息引用；Mock为空',
  `error_masked` VARCHAR(500) NULL COMMENT '稳定阻断码或脱敏错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '追加时间UTC',
  PRIMARY KEY (`id`),
  KEY `idx_ai_delegation_attempt_run` (`tenant_id`,`run_id`,`id`),
  CONSTRAINT `fk_ai_delegation_attempt_run` FOREIGN KEY (`run_id`) REFERENCES `crm_ai_chat_delegation_run` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI托管不可覆盖的阶段尝试日志';

CREATE TRIGGER `trg_ai_delegation_attempt_no_update` BEFORE UPDATE ON `crm_ai_chat_delegation_attempt`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI delegation attempt is append only';
CREATE TRIGGER `trg_ai_delegation_attempt_no_delete` BEFORE DELETE ON `crm_ai_chat_delegation_attempt`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI delegation attempt is append only';
