-- V58: 为存量缺少 sys_data_scope 的内部员工按当前生效角色补齐默认 customer / institution 数据范围。
-- 仅在 (tenant_id, user_id, resource) 不存在任何记录时补写，不覆盖已有授权，也不写入机构账号(INSTITUTION)。

INSERT INTO `sys_data_scope` (`tenant_id`, `user_id`, `resource`, `scope_type`, `scope_ref_id`)
SELECT
  resolved.`tenant_id`,
  resolved.`user_id`,
  'customer' AS `resource`,
  resolved.`scope_type`,
  resolved.`scope_ref_id`
FROM (
  SELECT
    u.`tenant_id`,
    u.`id` AS `user_id`,
    CASE
      WHEN MAX(CASE WHEN r.`role_code` IN ('ADMIN', 'OWNER', 'OPERATION', 'OPERATION_LEAD', 'FINANCE') THEN 1 ELSE 0 END) = 1
        THEN 'ALL'
      WHEN MAX(CASE WHEN r.`role_code` = 'MANAGER_LEAD' THEN 1 ELSE 0 END) = 1 AND u.`team_id` IS NOT NULL
        THEN 'TEAM'
      WHEN MAX(CASE WHEN r.`role_code` IN ('MANAGER_LEAD', 'MANAGER') THEN 1 ELSE 0 END) = 1
        THEN 'SELF'
      ELSE NULL
    END AS `scope_type`,
    CASE
      WHEN MAX(CASE WHEN r.`role_code` IN ('ADMIN', 'OWNER', 'OPERATION', 'OPERATION_LEAD', 'FINANCE') THEN 1 ELSE 0 END) = 1
        THEN NULL
      WHEN MAX(CASE WHEN r.`role_code` = 'MANAGER_LEAD' THEN 1 ELSE 0 END) = 1 AND u.`team_id` IS NOT NULL
        THEN u.`team_id`
      ELSE NULL
    END AS `scope_ref_id`
  FROM `sys_user` u
  INNER JOIN `sys_user_role` ur ON ur.`tenant_id` = u.`tenant_id` AND ur.`user_id` = u.`id`
  INNER JOIN `sys_role` r ON r.`tenant_id` = ur.`tenant_id` AND r.`id` = ur.`role_id` AND r.`status` = 'ACTIVE'
  WHERE u.`user_type` = 'EMPLOYEE'
    AND NOT EXISTS (
      SELECT 1 FROM `sys_data_scope` ds
      WHERE ds.`tenant_id` = u.`tenant_id` AND ds.`user_id` = u.`id` AND ds.`resource` = 'customer'
    )
  GROUP BY u.`tenant_id`, u.`id`, u.`team_id`
) resolved
WHERE resolved.`scope_type` IS NOT NULL;

INSERT INTO `sys_data_scope` (`tenant_id`, `user_id`, `resource`, `scope_type`, `scope_ref_id`)
SELECT DISTINCT
  u.`tenant_id`,
  u.`id` AS `user_id`,
  'institution' AS `resource`,
  'ALL' AS `scope_type`,
  NULL AS `scope_ref_id`
FROM `sys_user` u
INNER JOIN `sys_user_role` ur ON ur.`tenant_id` = u.`tenant_id` AND ur.`user_id` = u.`id`
INNER JOIN `sys_role` r ON r.`tenant_id` = ur.`tenant_id` AND r.`id` = ur.`role_id` AND r.`status` = 'ACTIVE'
WHERE u.`user_type` = 'EMPLOYEE'
  AND r.`role_code` IN ('ADMIN', 'OWNER', 'OPERATION', 'OPERATION_LEAD', 'FINANCE', 'BUSINESS', 'MANAGER_LEAD', 'MANAGER')
  AND NOT EXISTS (
    SELECT 1 FROM `sys_data_scope` ds
    WHERE ds.`tenant_id` = u.`tenant_id` AND ds.`user_id` = u.`id` AND ds.`resource` = 'institution'
  );
