INSERT IGNORE INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT refs.`tenant_id`,'wecom:sidebar:view','企微聊天侧边栏查看、JS签名与当前客户识别'
FROM (SELECT o.`tenant_id` FROM `sys_org` o UNION SELECT 1) refs;

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code`='wecom:sidebar:view'
WHERE r.`role_code` IN ('ADMIN','OWNER','MANAGER','MANAGER_LEAD','OPERATION','OPERATION_LEAD','BUSINESS');
