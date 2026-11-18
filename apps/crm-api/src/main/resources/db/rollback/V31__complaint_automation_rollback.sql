DROP TRIGGER IF EXISTS `trg_complaint_case_history_no_delete`;
DROP TRIGGER IF EXISTS `trg_complaint_case_history_no_update`;
DROP TRIGGER IF EXISTS `trg_complaint_hit_history_no_delete`;
DROP TRIGGER IF EXISTS `trg_complaint_hit_history_no_update`;
DROP TRIGGER IF EXISTS `trg_complaint_case_no_delete`;
DROP TRIGGER IF EXISTS `trg_complaint_case_immutable`;
DROP TRIGGER IF EXISTS `trg_complaint_hit_no_delete`;
DROP TRIGGER IF EXISTS `trg_complaint_hit_immutable`;
DROP TRIGGER IF EXISTS `trg_complaint_keyword_no_delete`;
DROP TRIGGER IF EXISTS `trg_complaint_policy_no_delete`;
DROP TABLE IF EXISTS `crm_complaint_case_history`;
DROP TABLE IF EXISTS `crm_complaint_hit_history`;
DROP TABLE IF EXISTS `crm_complaint_case`;
DROP TABLE IF EXISTS `crm_complaint_hit`;
DROP TABLE IF EXISTS `crm_complaint_keyword`;
DROP TABLE IF EXISTS `crm_complaint_policy`;
DELETE rp FROM `sys_role_permission` rp JOIN `sys_permission` p ON p.`id`=rp.`permission_id`
WHERE p.`tenant_id`=rp.`tenant_id` AND p.`permission_code` IN ('complaint:read','complaint:write','complaint:config');
DELETE FROM `sys_permission` WHERE `permission_code` IN ('complaint:read','complaint:write','complaint:config');
-- 不删除角色：角色可能早于本迁移存在或已有其他授权。
