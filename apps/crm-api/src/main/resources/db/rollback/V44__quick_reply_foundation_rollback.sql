DROP TRIGGER IF EXISTS `trg_quick_reply_usage_no_delete`;
DROP TRIGGER IF EXISTS `trg_quick_reply_usage_no_update`;
DROP TABLE IF EXISTS `crm_quick_reply_usage`;
DROP TABLE IF EXISTS `crm_quick_reply_template`;
DROP TABLE IF EXISTS `crm_quick_reply_variable`;
DROP TABLE IF EXISTS `crm_quick_reply_category`;
DELETE rp FROM `sys_role_permission` rp JOIN `sys_permission` p ON p.`id`=rp.`permission_id`
WHERE p.`tenant_id`=rp.`tenant_id` AND p.`permission_code` IN ('quickreply:view','quickreply:manage');
DELETE FROM `sys_permission` WHERE `permission_code` IN ('quickreply:view','quickreply:manage');
