-- V61 回滚：移除 customer:tag:manage 权限及其角色授权；不删除独立治理的 customerTags 字典数据。

DELETE rp
FROM `sys_role_permission` rp
JOIN `sys_permission` p ON p.`tenant_id` = rp.`tenant_id` AND p.`id` = rp.`permission_id`
WHERE p.`permission_code` = 'customer:tag:manage';

DELETE FROM `sys_permission`
WHERE `permission_code` = 'customer:tag:manage';
