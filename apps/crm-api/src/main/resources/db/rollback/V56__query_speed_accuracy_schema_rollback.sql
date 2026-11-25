-- 回滚 V56__query_speed_accuracy_schema.sql
-- 与 V56 严格互逆：按逆序恢复重复索引、索引列序、新增索引、新增列及列宽定义。
-- 注：回滚第 1 节列宽缩容前需确保库中不存在超过原列宽(128/96)的 conversation_id/wecom_userid 数据。

-- ============================================================================
-- 7. 恢复 V56 删除的 2 个冗余索引
-- ============================================================================
ALTER TABLE `crm_merge_resource_snapshot`
  ADD KEY `idx_merge_resource_snapshot_1` (`tenant_id`, `merge_case_id`, `resource_type`);

ALTER TABLE `crm_followup_batch_order`
  ADD KEY `idx_followup_batch_order_item` (`tenant_id`, `item_id`, `order_id`);

-- ============================================================================
-- 6. 回滚任务、通知、后台轮询、企微分析、审计、合并查重与登录引导索引
-- ============================================================================
ALTER TABLE `sys_org`
  DROP INDEX `idx_org_code_status`;

ALTER TABLE `sys_user`
  DROP INDEX `idx_user_username`;

ALTER TABLE `crm_merge_case`
  DROP INDEX `idx_merge_case_secondary`;

ALTER TABLE `crm_audit_log`
  DROP INDEX `idx_audit_log_tenant_id`,
  DROP INDEX `idx_audit_log_action`;

ALTER TABLE `crm_ai_reply_suggestion`
  DROP INDEX `idx_ai_reply_created`;

ALTER TABLE `crm_quick_reply_usage`
  DROP INDEX `idx_quick_reply_usage_send_time`,
  DROP INDEX `idx_quick_reply_usage_created`;

ALTER TABLE `crm_followup_batch`
  DROP INDEX `idx_followup_batch_poll`;

ALTER TABLE `crm_notification_delivery`
  DROP INDEX `idx_notification_delivery_poll`;

ALTER TABLE `crm_notification`
  DROP INDEX `idx_notification_unread`,
  ADD KEY `idx_notification_unread` (`tenant_id`, `recipient_user_id`, `read_at`, `id`);

ALTER TABLE `crm_task`
  DROP INDEX `idx_task_due_poll`,
  DROP INDEX `idx_task_customer`,
  DROP INDEX `idx_task_1`,
  ADD KEY `idx_task_1` (`tenant_id`, `assignee_user_id`, `due_at`, `status`);

-- ============================================================================
-- 5. 回滚企微消息与联系人高频索引
-- ============================================================================
ALTER TABLE `crm_wecom_contact`
  DROP INDEX `idx_wecom_contact_consent_cover`;

ALTER TABLE `crm_wecom_message`
  DROP INDEX `idx_wecom_message_link_direct`,
  DROP INDEX `idx_wecom_message_conv_id`,
  DROP INDEX `idx_wecom_message_sent_id`;

-- ============================================================================
-- 4. 回滚报表 7 大事实表时间窗与覆盖索引
-- ============================================================================
ALTER TABLE `crm_entitlement_ledger`
  DROP INDEX `idx_entitlement_ledg_1`,
  ADD KEY `idx_entitlement_ledg_1` (`tenant_id`, `entitlement_id`);

ALTER TABLE `crm_order_item`
  DROP INDEX `idx_order_item_sku`;

ALTER TABLE `crm_order`
  DROP INDEX `idx_order_status_id`,
  DROP INDEX `idx_order_ordered_at`,
  DROP INDEX `idx_order_owner_sale_paid`,
  DROP INDEX `idx_order_status_paid_at`;

ALTER TABLE `crm_refund`
  DROP INDEX `idx_refund_status_id`;

ALTER TABLE `crm_refund_payment`
  DROP INDEX `idx_refund_payment_provider_no`,
  DROP INDEX `idx_refund_payment_status_succeeded`;

ALTER TABLE `crm_payment_txn`
  DROP INDEX `idx_payment_txn_1`,
  ADD KEY `idx_payment_txn_1` (`tenant_id`, `order_id`, `status`),
  DROP INDEX `idx_payment_txn_status_confirmed`;

ALTER TABLE `crm_redemption_reversal`
  DROP INDEX `idx_redemption_reversal_redemption`;

ALTER TABLE `crm_redemption_line`
  DROP INDEX `idx_redemption_line_project`;

ALTER TABLE `crm_redemption`
  DROP INDEX `idx_redemption_status_id`,
  DROP INDEX `idx_redemption_status_posted`;

ALTER TABLE `crm_appointment_history`
  DROP INDEX `idx_appointment_history_status_time`;

ALTER TABLE `crm_followup`
  DROP INDEX `idx_followup_created_by`,
  DROP INDEX `idx_followup_contact_at`;

-- ============================================================================
-- 3. 回滚客户关联表反向索引
-- ============================================================================
ALTER TABLE `crm_customer_identity`
  DROP INDEX `idx_customer_identity_ext`;

ALTER TABLE `crm_customer_tag`
  DROP INDEX `idx_customer_tag_reverse`;

-- ============================================================================
-- 2. 回滚 Wave 2 结构增量列与配套索引
-- ============================================================================
ALTER TABLE `crm_risk_case`
  DROP INDEX `idx_risk_case_created`,
  DROP INDEX `idx_risk_case_customer`,
  DROP COLUMN `order_id`,
  DROP COLUMN `customer_id`;

ALTER TABLE `crm_youzan_event`
  DROP INDEX `idx_youzan_event_received`,
  DROP INDEX `idx_youzan_event_campaign`,
  DROP INDEX `idx_youzan_event_ext_customer`,
  DROP COLUMN `campaign_id`,
  DROP COLUMN `external_customer_id`;

ALTER TABLE `crm_customer`
  DROP INDEX `idx_customer_city_tier`,
  DROP INDEX `idx_customer_status`,
  DROP INDEX `idx_customer_created`,
  DROP INDEX `idx_customer_team`,
  DROP INDEX `idx_customer_next_followup`,
  DROP COLUMN `next_followup_at`;

-- ============================================================================
-- 1. 回滚跨表列宽对齐与配套索引
-- ============================================================================
ALTER TABLE `crm_wecom_callback_event`
  DROP INDEX `idx_wecom_callback_pending`,
  MODIFY COLUMN `wecom_userid` VARCHAR(96) NULL COMMENT '企微成员userid';

ALTER TABLE `crm_wecom_staff_account`
  DROP INDEX `idx_wecom_staff_user_msg`,
  MODIFY COLUMN `wecom_userid` VARCHAR(96) NOT NULL COMMENT '企微成员userid';

ALTER TABLE `crm_ai_chat_delegation_run`
  DROP INDEX `idx_ai_delegation_customer_status`,
  MODIFY COLUMN `conversation_id` VARCHAR(128) NULL COMMENT '会话标识';

ALTER TABLE `crm_ai_job`
  MODIFY COLUMN `conversation_id` VARCHAR(128) NOT NULL COMMENT '企微会话标识';
