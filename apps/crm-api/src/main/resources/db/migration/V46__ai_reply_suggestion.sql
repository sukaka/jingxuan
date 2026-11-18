CREATE TABLE `crm_ai_reply_suggestion` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `template_id` BIGINT UNSIGNED NOT NULL COMMENT 'AI候选来源话术模板',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT 'CRM客户唯一ID',
  `employee_id` BIGINT UNSIGNED NOT NULL COMMENT '候选所属员工',
  `scene` VARCHAR(64) NOT NULL COMMENT '服务端话术场景',
  `facts_snapshot_json` JSON NOT NULL COMMENT '生成时只读事实快照，敏感字段脱敏',
  `request_hash` CHAR(64) NOT NULL COMMENT '生成请求SHA256',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '生成幂等键',
  `reply_cipher` TEXT NOT NULL COMMENT 'AI原始回复AES-GCM密文，永不更新',
  `reason_masked` VARCHAR(500) NOT NULL COMMENT '简短推荐理由，已脱敏',
  `used_fact_codes_json` JSON NOT NULL COMMENT '白名单事实引用编码',
  `provider` VARCHAR(48) NOT NULL COMMENT '模型供应商，mock明确标识',
  `model_name` VARCHAR(128) NOT NULL COMMENT '实际模型名称',
  `prompt_version` VARCHAR(48) NOT NULL COMMENT '提示词契约版本',
  `confidence` DECIMAL(5,4) NOT NULL COMMENT '模型候选置信度，不代表事实准确性',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PROPOSED' COMMENT 'PROPOSED/ACCEPTED/REJECTED',
  `accepted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '员工是否已人工采纳',
  `edited` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '采纳内容是否经过人工编辑',
  `final_content_cipher` TEXT NULL COMMENT '员工采纳最终回复AES-GCM密文',
  `decision_reason` VARCHAR(500) NULL COMMENT '人工采纳或驳回原因，已脱敏',
  `decision_by` BIGINT UNSIGNED NULL COMMENT '人工处理员工',
  `decision_at` DATETIME(3) NULL COMMENT '人工处理时间UTC',
  `decision_key` VARCHAR(128) NULL COMMENT '人工处理幂等键',
  `decision_hash` CHAR(64) NULL COMMENT '人工处理请求SHA256',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '人工处理乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_reply_generation_key` (`tenant_id`,`employee_id`,`idempotency_key`),
  UNIQUE KEY `uk_ai_reply_decision_key` (`tenant_id`,`employee_id`,`decision_key`),
  KEY `idx_ai_reply_employee_customer` (`tenant_id`,`employee_id`,`customer_id`,`created_at`),
  KEY `idx_ai_reply_customer_status` (`tenant_id`,`customer_id`,`status`),
  CONSTRAINT `fk_ai_reply_template` FOREIGN KEY (`template_id`) REFERENCES `crm_quick_reply_template` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_reply_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_reply_employee` FOREIGN KEY (`employee_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_reply_decision_by` FOREIGN KEY (`decision_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工侧AI回复候选，必须人工采纳，不自动发送';

ALTER TABLE `crm_quick_reply_usage`
  ADD COLUMN `ai_suggestion_id` BIGINT UNSIGNED NULL COMMENT '已人工采纳的AI回复候选' AFTER `template_id`,
  ADD CONSTRAINT `fk_quick_reply_usage_ai_suggestion` FOREIGN KEY (`ai_suggestion_id`) REFERENCES `crm_ai_reply_suggestion` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD KEY `idx_quick_reply_usage_ai_suggestion` (`tenant_id`,`ai_suggestion_id`);

UPDATE `crm_quick_reply_template` SET `ai_enabled`=1
WHERE `code` IN ('PROJECT_REMAINING','APPOINTMENT_CONFIRM','FOLLOWUP_GREETING');
