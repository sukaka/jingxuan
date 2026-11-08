DELETE FROM `crm_idempotency_record` WHERE `scope_code` IN ('ai.provider.config.save','ai.provider.config.test');
DELETE rp FROM `sys_role_permission` rp
JOIN `sys_permission` p ON p.`tenant_id`=rp.`tenant_id` AND p.`id`=rp.`permission_id`
WHERE p.`permission_code`='ai:config';
DELETE FROM `sys_permission` WHERE `permission_code`='ai:config';
DROP TABLE IF EXISTS `crm_ai_provider_config`;
