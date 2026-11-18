ALTER TABLE `crm_wecom_recall_event`
  DROP CONSTRAINT `ck_wecom_recall_conversation_type`,
  DROP COLUMN `sender_wecom_userid`,
  DROP COLUMN `conversation_id`,
  DROP COLUMN `conversation_type`,
  MODIFY COLUMN `staff_user_id` BIGINT UNSIGNED NOT NULL COMMENT '撤回所属员工',
  MODIFY COLUMN `external_userid` VARCHAR(192) NOT NULL COMMENT '撤回所在单聊外部身份';

ALTER TABLE `crm_wecom_message`
  DROP CONSTRAINT `ck_wecom_message_conversation_identity`,
  DROP CONSTRAINT `ck_wecom_message_conversation_type`,
  DROP CONSTRAINT `fk_wecom_message_sender_staff`,
  DROP KEY `idx_wecom_message_conversation`,
  DROP COLUMN `room_roster_version`,
  DROP COLUMN `sender_staff_user_id`,
  DROP COLUMN `sender_wecom_userid`,
  DROP COLUMN `conversation_type`,
  MODIFY COLUMN `conversation_id` VARCHAR(128) NULL COMMENT '会话ID',
  MODIFY COLUMN `staff_user_id` BIGINT UNSIGNED NOT NULL COMMENT '归属被存档员工',
  MODIFY COLUMN `external_userid` VARCHAR(192) NOT NULL COMMENT '外部联系身份';

DROP TABLE `crm_wecom_room_participant`;
DROP TABLE `crm_wecom_room`;
