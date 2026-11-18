-- 仅在两个角色已无用户使用且完成备份后执行。
DELETE rp FROM `sys_role_permission` rp
JOIN `sys_role` r ON r.`tenant_id`=rp.`tenant_id` AND r.`id`=rp.`role_id`
JOIN `sys_permission` p ON p.`tenant_id`=rp.`tenant_id` AND p.`id`=rp.`permission_id`
WHERE r.`role_code` IN ('OPERATION','OPERATION_LEAD')
   OR (r.`role_code` IN ('ADMIN','BUSINESS') AND p.`permission_code`='campaign:review');

DELETE FROM `sys_role` WHERE `role_code` IN ('OPERATION','OPERATION_LEAD');
DELETE FROM `sys_permission` WHERE `permission_code`='campaign:review';
