-- V65 回滚：移除客户隐私审批与揭盲审计表、触发器、新增权限及 crm_customer/sys_user 新增列。
-- 注意：回滚前请确认业务方已归档审计日志；回滚后不可恢复已清理的隐私密文列。

DROP TRIGGER IF EXISTS `trg_privacy_access_log_no_delete`;
DROP TRIGGER IF EXISTS `trg_privacy_access_log_no_update`;

DROP TABLE IF EXISTS `crm_customer_privacy_access_log`;
DROP TABLE IF EXISTS `crm_customer_privacy_request`;

DELETE rp
FROM `sys_role_permission` rp
JOIN `sys_permission` p ON p.`tenant_id` = rp.`tenant_id` AND p.`id` = rp.`permission_id`
WHERE p.`permission_code` IN (
  'customer:privacy:request',
  'customer:privacy:approve',
  'customer:privacy:admin_reveal',
  'customer:privacy:audit'
);

DELETE FROM `sys_permission`
WHERE `permission_code` IN (
  'customer:privacy:request',
  'customer:privacy:approve',
  'customer:privacy:admin_reveal',
  'customer:privacy:audit'
);

ALTER TABLE `sys_user`
  DROP KEY `idx_user_supervisor`,
  DROP COLUMN `supervisor_user_id`;

ALTER TABLE `crm_customer`
  DROP KEY `idx_customer_wechat_hash`,
  DROP KEY `idx_customer_email_hash`,
  DROP KEY `idx_customer_id_card_hash`,
  DROP COLUMN `wechat_id_hash`,
  DROP COLUMN `wechat_id_cipher`,
  DROP COLUMN `email_hash`,
  DROP COLUMN `email_cipher`,
  DROP COLUMN `id_card_hash`,
  DROP COLUMN `id_card_cipher`;
