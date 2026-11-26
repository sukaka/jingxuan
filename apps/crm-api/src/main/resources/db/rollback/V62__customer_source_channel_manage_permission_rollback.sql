-- V62 回滚：移除 customer:source_channel:manage 权限及其角色授权；不删除独立治理的 customerSources/customerChannels 字典数据。

DELETE rp
FROM `sys_role_permission` rp
JOIN `sys_permission` p ON p.`tenant_id` = rp.`tenant_id` AND p.`id` = rp.`permission_id`
WHERE p.`permission_code` = 'customer:source_channel:manage';

DELETE FROM `sys_permission`
WHERE `permission_code` = 'customer:source_channel:manage';
