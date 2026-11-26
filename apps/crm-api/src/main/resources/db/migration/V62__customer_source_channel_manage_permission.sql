-- V62: 客户来源与渠道维护权限（customer:source_channel:manage），仅授予系统管理员与管家主管，普通管家仅可下拉选择不可新增/修改/停用。

INSERT INTO `sys_dictionary_type` (`tenant_id`,`dict_code`,`dict_name`,`status`)
SELECT refs.`tenant_id`, d.`dict_code`, d.`dict_name`, 'ACTIVE'
FROM (
  SELECT o.`tenant_id` FROM `sys_org` o
  UNION
  SELECT DISTINCT r.`tenant_id` FROM `sys_role` r
  UNION
  SELECT 1 AS `tenant_id`
) refs
CROSS JOIN (
  SELECT 'customerSources' AS `dict_code`, '客户来源' AS `dict_name`
  UNION ALL
  SELECT 'customerChannels' AS `dict_code`, '客户渠道' AS `dict_name`
) d
WHERE 1=1
ON DUPLICATE KEY UPDATE `dict_code`=VALUES(`dict_code`);

INSERT IGNORE INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT refs.`tenant_id`,'customer:source_channel:manage','客户来源与渠道维护（新增/修改/停用/启用）'
FROM (
  SELECT o.`tenant_id` FROM `sys_org` o
  UNION
  SELECT DISTINCT r.`tenant_id` FROM `sys_role` r
  UNION
  SELECT 1 AS `tenant_id`
) refs;

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`, r.`id`, p.`id`
FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id` = r.`tenant_id` AND p.`permission_code` = 'customer:source_channel:manage'
WHERE r.`role_code` IN ('ADMIN', 'MANAGER_LEAD');
