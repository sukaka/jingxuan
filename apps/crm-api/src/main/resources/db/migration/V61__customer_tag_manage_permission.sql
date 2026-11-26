-- V61: 客户标签内容维护权限（customer:tag:manage），仅授予系统管理员与管家主管，普通管家仅可选择标签不可新增/修改标签内容。

INSERT INTO `sys_dictionary_type` (`tenant_id`,`dict_code`,`dict_name`,`status`)
SELECT refs.`tenant_id`,'customerTags','客户标签','ACTIVE'
FROM (
  SELECT o.`tenant_id` FROM `sys_org` o
  UNION
  SELECT DISTINCT r.`tenant_id` FROM `sys_role` r
  UNION
  SELECT 1 AS `tenant_id`
) refs
ON DUPLICATE KEY UPDATE `dict_code`=VALUES(`dict_code`);

INSERT IGNORE INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT refs.`tenant_id`,'customer:tag:manage','客户标签内容维护（新增/修改/停用）'
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
JOIN `sys_permission` p ON p.`tenant_id` = r.`tenant_id` AND p.`permission_code` = 'customer:tag:manage'
WHERE r.`role_code` IN ('ADMIN', 'MANAGER_LEAD');
