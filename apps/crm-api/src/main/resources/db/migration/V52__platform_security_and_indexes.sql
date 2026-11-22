-- M1A: 平台安全加固、RBAC 治理权限与后台轮询/会话索引补齐。

-- 1. RBAC: 新增 3 项治理/高危权限（兼容全新空库与多租户升级库）
INSERT IGNORE INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT refs.`tenant_id`, v.`permission_code`, v.`description`
FROM (
  SELECT o.`tenant_id` FROM `sys_org` o
  UNION
  SELECT DISTINCT r.`tenant_id` FROM `sys_role` r
  UNION
  SELECT 1 AS `tenant_id`
) refs
CROSS JOIN (
  SELECT 'wecom:mapping:manage' AS `permission_code`, '企微员工映射与通知开关管理' AS `description`
  UNION ALL SELECT 'followup:policy:manage', '跟进间隔策略管理'
  UNION ALL SELECT 'channel:approve_finance', '合作渠道财务与佣金变更审批'
) v;

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`, r.`id`, p.`id`
FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id` = r.`tenant_id` AND p.`permission_code` = 'wecom:mapping:manage'
WHERE r.`role_code` = 'ADMIN';

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`, r.`id`, p.`id`
FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id` = r.`tenant_id` AND p.`permission_code` = 'followup:policy:manage'
WHERE r.`role_code` IN ('ADMIN', 'OPERATION_LEAD');

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`, r.`id`, p.`id`
FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id` = r.`tenant_id` AND p.`permission_code` = 'channel:approve_finance'
WHERE r.`role_code` IN ('ADMIN', 'FINANCE');

-- 2. M-BE-03: sys_login_session 前导 token_hash 查询索引与过期清理索引
ALTER TABLE `sys_login_session`
  ADD KEY `idx_login_session_token_hash` (`token_hash`),
  ADD KEY `idx_login_session_expires_at` (`expires_at`);

-- 3. M-BE-04: crm_idempotency_record 增加操作用户维度隔离，避免跨用户同幂等键碰撞
ALTER TABLE `crm_idempotency_record`
  ADD COLUMN `actor_user_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作用户ID(0=系统/匿名回调)' AFTER `scope_code`,
  DROP INDEX `uk_idempotency_reco_1`,
  ADD UNIQUE KEY `uk_idempotency_reco_1` (`tenant_id`, `scope_code`, `actor_user_id`, `idempotency_key`);

-- 4. M-INF-02: 5 张后台轮询表 status 前导覆盖索引
ALTER TABLE `crm_outbox_event`
  ADD KEY `idx_outbox_relay_status` (`status`, `next_retry_at`, `claim_expires_at`, `tenant_id`);

ALTER TABLE `crm_ai_job`
  ADD KEY `idx_ai_job_status_claim` (`status`, `next_retry_at`, `claimed_until`, `tenant_id`);

ALTER TABLE `crm_export_job`
  ADD KEY `idx_export_job_status_lease` (`status`, `lease_expires_at`, `tenant_id`);

ALTER TABLE `crm_knowledge_sync_job`
  ADD KEY `idx_knowledge_job_status_claim` (`status`, `next_retry_at`, `lease_expires_at`, `tenant_id`, `document_id`);

ALTER TABLE `crm_wecom_media_asset`
  ADD KEY `idx_wecom_media_status_claim` (`status`, `next_retry_at`, `lease_until`, `tenant_id`);

-- 5. M-INF-06: 企微员工映射默认关闭应用通知推送
ALTER TABLE `crm_wecom_staff_account`
  MODIFY COLUMN `message_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否允许接收企微应用通知；默认关闭，独立于归档同步 enabled';
