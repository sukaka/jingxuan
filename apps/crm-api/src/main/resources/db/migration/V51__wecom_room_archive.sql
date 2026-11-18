CREATE TABLE `crm_wecom_room` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企业ID',
  `room_id` VARCHAR(192) NOT NULL COMMENT '企微官方群聊roomid，不是CRM客户ID',
  `display_name` VARCHAR(128) NULL COMMENT '管理员配置的显示名称，不冒充官方群名称',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE允许归档/DISABLED停止新归档',
  `source_evidence_ref` VARCHAR(255) NOT NULL COMMENT '成员清单来源证据引用，不保存证据正文',
  `membership_confirmed_at` DATETIME(3) NOT NULL COMMENT '完整成员清单人工确认时间UTC',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '配置操作员',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '完整成员清单与配置乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_room` (`tenant_id`,`corp_id`,`room_id`),
  KEY `idx_wecom_room_status` (`tenant_id`,`corp_id`,`status`),
  CONSTRAINT `fk_wecom_room_creator` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_wecom_room_status` CHECK (`status` IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微群聊归档配置，必须显式确认完整成员清单';

CREATE TABLE `crm_wecom_room_participant` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企业ID',
  `room_id` VARCHAR(192) NOT NULL COMMENT '企微官方群聊roomid',
  `wecom_userid` VARCHAR(192) NOT NULL COMMENT '企微成员账号或external_userid，不能填写昵称/手机号',
  `participant_type` VARCHAR(16) NOT NULL COMMENT 'STAFF/EXTERNAL，由已核验身份映射确定',
  `staff_user_id` BIGINT UNSIGNED NULL COMMENT '员工参与人的当前CRM员工映射',
  `external_userid` VARCHAR(192) NULL COMMENT '外部参与人的企微external_userid',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REMOVED；退群不删除历史',
  `confirmed_at` DATETIME(3) NOT NULL COMMENT '本次成员确认时间UTC',
  `removed_at` DATETIME(3) NULL COMMENT '确认退群时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '成员记录乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_room_participant` (`tenant_id`,`corp_id`,`room_id`,`wecom_userid`),
  KEY `idx_wecom_room_staff` (`tenant_id`,`corp_id`,`staff_user_id`,`status`,`room_id`),
  KEY `idx_wecom_room_external` (`tenant_id`,`corp_id`,`external_userid`,`status`),
  CONSTRAINT `fk_wecom_room_participant_room` FOREIGN KEY (`tenant_id`,`corp_id`,`room_id`) REFERENCES `crm_wecom_room` (`tenant_id`,`corp_id`,`room_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_wecom_room_participant_staff` FOREIGN KEY (`staff_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_wecom_room_participant_type` CHECK (`participant_type` IN ('STAFF','EXTERNAL')),
  CONSTRAINT `ck_wecom_room_participant_status` CHECK (`status` IN ('ACTIVE','REMOVED')),
  CONSTRAINT `ck_wecom_room_participant_identity` CHECK ((`participant_type`='STAFF' AND `staff_user_id` IS NOT NULL AND `external_userid` IS NULL) OR (`participant_type`='EXTERNAL' AND `staff_user_id` IS NULL AND `external_userid` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微群聊已确认参与人，退群保留历史';

ALTER TABLE `crm_wecom_message`
  MODIFY COLUMN `external_userid` VARCHAR(192) NULL COMMENT '单聊外部联系身份；群聊为空，不能任选群成员填充',
  MODIFY COLUMN `staff_user_id` BIGINT UNSIGNED NULL COMMENT '单聊归属被存档员工；群聊为空',
  MODIFY COLUMN `conversation_id` VARCHAR(192) NULL COMMENT '单聊会话ID或群聊roomid',
  ADD COLUMN `conversation_type` VARCHAR(16) NOT NULL DEFAULT 'DIRECT' COMMENT 'DIRECT单聊/ROOM群聊' AFTER `staff_user_id`,
  ADD COLUMN `sender_wecom_userid` VARCHAR(192) NULL COMMENT '实际发送人的企微成员账号或external_userid' AFTER `sender_type`,
  ADD COLUMN `sender_staff_user_id` BIGINT UNSIGNED NULL COMMENT '实际发送员工的CRM映射，客户发送时为空' AFTER `sender_wecom_userid`,
  ADD COLUMN `room_roster_version` BIGINT UNSIGNED NULL COMMENT '群消息采集时完整成员清单版本' AFTER `consent_evidence_ref`,
  ADD CONSTRAINT `fk_wecom_message_sender_staff` FOREIGN KEY (`sender_staff_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD KEY `idx_wecom_message_conversation` (`tenant_id`,`conversation_type`,`conversation_id`,`sent_at`,`id`),
  ADD CONSTRAINT `ck_wecom_message_conversation_type` CHECK (`conversation_type` IN ('DIRECT','ROOM')),
  ADD CONSTRAINT `ck_wecom_message_conversation_identity` CHECK ((`conversation_type`='DIRECT' AND `external_userid` IS NOT NULL AND `staff_user_id` IS NOT NULL AND `room_roster_version` IS NULL) OR (`conversation_type`='ROOM' AND `external_userid` IS NULL AND `staff_user_id` IS NULL AND `customer_id` IS NULL AND `conversation_id` IS NOT NULL AND `room_roster_version` IS NOT NULL));

UPDATE `crm_wecom_message` m
LEFT JOIN `crm_wecom_staff_account` sa ON sa.tenant_id=m.tenant_id AND sa.corp_id=m.corp_id AND sa.staff_user_id=m.staff_user_id
SET m.sender_wecom_userid=CASE WHEN m.sender_type='CUSTOMER' THEN m.external_userid ELSE sa.wecom_userid END,
    m.sender_staff_user_id=CASE WHEN m.sender_type='EMPLOYEE' THEN m.staff_user_id ELSE NULL END;

ALTER TABLE `crm_wecom_recall_event`
  MODIFY COLUMN `external_userid` VARCHAR(192) NULL COMMENT '单聊外部身份；群聊为空',
  MODIFY COLUMN `staff_user_id` BIGINT UNSIGNED NULL COMMENT '单聊归属员工；群聊为空',
  ADD COLUMN `conversation_type` VARCHAR(16) NOT NULL DEFAULT 'DIRECT' COMMENT 'DIRECT/ROOM' AFTER `staff_user_id`,
  ADD COLUMN `conversation_id` VARCHAR(192) NULL COMMENT '单聊或群聊会话ID' AFTER `conversation_type`,
  ADD COLUMN `sender_wecom_userid` VARCHAR(192) NULL COMMENT '撤回操作人企微ID' AFTER `conversation_id`,
  ADD CONSTRAINT `ck_wecom_recall_conversation_type` CHECK (`conversation_type` IN ('DIRECT','ROOM'));

UPDATE `crm_wecom_recall_event` e
JOIN `crm_wecom_staff_account` sa ON sa.tenant_id=e.tenant_id AND sa.corp_id=e.corp_id AND sa.staff_user_id=e.staff_user_id
SET e.conversation_id=CASE WHEN e.sender_type='CUSTOMER' THEN e.external_userid ELSE sa.wecom_userid END,
    e.sender_wecom_userid=CASE WHEN e.sender_type='CUSTOMER' THEN e.external_userid ELSE sa.wecom_userid END;
