-- V65: 客户隐私信息（手机号/身份证号/邮箱/微信号）加密存储、直属上级配置、上级审批限时查看申请单与不可篡改揭盲审计日志。
-- 严格约束：隐私字段仅存 AES-256-GCM 密文与 HMAC-SHA256 盲索引；任何角色默认不回显明文；
-- 隐私访问日志仅允许追加，数据库触发器物理禁止 UPDATE/DELETE。

ALTER TABLE `crm_customer`
  ADD COLUMN `id_card_cipher` VARCHAR(512) NULL COMMENT '加密身份证号(AES-256-GCM)' AFTER `mobile_hash`,
  ADD COLUMN `id_card_hash` VARCHAR(64) NULL COMMENT '身份证号不可逆盲索引(HMAC-SHA256)' AFTER `id_card_cipher`,
  ADD COLUMN `email_cipher` VARCHAR(512) NULL COMMENT '加密邮箱(AES-256-GCM)' AFTER `id_card_hash`,
  ADD COLUMN `email_hash` VARCHAR(64) NULL COMMENT '邮箱不可逆盲索引(HMAC-SHA256)' AFTER `email_cipher`,
  ADD COLUMN `wechat_id_cipher` VARCHAR(512) NULL COMMENT '加密微信号(AES-256-GCM)' AFTER `email_hash`,
  ADD COLUMN `wechat_id_hash` VARCHAR(64) NULL COMMENT '微信号不可逆盲索引(HMAC-SHA256)' AFTER `wechat_id_cipher`,
  ADD KEY `idx_customer_id_card_hash` (`tenant_id`,`id_card_hash`),
  ADD KEY `idx_customer_email_hash` (`tenant_id`,`email_hash`),
  ADD KEY `idx_customer_wechat_hash` (`tenant_id`,`wechat_id_hash`);

ALTER TABLE `sys_user`
  ADD COLUMN `supervisor_user_id` BIGINT UNSIGNED NULL COMMENT '直属上级用户ID(内部员工，禁止指向本人或成环)' AFTER `team_id`,
  ADD KEY `idx_user_supervisor` (`tenant_id`,`supervisor_user_id`);

CREATE TABLE IF NOT EXISTS `crm_customer_privacy_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `request_no` VARCHAR(48) NOT NULL COMMENT '申请单编号(PR+10位补零)',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '目标客户ID',
  `applicant_user_id` BIGINT UNSIGNED NOT NULL COMMENT '申请人ID',
  `applicant_team_id` BIGINT UNSIGNED NULL COMMENT '申请时团队快照',
  `requested_fields_json` JSON NOT NULL COMMENT '申请查看字段列表(MOBILE/ID_CARD/EMAIL/WECHAT_ID)',
  `approved_fields_json` JSON NULL COMMENT '批准查看字段列表(申请字段子集)',
  `reason` VARCHAR(500) NOT NULL COMMENT '申请查看原因(不含隐私明文)',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/CANCELED/REVOKED',
  `assigned_approver_id` BIGINT UNSIGNED NOT NULL COMMENT '解析出的主审批上级ID',
  `approver_source` VARCHAR(32) NOT NULL COMMENT 'DIRECT_SUPERVISOR/TEAM_LEADER/TEAM_MANAGER_LEAD/ROLE_SUPERIOR/ADMIN_FALLBACK',
  `requested_duration_minutes` INT NOT NULL DEFAULT 30 COMMENT '申请授权时长(分钟:15/30/120/1440)',
  `approved_duration_minutes` INT NULL COMMENT '实际批准时长(分钟:15/30/120/1440)',
  `reviewed_by` BIGINT UNSIGNED NULL COMMENT '实际审批人ID',
  `reviewed_at` DATETIME(3) NULL COMMENT '审批时间(UTC)',
  `review_reason` VARCHAR(500) NULL COMMENT '审批意见或驳回原因',
  `grant_starts_at` DATETIME(3) NULL COMMENT '授权生效时间(UTC)',
  `grant_expires_at` DATETIME(3) NULL COMMENT '授权到期时间(UTC)',
  `revoked_by` BIGINT UNSIGNED NULL COMMENT '撤销授权操作人ID',
  `revoked_at` DATETIME(3) NULL COMMENT '撤销授权时间(UTC)',
  `revoke_reason` VARCHAR(500) NULL COMMENT '撤销授权原因',
  `reveal_count` INT NOT NULL DEFAULT 0 COMMENT '有效期内实际揭盲次数',
  `last_revealed_at` DATETIME(3) NULL COMMENT '最近一次揭盲时间(UTC)',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_privacy_req_no` (`tenant_id`,`request_no`),
  KEY `idx_privacy_req_applicant` (`tenant_id`,`applicant_user_id`,`customer_id`,`status`,`grant_expires_at`),
  KEY `idx_privacy_req_approver` (`tenant_id`,`assigned_approver_id`,`status`,`created_at`),
  KEY `idx_privacy_req_customer` (`tenant_id`,`customer_id`,`created_at`),
  KEY `idx_privacy_req_status` (`tenant_id`,`status`,`created_at`),
  CONSTRAINT `fk_privacy_req_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_privacy_req_applicant` FOREIGN KEY (`applicant_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_privacy_req_approver` FOREIGN KEY (`assigned_approver_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_privacy_req_reviewer` FOREIGN KEY (`reviewed_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_privacy_req_revoker` FOREIGN KEY (`revoked_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户隐私信息查看申请与限时审批授权';

CREATE TABLE IF NOT EXISTS `crm_customer_privacy_access_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '目标客户ID',
  `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '查看人ID',
  `actor_role_snapshot` VARCHAR(255) NOT NULL COMMENT '查看时角色快照',
  `access_mode` VARCHAR(32) NOT NULL COMMENT 'ADMIN_DIRECT_REVEAL/APPROVED_GRANT_REVEAL',
  `request_id` BIGINT UNSIGNED NULL COMMENT '关联审批申请单ID(ADMIN直查为空)',
  `revealed_fields_json` JSON NOT NULL COMMENT '本次实际揭盲字段列表',
  `masked_values_snapshot_json` JSON NOT NULL COMMENT '本次揭盲字段脱敏快照(严禁落明文)',
  `reason` VARCHAR(500) NOT NULL COMMENT '查看原因',
  `client_ip` VARCHAR(64) NULL COMMENT '客户端IP',
  `user_agent` VARCHAR(255) NULL COMMENT '客户端User-Agent',
  `trace_id` VARCHAR(96) NULL COMMENT '请求追踪ID',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '查看时间(UTC)',
  PRIMARY KEY (`id`),
  KEY `idx_privacy_access_customer` (`tenant_id`,`customer_id`,`created_at`),
  KEY `idx_privacy_access_actor` (`tenant_id`,`actor_user_id`,`created_at`),
  KEY `idx_privacy_access_req` (`tenant_id`,`request_id`,`created_at`),
  KEY `idx_privacy_access_mode` (`tenant_id`,`access_mode`,`created_at`),
  CONSTRAINT `fk_privacy_access_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_privacy_access_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_privacy_access_request` FOREIGN KEY (`request_id`) REFERENCES `crm_customer_privacy_request` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户隐私信息揭盲不可覆盖审计日志';

CREATE TRIGGER `trg_privacy_access_log_no_update` BEFORE UPDATE ON `crm_customer_privacy_access_log`
FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Customer privacy access log is append only';

CREATE TRIGGER `trg_privacy_access_log_no_delete` BEFORE DELETE ON `crm_customer_privacy_access_log`
FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Customer privacy access log is append only';

-- 新增 4 项隐私管控权限（多租户幂等）
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
  SELECT 'customer:privacy:request' AS `permission_code`, '申请查看客户隐私信息与有效期内揭盲' AS `description`
  UNION ALL SELECT 'customer:privacy:approve', '审批下属客户隐私查看申请与撤销授权'
  UNION ALL SELECT 'customer:privacy:admin_reveal', '管理员按钮显式揭盲客户隐私（强制审计）'
  UNION ALL SELECT 'customer:privacy:audit', '查看客户隐私访问审计日志台账'
) v;

-- 权限授予角色：严禁向机构角色授予任何客户隐私权限；admin_reveal 仅授予 ADMIN
INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`, r.`id`, p.`id`
FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id` = r.`tenant_id` AND p.`permission_code` = 'customer:privacy:request'
WHERE r.`role_code` IN ('ADMIN','OWNER','MANAGER_LEAD','MANAGER','OPERATION','OPERATION_LEAD','FINANCE','BUSINESS');

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`, r.`id`, p.`id`
FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id` = r.`tenant_id` AND p.`permission_code` = 'customer:privacy:approve'
WHERE r.`role_code` IN ('ADMIN','OWNER','MANAGER_LEAD','OPERATION_LEAD');

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`, r.`id`, p.`id`
FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id` = r.`tenant_id` AND p.`permission_code` = 'customer:privacy:admin_reveal'
WHERE r.`role_code` = 'ADMIN';

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`, r.`id`, p.`id`
FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id` = r.`tenant_id` AND p.`permission_code` = 'customer:privacy:audit'
WHERE r.`role_code` IN ('ADMIN','OWNER','MANAGER_LEAD','OPERATION_LEAD');
