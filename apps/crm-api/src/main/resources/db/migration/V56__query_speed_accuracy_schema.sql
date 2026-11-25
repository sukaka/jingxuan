-- V56: 查询速度与准确性架构治理（跨表列宽对齐、Wave 2 高收益冗余列与安全回填、P0/P1 索引补齐与列序修正、重复索引清理）。
-- 所有变更与 V1..V55 保持严格兼容，不破坏历史账本与外键约束；回填 DML 显式保留原 updated_at 与 version。

-- ============================================================================
-- 1. 跨表列宽对齐（与 crm_wecom_message.conversation_id / crm_wecom_room_participant.wecom_userid 对齐）
-- ============================================================================
ALTER TABLE `crm_ai_job`
  MODIFY COLUMN `conversation_id` VARCHAR(192) NOT NULL COMMENT '企微会话标识(与crm_wecom_message对齐)';

ALTER TABLE `crm_ai_chat_delegation_run`
  MODIFY COLUMN `conversation_id` VARCHAR(192) NULL COMMENT '会话标识(与crm_wecom_message对齐)',
  ADD KEY `idx_ai_delegation_customer_status` (`tenant_id`, `customer_id`, `status`, `id`);

ALTER TABLE `crm_wecom_staff_account`
  MODIFY COLUMN `wecom_userid` VARCHAR(192) NOT NULL COMMENT '企微成员userid',
  ADD KEY `idx_wecom_staff_user_msg` (`tenant_id`, `staff_user_id`, `message_enabled`, `corp_id`);

ALTER TABLE `crm_wecom_callback_event`
  MODIFY COLUMN `wecom_userid` VARCHAR(192) NULL COMMENT '企微成员userid',
  ADD KEY `idx_wecom_callback_pending` (`tenant_id`, `status`, `next_retry_at`, `id`);

-- ============================================================================
-- 2. Wave 2 高收益结构增量列 + 安全回填 + 配套索引
-- ============================================================================

-- 2.1 crm_customer.next_followup_at：冗余最新有效跟进的下次跟进时间，解决到期跟进筛选口径与子查询慢问题
ALTER TABLE `crm_customer`
  ADD COLUMN `next_followup_at` DATETIME(3) NULL COMMENT '最新有效跟进的下次跟进时间UTC(冗余加速)' AFTER `last_followup_at`;

UPDATE `crm_customer` c
INNER JOIN (
  SELECT `tenant_id`, `customer_id`, MAX(`next_followup_at`) AS `max_next_followup_at`
  FROM `crm_followup`
  WHERE `deleted` = 0 AND `next_followup_at` IS NOT NULL
  GROUP BY `tenant_id`, `customer_id`
) f ON f.`tenant_id` = c.`tenant_id` AND f.`customer_id` = c.`id`
SET c.`next_followup_at` = f.`max_next_followup_at`,
    c.`updated_at` = c.`updated_at`;

ALTER TABLE `crm_customer`
  ADD KEY `idx_customer_next_followup` (`tenant_id`, `deleted`, `next_followup_at`, `id`),
  ADD KEY `idx_customer_team` (`tenant_id`, `deleted`, `team_id`, `id`),
  ADD KEY `idx_customer_created` (`tenant_id`, `deleted`, `created_at`),
  ADD KEY `idx_customer_status` (`tenant_id`, `deleted`, `customer_status`, `id`),
  ADD KEY `idx_customer_city_tier` (`tenant_id`, `deleted`, `city_code`, `tier_code`, `id`);

-- 2.2 crm_youzan_event.external_customer_id / campaign_id：消除 LONGTEXT 实时 JSON_EXTRACT
ALTER TABLE `crm_youzan_event`
  ADD COLUMN `external_customer_id` VARCHAR(128) NULL COMMENT '从报文抽取的外部客户ID' AFTER `external_order_no`,
  ADD COLUMN `campaign_id` BIGINT UNSIGNED NULL COMMENT '从报文抽取的活动ID' AFTER `external_customer_id`;

UPDATE `crm_youzan_event`
SET
  `external_customer_id` = CASE
    WHEN JSON_VALID(`payload_masked_json`) = 1
      AND JSON_TYPE(JSON_EXTRACT(`payload_masked_json`, '$.external_customer_id')) IN ('STRING', 'INTEGER', 'UNSIGNED INTEGER')
      AND TRIM(JSON_UNQUOTE(JSON_EXTRACT(`payload_masked_json`, '$.external_customer_id'))) NOT IN ('', 'null')
      AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(`payload_masked_json`, '$.external_customer_id')))) <= 128
    THEN TRIM(JSON_UNQUOTE(JSON_EXTRACT(`payload_masked_json`, '$.external_customer_id')))
    ELSE NULL
  END,
  `campaign_id` = CASE
    WHEN JSON_VALID(`payload_masked_json`) = 1
      AND JSON_TYPE(JSON_EXTRACT(`payload_masked_json`, '$.campaign_id')) IN ('STRING', 'INTEGER', 'UNSIGNED INTEGER')
      AND TRIM(JSON_UNQUOTE(JSON_EXTRACT(`payload_masked_json`, '$.campaign_id'))) REGEXP '^[1-9][0-9]*$'
      AND (
        CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(`payload_masked_json`, '$.campaign_id')))) < 20
        OR (
          CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(`payload_masked_json`, '$.campaign_id')))) = 20
          AND TRIM(JSON_UNQUOTE(JSON_EXTRACT(`payload_masked_json`, '$.campaign_id'))) <= '18446744073709551615'
        )
      )
    THEN CAST(TRIM(JSON_UNQUOTE(JSON_EXTRACT(`payload_masked_json`, '$.campaign_id'))) AS UNSIGNED)
    ELSE NULL
  END,
  `updated_at` = `updated_at`
WHERE JSON_VALID(`payload_masked_json`) = 1;

ALTER TABLE `crm_youzan_event`
  ADD KEY `idx_youzan_event_ext_customer` (`tenant_id`, `shop_id`, `external_customer_id`),
  ADD KEY `idx_youzan_event_campaign` (`tenant_id`, `shop_id`, `campaign_id`, `external_order_no`),
  ADD KEY `idx_youzan_event_received` (`tenant_id`, `shop_id`, `received_at`);

-- 2.3 crm_risk_case.customer_id / order_id：消除 9 表 REGEXP/CAST/JSON_EXTRACT 关联（按 5 类来源逐源安全回填）
ALTER TABLE `crm_risk_case`
  ADD COLUMN `customer_id` BIGINT UNSIGNED NULL COMMENT '关联客户ID冗余加速' AFTER `source_ref`,
  ADD COLUMN `order_id` BIGINT UNSIGNED NULL COMMENT '关联订单ID冗余加速' AFTER `customer_id`;

-- 来源 1: ORDER
UPDATE `crm_risk_case` r
INNER JOIN `crm_order` o
  ON o.`tenant_id` = r.`tenant_id`
  AND o.`id` = CASE
    WHEN r.`source_ref` REGEXP '^[1-9][0-9]*$'
      AND (CHAR_LENGTH(r.`source_ref`) < 20 OR (CHAR_LENGTH(r.`source_ref`) = 20 AND r.`source_ref` <= '18446744073709551615'))
    THEN CAST(r.`source_ref` AS UNSIGNED)
    ELSE NULL
  END
SET r.`order_id` = COALESCE(r.`order_id`, o.`id`),
    r.`customer_id` = COALESCE(r.`customer_id`, o.`customer_id`),
    r.`updated_at` = r.`updated_at`
WHERE r.`source_type` = 'ORDER'
  AND (r.`customer_id` IS NULL OR r.`order_id` IS NULL);

-- 来源 2: YOUZAN_SYNC_ISSUE（复用 2.2 已安全回填的 crm_youzan_event.external_customer_id）
UPDATE `crm_risk_case` r
INNER JOIN `crm_youzan_sync_issue` yi
  ON yi.`tenant_id` = r.`tenant_id`
  AND yi.`id` = CASE
    WHEN r.`source_ref` REGEXP '^[1-9][0-9]*$'
      AND (CHAR_LENGTH(r.`source_ref`) < 20 OR (CHAR_LENGTH(r.`source_ref`) = 20 AND r.`source_ref` <= '18446744073709551615'))
    THEN CAST(r.`source_ref` AS UNSIGNED)
    ELSE NULL
  END
INNER JOIN `crm_youzan_event` ye
  ON ye.`tenant_id` = yi.`tenant_id` AND ye.`id` = yi.`event_id`
LEFT JOIN `crm_youzan_order_map` yom
  ON yom.`tenant_id` = ye.`tenant_id` AND yom.`shop_id` = ye.`shop_id` AND yom.`external_order_no` = ye.`external_order_no`
LEFT JOIN `crm_order` o
  ON o.`tenant_id` = r.`tenant_id` AND o.`id` = COALESCE(ye.`order_id`, yom.`crm_order_id`)
LEFT JOIN `crm_youzan_customer_link` ycl
  ON ycl.`tenant_id` = ye.`tenant_id` AND ycl.`shop_id` = ye.`shop_id`
  AND ye.`external_customer_id` IS NOT NULL AND ycl.`external_customer_id` = ye.`external_customer_id`
SET r.`order_id` = COALESCE(r.`order_id`, o.`id`),
    r.`customer_id` = COALESCE(r.`customer_id`, o.`customer_id`, ycl.`customer_id`),
    r.`updated_at` = r.`updated_at`
WHERE r.`source_type` = 'YOUZAN_SYNC_ISSUE'
  AND (o.`id` IS NOT NULL OR ycl.`customer_id` IS NOT NULL)
  AND (r.`customer_id` IS NULL OR r.`order_id` IS NULL);

-- 来源 3: REFUND
UPDATE `crm_risk_case` r
INNER JOIN `crm_refund` rf
  ON rf.`tenant_id` = r.`tenant_id`
  AND rf.`id` = CASE
    WHEN r.`source_ref` REGEXP '^[1-9][0-9]*$'
      AND (CHAR_LENGTH(r.`source_ref`) < 20 OR (CHAR_LENGTH(r.`source_ref`) = 20 AND r.`source_ref` <= '18446744073709551615'))
    THEN CAST(r.`source_ref` AS UNSIGNED)
    ELSE NULL
  END
LEFT JOIN `crm_order` o
  ON o.`tenant_id` = rf.`tenant_id` AND o.`id` = rf.`order_id`
SET r.`order_id` = COALESCE(r.`order_id`, o.`id`),
    r.`customer_id` = COALESCE(r.`customer_id`, o.`customer_id`, rf.`customer_id`),
    r.`updated_at` = r.`updated_at`
WHERE r.`source_type` = 'REFUND'
  AND (r.`customer_id` IS NULL OR r.`order_id` IS NULL);

-- 来源 4: ENTITLEMENT
UPDATE `crm_risk_case` r
INNER JOIN `crm_entitlement` e
  ON e.`tenant_id` = r.`tenant_id`
  AND e.`id` = CASE
    WHEN r.`source_ref` REGEXP '^[1-9][0-9]*$'
      AND (CHAR_LENGTH(r.`source_ref`) < 20 OR (CHAR_LENGTH(r.`source_ref`) = 20 AND r.`source_ref` <= '18446744073709551615'))
    THEN CAST(r.`source_ref` AS UNSIGNED)
    ELSE NULL
  END
LEFT JOIN `crm_order_item` oi
  ON oi.`tenant_id` = e.`tenant_id` AND oi.`id` = e.`order_item_id`
LEFT JOIN `crm_order` o
  ON o.`tenant_id` = oi.`tenant_id` AND o.`id` = oi.`order_id`
SET r.`order_id` = COALESCE(r.`order_id`, o.`id`),
    r.`customer_id` = COALESCE(r.`customer_id`, o.`customer_id`, e.`customer_id`),
    r.`updated_at` = r.`updated_at`
WHERE r.`source_type` = 'ENTITLEMENT'
  AND (r.`customer_id` IS NULL OR r.`order_id` IS NULL);

-- 来源 5: REDEMPTION
UPDATE `crm_risk_case` r
INNER JOIN `crm_redemption` rd
  ON rd.`tenant_id` = r.`tenant_id`
  AND rd.`id` = CASE
    WHEN r.`source_ref` REGEXP '^[1-9][0-9]*$'
      AND (CHAR_LENGTH(r.`source_ref`) < 20 OR (CHAR_LENGTH(r.`source_ref`) = 20 AND r.`source_ref` <= '18446744073709551615'))
    THEN CAST(r.`source_ref` AS UNSIGNED)
    ELSE NULL
  END
SET r.`customer_id` = COALESCE(r.`customer_id`, rd.`customer_id`),
    r.`updated_at` = r.`updated_at`
WHERE r.`source_type` = 'REDEMPTION'
  AND r.`customer_id` IS NULL;

ALTER TABLE `crm_risk_case`
  ADD KEY `idx_risk_case_customer` (`tenant_id`, `customer_id`, `id`),
  ADD KEY `idx_risk_case_created` (`tenant_id`, `created_at`, `id`);

-- ============================================================================
-- 3. 客户关联表反向索引
-- ============================================================================
ALTER TABLE `crm_customer_tag`
  ADD KEY `idx_customer_tag_reverse` (`tenant_id`, `tag_code`, `customer_id`);

ALTER TABLE `crm_customer_identity`
  ADD KEY `idx_customer_identity_ext` (`tenant_id`, `external_id`, `customer_id`);

-- ============================================================================
-- 4. 报表 7 大事实表时间窗与覆盖索引
-- ============================================================================
ALTER TABLE `crm_followup`
  ADD KEY `idx_followup_contact_at` (`tenant_id`, `deleted`, `contact_at`, `customer_id`),
  ADD KEY `idx_followup_created_by` (`tenant_id`, `deleted`, `created_by`, `id`);

ALTER TABLE `crm_appointment_history`
  ADD KEY `idx_appointment_history_status_time` (`tenant_id`, `to_status`, `created_at`, `appointment_id`);

ALTER TABLE `crm_redemption`
  ADD KEY `idx_redemption_status_posted` (`tenant_id`, `status`, `posted_at`, `customer_id`, `institution_id`),
  ADD KEY `idx_redemption_status_id` (`tenant_id`, `status`, `id`);

ALTER TABLE `crm_redemption_line`
  ADD KEY `idx_redemption_line_project` (`tenant_id`, `project_id`, `redemption_id`);

ALTER TABLE `crm_redemption_reversal`
  ADD KEY `idx_redemption_reversal_redemption` (`tenant_id`, `redemption_id`, `status`);

ALTER TABLE `crm_payment_txn`
  ADD KEY `idx_payment_txn_status_confirmed` (`tenant_id`, `status`, `confirmed_at`, `order_id`, `receiver_type`, `amount_fen`),
  DROP INDEX `idx_payment_txn_1`,
  ADD KEY `idx_payment_txn_1` (`tenant_id`, `order_id`, `status`, `amount_fen`);

ALTER TABLE `crm_refund_payment`
  ADD KEY `idx_refund_payment_status_succeeded` (`tenant_id`, `status`, `succeeded_at`, `refund_id`, `amount_fen`),
  ADD KEY `idx_refund_payment_provider_no` (`provider`, `external_refund_no`);

ALTER TABLE `crm_refund`
  ADD KEY `idx_refund_status_id` (`tenant_id`, `status`, `id`);

ALTER TABLE `crm_order`
  ADD KEY `idx_order_status_paid_at` (`tenant_id`, `status`, `paid_at`),
  ADD KEY `idx_order_owner_sale_paid` (`tenant_id`, `owner_at_sale_id`, `paid_at`),
  ADD KEY `idx_order_ordered_at` (`tenant_id`, `ordered_at`, `status`, `customer_id`),
  ADD KEY `idx_order_status_id` (`tenant_id`, `status`, `id`);

ALTER TABLE `crm_order_item`
  ADD KEY `idx_order_item_sku` (`tenant_id`, `sku_id`, `order_id`);

-- fk_entitlement_ledg_entitlement_id 由单列隐式索引支撑，不依赖 idx_entitlement_ledg_1
ALTER TABLE `crm_entitlement_ledger`
  DROP INDEX `idx_entitlement_ledg_1`,
  ADD KEY `idx_entitlement_ledg_1` (`tenant_id`, `entitlement_id`, `delta_count`, `txn_type`);

-- ============================================================================
-- 5. 企微消息与联系人高频索引
-- ============================================================================
ALTER TABLE `crm_wecom_message`
  ADD KEY `idx_wecom_message_sent_id` (`tenant_id`, `sent_at`, `id`),
  ADD KEY `idx_wecom_message_conv_id` (`tenant_id`, `conversation_id`, `sent_at`, `id`),
  ADD KEY `idx_wecom_message_link_direct` (`tenant_id`, `corp_id`, `external_userid`, `staff_user_id`, `customer_id`);

ALTER TABLE `crm_wecom_contact`
  ADD KEY `idx_wecom_contact_consent_cover` (`tenant_id`, `corp_id`, `external_userid`, `consent_status`, `staff_user_id`);

-- ============================================================================
-- 6. 任务、通知、后台轮询、企微分析、审计、合并查重与登录引导索引补齐及列序修正
-- ============================================================================
ALTER TABLE `crm_task`
  DROP INDEX `idx_task_1`,
  ADD KEY `idx_task_1` (`tenant_id`, `assignee_user_id`, `status`, `due_at`),
  ADD KEY `idx_task_customer` (`tenant_id`, `customer_id`, `status`, `id`),
  ADD KEY `idx_task_due_poll` (`status`, `source_type`, `due_at`, `tenant_id`);

ALTER TABLE `crm_notification`
  DROP INDEX `idx_notification_unread`,
  ADD KEY `idx_notification_unread` (`tenant_id`, `recipient_user_id`, `read_at`, `created_at`, `id`);

ALTER TABLE `crm_notification_delivery`
  ADD KEY `idx_notification_delivery_poll` (`status`, `next_attempt_at`, `tenant_id`);

ALTER TABLE `crm_followup_batch`
  ADD KEY `idx_followup_batch_poll` (`status`, `next_retry_at`, `tenant_id`);

ALTER TABLE `crm_quick_reply_usage`
  ADD KEY `idx_quick_reply_usage_created` (`tenant_id`, `created_at`, `employee_id`, `send_status`),
  ADD KEY `idx_quick_reply_usage_send_time` (`tenant_id`, `send_time`, `employee_id`, `template_id`);

ALTER TABLE `crm_ai_reply_suggestion`
  ADD KEY `idx_ai_reply_created` (`tenant_id`, `created_at`, `employee_id`, `status`);

ALTER TABLE `crm_audit_log`
  ADD KEY `idx_audit_log_action` (`tenant_id`, `action_code`, `id`),
  ADD KEY `idx_audit_log_tenant_id` (`tenant_id`, `id`);

ALTER TABLE `crm_merge_case`
  ADD KEY `idx_merge_case_secondary` (`tenant_id`, `secondary_customer_id`, `status`, `id`);

ALTER TABLE `sys_user`
  ADD KEY `idx_user_username` (`username`);

ALTER TABLE `sys_org`
  ADD KEY `idx_org_code_status` (`org_code`, `status`, `tenant_id`);

-- ============================================================================
-- 7. 清理 100% 重复冗余索引（均不支撑外键：FK 为单列隐式索引，且分别被 uk_followup_batch_item_order / uk_merge_resource_snapshot_1 完全覆盖）
-- ============================================================================
ALTER TABLE `crm_followup_batch_order`
  DROP INDEX `idx_followup_batch_order_item`;

ALTER TABLE `crm_merge_resource_snapshot`
  DROP INDEX `idx_merge_resource_snapshot_1`;
