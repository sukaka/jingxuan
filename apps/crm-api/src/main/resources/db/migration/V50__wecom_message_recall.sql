ALTER TABLE `crm_wecom_message`
  ADD COLUMN `recalled_at` DATETIME(3) NULL COMMENT '撤回生效时间UTC，正文与原始元数据不删除' AFTER `status`,
  ADD COLUMN `recall_message_id` VARCHAR(192) NULL COMMENT '导致撤回的官方控制消息ID' AFTER `recalled_at`;

CREATE TABLE `crm_wecom_recall_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企业ID',
  `recall_message_id` VARCHAR(192) NOT NULL COMMENT '撤回控制消息ID',
  `original_message_id` VARCHAR(192) NOT NULL COMMENT 'revoke.pre_msgid，原消息官方ID',
  `recall_seq` BIGINT UNSIGNED NOT NULL COMMENT '撤回控制消息归档序号',
  `external_userid` VARCHAR(192) NOT NULL COMMENT '撤回所在单聊外部身份',
  `staff_user_id` BIGINT UNSIGNED NOT NULL COMMENT '撤回所属员工',
  `sender_type` VARCHAR(32) NOT NULL COMMENT 'EMPLOYEE/CUSTOMER',
  `occurred_at` DATETIME(3) NOT NULL COMMENT '撤回发生时间UTC',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING_ORIGINAL' COMMENT 'PENDING_ORIGINAL/APPLIED',
  `original_message_ref` BIGINT UNSIGNED NULL COMMENT '补全后的内部消息引用',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件首次接收时间UTC',
  `applied_at` DATETIME(3) NULL COMMENT '原消息撤回生效时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '补全乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_recall_message` (`tenant_id`,`corp_id`,`recall_message_id`),
  UNIQUE KEY `uk_wecom_recall_original` (`tenant_id`,`corp_id`,`original_message_id`),
  KEY `idx_wecom_recall_pending` (`tenant_id`,`status`,`original_message_id`),
  CONSTRAINT `fk_wecom_recall_original_ref` FOREIGN KEY (`original_message_ref`) REFERENCES `crm_wecom_message` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_wecom_recall_staff` FOREIGN KEY (`staff_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微撤回控制事件，支持乱序补全且不删除原消息';
