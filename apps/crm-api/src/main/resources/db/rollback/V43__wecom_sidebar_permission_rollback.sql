DELETE rp FROM `sys_role_permission` rp JOIN `sys_permission` p ON p.`id`=rp.`permission_id`
WHERE p.`tenant_id`=rp.`tenant_id` AND p.`permission_code`='wecom:sidebar:view';
DELETE FROM `sys_permission` WHERE `permission_code`='wecom:sidebar:view';
