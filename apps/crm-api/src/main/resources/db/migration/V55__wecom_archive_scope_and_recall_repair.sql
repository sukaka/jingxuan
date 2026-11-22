-- H-BUG-08: 企微联系人同步与回调事件租约字段及回收索引
ALTER TABLE `crm_wecom_contact_sync_run`
  ADD COLUMN `lease_until` DATETIME(3) NULL COMMENT '同步租约到期UTC' AFTER `started_at`,
  ADD KEY `idx_wecom_sync_run_lease` (`tenant_id`, `corp_id`, `status`, `lease_until`);

ALTER TABLE `crm_wecom_callback_event`
  ADD COLUMN `lease_until` DATETIME(3) NULL COMMENT '处理租约到期UTC' AFTER `next_retry_at`,
  ADD KEY `idx_wecom_callback_lease` (`tenant_id`, `status`, `lease_until`, `id`);

-- H-BUG-12: crm_wecom_message 补充 archive_scope 并按归档范围重建序列唯一键
ALTER TABLE `crm_wecom_message`
  ADD COLUMN `archive_scope` VARCHAR(128) NOT NULL DEFAULT 'MOCK_ARCHIVE' COMMENT '归档范围(MOCK_ARCHIVE/OFFICIAL_ARCHIVE)' AFTER `archive_seq`,
  DROP INDEX `uk_wecom_message_2`,
  ADD UNIQUE KEY `uk_wecom_message_2` (`tenant_id`, `corp_id`, `archive_scope`, `archive_seq`);

UPDATE `crm_wecom_message` m
SET m.`archive_scope` = 'OFFICIAL_ARCHIVE'
WHERE EXISTS (
  SELECT 1 FROM `crm_wecom_cursor` c
  WHERE c.`tenant_id` = m.`tenant_id` AND c.`corp_id` = m.`corp_id` AND c.`archive_scope` = 'OFFICIAL_ARCHIVE'
)
AND NOT EXISTS (
  SELECT 1 FROM `crm_wecom_cursor` c2
  WHERE c2.`tenant_id` = m.`tenant_id` AND c2.`corp_id` = m.`corp_id` AND c2.`archive_scope` = 'MOCK_ARCHIVE'
);

-- H-BUG-11: 使用 LEFT JOIN 修复 V51 对 DIRECT 撤回事件 conversation_id 与客户 sender_wecom_userid 的回填
UPDATE `crm_wecom_recall_event` e
LEFT JOIN `crm_wecom_staff_account` sa
  ON sa.`tenant_id` = e.`tenant_id` AND sa.`corp_id` = e.`corp_id` AND sa.`staff_user_id` = e.`staff_user_id`
SET e.`conversation_id` = e.`external_userid`,
    e.`sender_wecom_userid` = CASE
      WHEN e.`sender_type` = 'CUSTOMER' THEN e.`external_userid`
      ELSE COALESCE(NULLIF(e.`sender_wecom_userid`, ''), sa.`wecom_userid`)
    END
WHERE e.`conversation_type` = 'DIRECT';
