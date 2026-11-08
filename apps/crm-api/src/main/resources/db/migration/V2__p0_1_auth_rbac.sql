-- P0-1 认证授权与 RBAC/数据范围/审计基表。
-- 除 sys_login_session 外，表结构逐字复制自 docs/07_可执行参考/schema.mysql8.4.reference.sql。
-- 所有表带 tenant_id；时间 DATETIME(3) UTC；version 乐观锁；金额不在本阶段出现。

CREATE TABLE IF NOT EXISTS `sys_org` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `org_code` VARCHAR(64) NOT NULL COMMENT '组织编码',
  `org_name` VARCHAR(160) NOT NULL COMMENT '组织名称',
  `status` VARCHAR(32) NOT NULL COMMENT '启用状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_org_1` (`tenant_id`,`org_code`),
  KEY `idx_org_1` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组织/企业';

CREATE TABLE IF NOT EXISTS `sys_team` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `org_id` BIGINT UNSIGNED NOT NULL COMMENT '组织ID',
  `team_name` VARCHAR(128) NOT NULL COMMENT '团队名',
  `leader_user_id` BIGINT UNSIGNED NULL COMMENT '负责人ID',
  `status` VARCHAR(32) NOT NULL COMMENT '团队状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_team_1` (`tenant_id`,`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管家/商务团队';

CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `org_id` BIGINT UNSIGNED NOT NULL COMMENT '组织',
  `team_id` BIGINT UNSIGNED NULL COMMENT '团队',
  `username` VARCHAR(96) NOT NULL COMMENT '登录名',
  `display_name` VARCHAR(128) NOT NULL COMMENT '显示/真实姓名',
  `password_hash` VARCHAR(255) NULL COMMENT '密码哈希(或SSO标识)',
  `user_type` VARCHAR(32) NOT NULL COMMENT 'EMPLOYEE/INSTITUTION',
  `status` VARCHAR(32) NOT NULL COMMENT '启用/停用',
  `mfa_ref` VARCHAR(128) NULL COMMENT 'MFA配置引用',
  `last_login_at` DATETIME(3) NULL COMMENT '最近登录',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_1` (`tenant_id`,`username`),
  KEY `idx_user_1` (`tenant_id`,`team_id`),
  KEY `idx_user_2` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统登录用户';

CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `role_code` VARCHAR(64) NOT NULL COMMENT '角色代码',
  `role_name` VARCHAR(96) NOT NULL COMMENT '角色名称',
  `status` VARCHAR(32) NOT NULL COMMENT '启用状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_1` (`tenant_id`,`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

CREATE TABLE IF NOT EXISTS `sys_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `permission_code` VARCHAR(96) NOT NULL COMMENT '例如 customer:read',
  `description` VARCHAR(255) NULL COMMENT '用途',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_1` (`tenant_id`,`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源动作权限';

CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户',
  `role_id` BIGINT UNSIGNED NOT NULL COMMENT '角色',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role_1` (`tenant_id`,`user_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联';

CREATE TABLE IF NOT EXISTS `sys_role_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `role_id` BIGINT UNSIGNED NOT NULL COMMENT '角色',
  `permission_id` BIGINT UNSIGNED NOT NULL COMMENT '权限',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission_1` (`tenant_id`,`role_id`,`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色动作权限';

CREATE TABLE IF NOT EXISTS `sys_data_scope` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户',
  `resource` VARCHAR(64) NOT NULL COMMENT 'customer/order/institution等',
  `scope_type` VARCHAR(32) NOT NULL COMMENT 'SELF/TEAM/ALL/INSTITUTION',
  `scope_ref_id` BIGINT UNSIGNED NULL COMMENT '数据范围对象',
  `expires_at` DATETIME(3) NULL COMMENT '到期',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_data_scope_1` (`tenant_id`,`user_id`,`resource`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据范围授权';

CREATE TABLE IF NOT EXISTS `crm_institution_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '机构',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '系统账号',
  `institution_role` VARCHAR(48) NOT NULL COMMENT 'FRONTDESK/OPERATOR/FINANCE',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_institution_user_1` (`tenant_id`,`institution_id`,`user_id`),
  KEY `idx_institution_user_1` (`tenant_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机构账号到机构的权限映射';

CREATE TABLE IF NOT EXISTS `crm_audit_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '行为人',
  `action_code` VARCHAR(96) NOT NULL COMMENT '动作编码',
  `target_type` VARCHAR(64) NOT NULL COMMENT '资源类型',
  `target_id` VARCHAR(96) NOT NULL COMMENT '资源ID',
  `before_json` JSON NULL COMMENT '操作前脱敏快照',
  `after_json` JSON NULL COMMENT '操作后脱敏快照',
  `trace_id` VARCHAR(96) NULL COMMENT '调用trace',
  `client_ip` VARCHAR(64) NULL COMMENT '客户端IP',
  `reason` TEXT NULL COMMENT '原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_audit_log_1` (`tenant_id`,`target_type`,`target_id`,`created_at`),
  KEY `idx_audit_log_2` (`tenant_id`,`actor_user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感操作不可覆盖审计日志';

CREATE TABLE IF NOT EXISTS `crm_idempotency_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `scope_code` VARCHAR(64) NOT NULL COMMENT '业务范围',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '请求幂等键',
  `resource_type` VARCHAR(64) NULL COMMENT '返回资源',
  `resource_id` VARCHAR(96) NULL COMMENT '结果资源ID',
  `status` VARCHAR(32) NOT NULL COMMENT 'IN_PROGRESS/SUCCEEDED/FAILED',
  `request_hash` VARCHAR(64) NULL COMMENT '请求Hash',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idempotency_reco_1` (`tenant_id`,`scope_code`,`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同步请求幂等记录';

CREATE TABLE IF NOT EXISTS `crm_outbox_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `event_id` VARCHAR(64) NOT NULL COMMENT 'UUID事件ID',
  `event_type` VARCHAR(96) NOT NULL COMMENT '事件类型',
  `event_version` INT NOT NULL DEFAULT 0 COMMENT 'schema版本',
  `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合类型',
  `aggregate_id` VARCHAR(96) NOT NULL COMMENT '业务聚合ID',
  `partition_key` VARCHAR(128) NOT NULL COMMENT 'Kafka分区Key',
  `payload_json` JSON NULL COMMENT '事件数据',
  `status` VARCHAR(32) NOT NULL COMMENT 'NEW/SENT/FAILED',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  `sent_at` DATETIME(3) NULL COMMENT '成功发送时间',
  `last_error` TEXT NULL COMMENT '失败原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbox_event_1` (`tenant_id`,`event_id`),
  KEY `idx_outbox_event_1` (`tenant_id`,`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可靠事件投递表';

CREATE TABLE IF NOT EXISTS `crm_inbox_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `consumer_group` VARCHAR(96) NOT NULL COMMENT '消费者组',
  `event_id` VARCHAR(64) NOT NULL COMMENT 'UUID事件ID',
  `processed_at` DATETIME(3) NULL COMMENT '成功处理时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbox_event_1` (`tenant_id`,`consumer_group`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消费者幂等收件箱';

-- 登录会话表（参考 SQL 未包含；依据 docs/01_需求与产品/12_角色权限与数据隔离.md 第 4 节 login_session 建议新增）。
-- 只存令牌 SHA-256 哈希，不落明文 token；吊销/过期即失效。
CREATE TABLE IF NOT EXISTS `sys_login_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '登录用户',
  `token_hash` VARCHAR(128) NOT NULL COMMENT '访问令牌SHA-256哈希(不存明文)',
  `client_ip` VARCHAR(64) NULL COMMENT '登录IP',
  `user_agent` VARCHAR(255) NULL COMMENT '登录User-Agent',
  `expires_at` DATETIME(3) NOT NULL COMMENT '过期时间',
  `revoked_at` DATETIME(3) NULL COMMENT '吊销时间(logout/停用/权限回收)',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_login_session_1` (`tenant_id`,`token_hash`),
  KEY `idx_login_session_1` (`tenant_id`,`user_id`,`revoked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录会话(服务端可吊销)';
