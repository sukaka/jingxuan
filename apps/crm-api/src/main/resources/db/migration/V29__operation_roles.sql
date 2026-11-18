INSERT IGNORE INTO `sys_role` (`tenant_id`,`role_code`,`role_name`,`status`)
SELECT o.`tenant_id`,v.`role_code`,v.`role_name`,'ACTIVE' FROM `sys_org` o
CROSS JOIN (SELECT 'OPERATION' AS `role_code`,'运营' AS `role_name`
      UNION ALL SELECT 'OPERATION_LEAD','运营主管') v;

INSERT INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT o.`tenant_id`,'campaign:review','活动审核与发布（草稿转为进行中）' FROM `sys_org` o
ON DUPLICATE KEY UPDATE `description`=VALUES(`description`);

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id`
WHERE r.`role_code`='ADMIN';

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code`='campaign:review'
WHERE r.`role_code` IN ('BUSINESS','OPERATION_LEAD');

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code` IN (
  'product:read','product:write','campaign:read','campaign:write',
  'material:read','material:write','channel:read','channel:write',
  'dictionary:read','order:read','report:read','risk:read','risk:write',
  'customer:read','institution:read')
WHERE r.`role_code` IN ('OPERATION','OPERATION_LEAD');

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code`='material:review'
WHERE r.`role_code`='OPERATION_LEAD';

-- 仅创建角色和权限模板，不创建生产登录账号、不授予导出、支付确认、用户管理或集成配置权限。
-- 回滚前须先停用并迁移两个新角色的用户，再按批准脚本清理授权和角色；不可直接删除已有业务数据。
