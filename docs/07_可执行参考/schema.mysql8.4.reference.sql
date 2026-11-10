-- CRM V2.0 MySQL 8.4 LTS development baseline (reference)
-- Important: reviewed incremental changes must become Flyway migrations.
-- All monetary figures in fen (INTEGER), not floating point.
-- No real personal information or passwords in this file.
CREATE DATABASE IF NOT EXISTS `medical_crm` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `medical_crm`;
SET NAMES utf8mb4;


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

CREATE TABLE IF NOT EXISTS `sys_dictionary_type` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `dict_code` VARCHAR(64) NOT NULL COMMENT '字典编码',
  `dict_name` VARCHAR(96) NOT NULL COMMENT '字典名称',
  `status` VARCHAR(32) NOT NULL COMMENT '状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dictionary_type_1` (`tenant_id`,`dict_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可维护字典类型';

CREATE TABLE IF NOT EXISTS `sys_dictionary_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `dict_type_id` BIGINT UNSIGNED NOT NULL COMMENT '字典类型',
  `option_code` VARCHAR(96) NOT NULL COMMENT '不可变业务代码',
  `option_label` VARCHAR(128) NOT NULL COMMENT '中文展示名称',
  `sort_no` INT NOT NULL DEFAULT 0 COMMENT '排序',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE/ARCHIVED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dictionary_item_1` (`tenant_id`,`dict_type_id`,`option_code`),
  KEY `idx_dictionary_item_1` (`tenant_id`,`dict_type_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典选项';

CREATE TABLE IF NOT EXISTS `crm_customer` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '当前专属管家用户ID',
  `team_id` BIGINT UNSIGNED NULL COMMENT '所属团队',
  `customer_no` VARCHAR(48) NOT NULL COMMENT '公开客户编号',
  `display_name` VARCHAR(96) NULL COMMENT '客户姓名/称呼',
  `nickname` VARCHAR(128) NULL COMMENT '客户昵称',
  `remark_name` VARCHAR(128) NULL COMMENT '备注名',
  `mobile_cipher` VARCHAR(512) NULL COMMENT '加密手机号',
  `mobile_hash` VARCHAR(64) NULL COMMENT '去重专用不可逆哈希',
  `city_code` VARCHAR(64) NULL COMMENT '城市字典',
  `source_code` VARCHAR(64) NULL COMMENT '来源字典',
  `channel_code` VARCHAR(64) NULL COMMENT '渠道字典',
  `customer_status` VARCHAR(64) NULL COMMENT '客户状态',
  `tier_code` VARCHAR(16) NULL COMMENT 'S/A/B/C',
  `intent_level` VARCHAR(16) NULL COMMENT '意向级别',
  `spending_level` VARCHAR(16) NULL COMMENT '消费能力',
  `birthday` DATE NULL COMMENT '生日',
  `points_cached` INT NOT NULL DEFAULT 0 COMMENT '积分缓存，非最终账本',
  `attention_projects` VARCHAR(512) NULL COMMENT '关注项目',
  `blocker_note` TEXT NULL COMMENT '卡点情况',
  `remark` TEXT NULL COMMENT '客户备注',
  `first_added_at` DATETIME(3) NULL COMMENT '客户首次添加时间',
  `first_order_at` DATETIME(3) NULL COMMENT '首次下单时间',
  `last_followup_at` DATETIME(3) NULL COMMENT '最后跟进时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '归档/软删除',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_1` (`tenant_id`,`customer_no`),
  KEY `idx_customer_1` (`tenant_id`,`owner_staff_id`,`customer_status`),
  KEY `idx_customer_2` (`tenant_id`,`mobile_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM客户主档案';

CREATE TABLE IF NOT EXISTS `crm_customer_identity` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `identity_type` VARCHAR(48) NOT NULL COMMENT 'WECOM/PHONE/YOUZAN/LEGACY',
  `source_app` VARCHAR(96) NOT NULL COMMENT 'corp/app/shop标识',
  `external_id` VARCHAR(192) NOT NULL COMMENT '外部身份',
  `verified_at` DATETIME(3) NULL COMMENT '核验时间',
  `status` VARCHAR(32) NOT NULL COMMENT '已关联/待核验',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_identit_1` (`tenant_id`,`identity_type`,`source_app`,`external_id`),
  KEY `idx_customer_identit_1` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户来源身份映射';

CREATE TABLE IF NOT EXISTS `crm_customer_tag` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `tag_code` VARCHAR(64) NOT NULL COMMENT '标签字典代码',
  `created_by` BIGINT UNSIGNED NULL COMMENT '标记者',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_tag_1` (`tenant_id`,`customer_id`,`tag_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户标签';

CREATE TABLE IF NOT EXISTS `crm_customer_owner_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `old_owner_id` BIGINT UNSIGNED NOT NULL COMMENT '原管家',
  `new_owner_id` BIGINT UNSIGNED NOT NULL COMMENT '新管家',
  `changed_by` BIGINT UNSIGNED NOT NULL COMMENT '操作人',
  `effective_at` DATETIME(3) NULL COMMENT '实际生效时间',
  `reason` TEXT NULL COMMENT '变更原因',
  `approval_ref` VARCHAR(96) NULL COMMENT '审批单',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_customer_owner_h_1` (`tenant_id`,`customer_id`,`effective_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='归属变更不可覆盖日志';

CREATE TABLE IF NOT EXISTS `crm_merge_case` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `primary_customer_id` BIGINT UNSIGNED NOT NULL COMMENT '主客户',
  `secondary_customer_id` BIGINT UNSIGNED NOT NULL COMMENT '被合并客户',
  `status` VARCHAR(32) NOT NULL COMMENT 'SUGGESTED/CONFIRMED/REJECTED',
  `match_score` DECIMAL(10,6) NULL COMMENT '相似度0-1',
  `match_evidence` JSON NULL COMMENT '相似证据',
  `reviewer_id` BIGINT UNSIGNED NULL COMMENT '审核人',
  `reviewed_at` DATETIME(3) NULL COMMENT '审核时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merge_case_1` (`tenant_id`,`primary_customer_id`,`secondary_customer_id`),
  KEY `idx_merge_case_1` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='疑似重复客户合并审批';

CREATE TABLE IF NOT EXISTS `crm_points_ledger` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `delta` INT NOT NULL DEFAULT 0 COMMENT '积分正负变动',
  `reason_code` VARCHAR(48) NOT NULL COMMENT '积分调整类型',
  `source_type` VARCHAR(48) NOT NULL COMMENT '来源类型',
  `source_ref` VARCHAR(96) NOT NULL COMMENT '来源ID',
  `operator_user_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_points_ledger_1` (`tenant_id`,`source_type`,`source_ref`),
  KEY `idx_points_ledger_1` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会员积分账本';

CREATE TABLE IF NOT EXISTS `crm_followup` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '管家',
  `channel_code` VARCHAR(64) NOT NULL COMMENT '沟通方式',
  `contact_at` DATETIME(3) NULL COMMENT '沟通时间',
  `summary` TEXT NULL COMMENT '沟通内容',
  `intent_project` VARCHAR(192) NULL COMMENT '意向项目',
  `estimated_amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '预计金额',
  `next_followup_at` DATETIME(3) NULL COMMENT '下次跟进',
  `blocker_note` TEXT NULL COMMENT '卡点',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '登记人',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_followup_1` (`tenant_id`,`customer_id`,`contact_at`),
  KEY `idx_followup_2` (`tenant_id`,`owner_staff_id`,`next_followup_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日沟通流水';

CREATE TABLE IF NOT EXISTS `crm_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NULL COMMENT '可空客户',
  `assignee_user_id` BIGINT UNSIGNED NOT NULL COMMENT '负责人',
  `task_type` VARCHAR(48) NOT NULL COMMENT '回访/预约/退款',
  `priority` VARCHAR(16) NOT NULL COMMENT 'LOW/MEDIUM/HIGH',
  `status` VARCHAR(32) NOT NULL COMMENT 'OPEN/DONE/CANCELED',
  `title` VARCHAR(255) NOT NULL COMMENT '任务标题',
  `description` TEXT NULL COMMENT '任务描述',
  `due_at` DATETIME(3) NULL COMMENT '截止日期',
  `source_type` VARCHAR(48) NOT NULL COMMENT '来源类型',
  `source_ref` VARCHAR(128) NULL COMMENT '来源 ID',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_source` (`tenant_id`,`source_type`,`source_ref`),
  KEY `idx_task_1` (`tenant_id`,`assignee_user_id`,`due_at`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人工、跟进规则与 AI 待办';

CREATE TABLE IF NOT EXISTS `crm_institution` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `institution_no` VARCHAR(48) NOT NULL COMMENT '机构编码',
  `full_name` VARCHAR(160) NOT NULL COMMENT '机构全称',
  `short_name` VARCHAR(96) NULL COMMENT '简称',
  `city_code` VARCHAR(64) NULL COMMENT '城市',
  `business_type_code` VARCHAR(64) NULL COMMENT '医美/口腔/私密/综合',
  `ownership_code` VARCHAR(64) NULL COMMENT '公立/民营',
  `grade_code` VARCHAR(16) NULL COMMENT 'S/A/B/C',
  `address` VARCHAR(255) NULL COMMENT '地址',
  `contact_name` VARCHAR(128) NULL COMMENT '负责人',
  `contact_phone_cipher` VARCHAR(512) NULL COMMENT '加密联系电话',
  `status` VARCHAR(32) NOT NULL COMMENT '待审核/合作中/停止合作',
  `settlement_type` VARCHAR(48) NOT NULL COMMENT '月结/次卡次结',
  `online_rate` DECIMAL(10,6) NULL COMMENT '线上佣金率',
  `offline_rate` DECIMAL(10,6) NULL COMMENT '线下佣金率',
  `platform_rate` DECIMAL(10,6) NULL COMMENT '点评等平台佣金率',
  `other_rate_rule` TEXT NULL COMMENT '其他佣金',
  `charges_fee` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否收费',
  `deposit_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '保证金分',
  `guarantee_roi` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否保投产',
  `roi_terms` TEXT NULL COMMENT '保投产约定',
  `settlement_name` VARCHAR(160) NULL COMMENT '机构结款名称',
  `bank_name_cipher` VARCHAR(512) NULL COMMENT '加密开户行',
  `bank_account_cipher` VARCHAR(512) NULL COMMENT '加密银行账户',
  `tax_id_cipher` VARCHAR(512) NULL COMMENT '加密税号',
  `introduction` TEXT NULL COMMENT '机构简介',
  `archived` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '归档',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_institution_1` (`tenant_id`,`institution_no`),
  KEY `idx_institution_1` (`tenant_id`,`city_code`,`status`),
  KEY `idx_institution_2` (`tenant_id`,`grade_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合作机构主档案';

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

CREATE TABLE IF NOT EXISTS `crm_institution_service` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '机构',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '子项目',
  `status` VARCHAR(32) NOT NULL COMMENT '启用状态',
  `effective_at` DATETIME(3) NULL COMMENT '生效时间',
  `expire_at` DATETIME(3) NULL COMMENT '到期时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_institution_serv_1` (`tenant_id`,`institution_id`,`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可提供的机构子项目及条件';

CREATE TABLE IF NOT EXISTS `crm_institution_contract` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '机构',
  `contract_no` VARCHAR(96) NOT NULL COMMENT '合同编号',
  `contract_version` INT NOT NULL DEFAULT 0 COMMENT '版本号',
  `valid_from` DATE NULL COMMENT '开始日期',
  `valid_to` DATE NULL COMMENT '到期日期',
  `status` VARCHAR(32) NOT NULL COMMENT 'DRAFT/ACTIVE/EXPIRED',
  `file_id` BIGINT UNSIGNED NULL COMMENT '合同附件引用',
  `terms_snapshot` TEXT NULL COMMENT '结算/收费条款快照',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_institution_cont_1` (`tenant_id`,`contract_no`,`contract_version`),
  KEY `idx_institution_cont_1` (`tenant_id`,`institution_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机构合同及版本';

CREATE TABLE IF NOT EXISTS `crm_institution_qualification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '机构',
  `qualification_type` VARCHAR(64) NOT NULL COMMENT '医疗资质/营业执照',
  `license_no` VARCHAR(128) NULL COMMENT '证照号',
  `valid_to` DATE NULL COMMENT '有效期',
  `file_id` BIGINT UNSIGNED NULL COMMENT '证照附件',
  `status` VARCHAR(32) NOT NULL COMMENT '有效/到期/待复核',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_institution_qual_1` (`tenant_id`,`institution_id`,`valid_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资质证照与到期预警';

CREATE TABLE IF NOT EXISTS `crm_profile_change_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '机构',
  `field_code` VARCHAR(64) NOT NULL COMMENT '字段代码',
  `old_value_cipher` TEXT NULL COMMENT '旧值加密/脱敏',
  `new_value_cipher` TEXT NULL COMMENT '新值加密/脱敏',
  `base_version` INT NOT NULL DEFAULT 0 COMMENT '申请时机构版本',
  `reason` TEXT NULL COMMENT '申请原因',
  `approval_route` VARCHAR(48) NOT NULL COMMENT 'MARKET/FINANCE/BOTH',
  `status` VARCHAR(48) NOT NULL COMMENT '待审/生效/拒绝',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '申请账号',
  `applied_at` DATETIME(3) NULL COMMENT '生效时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_profile_change_r_1` (`tenant_id`,`institution_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机构敏感字段变更申请';

CREATE TABLE IF NOT EXISTS `crm_profile_change_step` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `request_id` BIGINT UNSIGNED NOT NULL COMMENT '变更单',
  `step_order` INT NOT NULL DEFAULT 0 COMMENT '步骤顺序',
  `role_code` VARCHAR(64) NOT NULL COMMENT 'MARKET/FINANCE',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/PASSED/REJECTED',
  `reviewer_id` BIGINT UNSIGNED NULL COMMENT '审核人',
  `reviewed_at` DATETIME(3) NULL COMMENT '审核时间',
  `comment` TEXT NULL COMMENT '审核意见',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_profile_change_s_1` (`tenant_id`,`request_id`,`step_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资料变更分级审批步骤';

CREATE TABLE IF NOT EXISTS `crm_partner_channel` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `channel_no` VARCHAR(48) NOT NULL COMMENT '渠道编号',
  `name` VARCHAR(160) NOT NULL COMMENT '达人/渠道名称',
  `platform_code` VARCHAR(64) NULL COMMENT '微博/公众号等',
  `business_owner_user_id` BIGINT UNSIGNED NULL COMMENT '对接商务',
  `status` VARCHAR(32) NOT NULL COMMENT '合作/暂停/归档',
  `remarks` TEXT NULL COMMENT '备注',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_partner_channel_1` (`tenant_id`,`channel_no`),
  KEY `idx_partner_channel_1` (`tenant_id`,`business_owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='达人/合作渠道';

CREATE TABLE IF NOT EXISTS `crm_project` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `project_code` VARCHAR(64) NOT NULL COMMENT '项目编码',
  `project_name` VARCHAR(160) NOT NULL COMMENT '项目标准名',
  `category_code` VARCHAR(64) NULL COMMENT '项目分类',
  `list_price_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '参考单价分',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_1` (`tenant_id`,`project_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品子项目定义';

CREATE TABLE IF NOT EXISTS `crm_package` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `package_code` VARCHAR(64) NOT NULL COMMENT '套餐编码',
  `package_name` VARCHAR(160) NOT NULL COMMENT '套餐标准名',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_package_1` (`tenant_id`,`package_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卡项/套餐主定义';

CREATE TABLE IF NOT EXISTS `crm_package_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `package_id` BIGINT UNSIGNED NOT NULL COMMENT '套餐',
  `version_no` INT NOT NULL DEFAULT 0 COMMENT '版本号',
  `selection_mode` VARCHAR(32) NOT NULL COMMENT 'FIXED/CHOOSE_N/USE_N',
  `choice_count` INT NOT NULL DEFAULT 0 COMMENT '可选N项',
  `selection_rules` TEXT NULL COMMENT '选项规则和重复规则',
  `status` VARCHAR(32) NOT NULL COMMENT 'DRAFT/ACTIVE/ARCHIVED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_package_version_1` (`tenant_id`,`package_id`,`version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售版本快照模板';

CREATE TABLE IF NOT EXISTS `crm_package_project` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `package_version_id` BIGINT UNSIGNED NOT NULL COMMENT '套餐版本',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '子项目',
  `grant_count` INT NOT NULL DEFAULT 0 COMMENT '购买时发放次数或可选上限',
  `selection_weight` INT NOT NULL DEFAULT 0 COMMENT '选择权重或配额',
  `allocation_price_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '金额分摊基准',
  `option_group` VARCHAR(48) NOT NULL COMMENT '选项组编码',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_package_project_1` (`tenant_id`,`package_version_id`,`project_id`,`option_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='套餐版本包含的项目与权益';

CREATE TABLE IF NOT EXISTS `crm_sku` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `sku_code` VARCHAR(96) NOT NULL COMMENT '稳定不可重复SKU',
  `package_version_id` BIGINT UNSIGNED NOT NULL COMMENT '套餐版本',
  `spec_name` VARCHAR(128) NOT NULL COMMENT '3次/6次/选3项等',
  `sale_price_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '售价分',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `on_sale_at` DATETIME(3) NULL COMMENT '上架时间',
  `off_sale_at` DATETIME(3) NULL COMMENT '下架时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_1` (`tenant_id`,`sku_code`),
  KEY `idx_sku_1` (`tenant_id`,`package_version_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='具体可售 SKU（规格）';

CREATE TABLE IF NOT EXISTS `crm_sku_institution` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT 'SKU',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '医院',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_institution_1` (`tenant_id`,`sku_id`,`institution_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SKU适用机构';

CREATE TABLE IF NOT EXISTS `crm_campaign` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `campaign_code` VARCHAR(64) NOT NULL COMMENT '活动编码',
  `campaign_name` VARCHAR(160) NOT NULL COMMENT '活动名称',
  `campaign_type` VARCHAR(32) NOT NULL COMMENT 'REGULAR/FLASH/PROMOTION',
  `starts_at` DATETIME(3) NULL COMMENT '活动开始',
  `ends_at` DATETIME(3) NULL COMMENT '活动结束',
  `status` VARCHAR(32) NOT NULL COMMENT '活动状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_campaign_1` (`tenant_id`,`campaign_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动/快闪/大促';

CREATE TABLE IF NOT EXISTS `crm_campaign_batch` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `campaign_id` BIGINT UNSIGNED NOT NULL COMMENT '活动',
  `batch_code` VARCHAR(64) NOT NULL COMMENT '批次编码',
  `batch_name` VARCHAR(96) NOT NULL COMMENT '批次名称',
  `starts_at` DATETIME(3) NULL COMMENT '开始',
  `ends_at` DATETIME(3) NULL COMMENT '结束',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_campaign_batch_1` (`tenant_id`,`campaign_id`,`batch_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动分波次';

CREATE TABLE IF NOT EXISTS `crm_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `order_no` VARCHAR(64) NOT NULL COMMENT 'CRM订单编号',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `sold_by_user_id` BIGINT UNSIGNED NOT NULL COMMENT '成交管家',
  `owner_at_sale_id` BIGINT UNSIGNED NOT NULL COMMENT '销售时客户归属快照',
  `external_order_no` VARCHAR(128) NULL COMMENT '第三方订单号',
  `source_system` VARCHAR(48) NOT NULL COMMENT 'CRM/YOUZAN',
  `external_shop_id` VARCHAR(96) NULL COMMENT '第三方店铺标识',
  `order_type_code` VARCHAR(48) NOT NULL COMMENT '普通/快闪/大促',
  `campaign_id` BIGINT UNSIGNED NULL COMMENT '活动',
  `campaign_batch_id` BIGINT UNSIGNED NULL COMMENT '批次',
  `status` VARCHAR(48) NOT NULL COMMENT '订单状态',
  `receiver_type` VARCHAR(32) NOT NULL COMMENT 'PLATFORM/INSTITUTION',
  `list_amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '原价分',
  `discount_amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '优惠分',
  `payable_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '应付分',
  `paid_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '已确认实付分',
  `refunded_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '成功退款分',
  `ordered_at` DATETIME(3) NULL COMMENT '订单日期',
  `paid_at` DATETIME(3) NULL COMMENT '支付完成时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_1` (`tenant_id`,`order_no`),
  UNIQUE KEY `uk_order_2` (`tenant_id`,`source_system`,`external_shop_id`,`external_order_no`),
  KEY `idx_order_1` (`tenant_id`,`customer_id`,`status`),
  KEY `idx_order_2` (`tenant_id`,`sold_by_user_id`,`paid_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM订单事实';

CREATE TABLE IF NOT EXISTS `crm_order_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '购买的SKU',
  `package_version_id` BIGINT UNSIGNED NOT NULL COMMENT '套餐版本',
  `sku_code_snapshot` VARCHAR(96) NULL COMMENT '当时SKU编码',
  `sku_name_snapshot` VARCHAR(160) NOT NULL COMMENT '当时卡项/规格名',
  `purchase_quantity` INT NOT NULL DEFAULT 0 COMMENT '购买销售份数',
  `list_amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '原价',
  `paid_allocated_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '该行分摊实付',
  `entitlement_snapshot` JSON NULL COMMENT '选项目/次数/机构/有效期快照',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_order_item_1` (`tenant_id`,`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售时订单行快照';

CREATE TABLE IF NOT EXISTS `crm_payment_txn` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单',
  `payment_channel` VARCHAR(48) NOT NULL COMMENT '平台/银行/线下',
  `receiver_type` VARCHAR(32) NOT NULL COMMENT 'PLATFORM/INSTITUTION',
  `external_trade_no` VARCHAR(128) NULL COMMENT '支付平台交易号',
  `amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '支付金额',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/CONFIRMED/FAILED/REVERSED',
  `confirmed_at` DATETIME(3) NULL COMMENT '确认时间',
  `confirmed_by` BIGINT UNSIGNED NULL COMMENT '确认人',
  `voucher_file_id` BIGINT UNSIGNED NULL COMMENT '支付凭证',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_txn_1` (`tenant_id`,`payment_channel`,`external_trade_no`),
  KEY `idx_payment_txn_1` (`tenant_id`,`order_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实际支付流水';

CREATE TABLE IF NOT EXISTS `crm_entitlement` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `order_item_id` BIGINT UNSIGNED NOT NULL COMMENT '购买订单行',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '项目',
  `granted_count` INT NOT NULL DEFAULT 0 COMMENT '已发放次数累计',
  `available_count` INT NOT NULL DEFAULT 0 COMMENT '可用次数事务快照',
  `version_no` INT NOT NULL DEFAULT 0 COMMENT '余额乐观锁业务版本',
  `valid_from` DATE NULL COMMENT '起始日期',
  `valid_to` DATE NULL COMMENT '到期日期',
  `status` VARCHAR(32) NOT NULL COMMENT 'AVAILABLE/EXPIRED/EXHAUSTED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entitlement_1` (`tenant_id`,`order_item_id`,`project_id`),
  KEY `idx_entitlement_1` (`tenant_id`,`customer_id`,`status`),
  KEY `idx_entitlement_2` (`tenant_id`,`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户子项目权益批次';

CREATE TABLE IF NOT EXISTS `crm_entitlement_ledger` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `entitlement_id` BIGINT UNSIGNED NOT NULL COMMENT '权益批次',
  `txn_type` VARCHAR(48) NOT NULL COMMENT 'GRANT/REDEEM/REFUND_REVOKE/CORRECTION',
  `delta_count` INT NOT NULL DEFAULT 0 COMMENT '次数正负增减',
  `source_type` VARCHAR(48) NOT NULL COMMENT '来源类型',
  `source_ref` VARCHAR(128) NOT NULL COMMENT '业务来源标识',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '幂等唯一键',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '操作人',
  `reason` TEXT NULL COMMENT '原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entitlement_ledg_1` (`tenant_id`,`idempotency_key`),
  UNIQUE KEY `uk_entitlement_ledg_2` (`tenant_id`,`source_type`,`source_ref`,`txn_type`,`entitlement_id`),
  KEY `idx_entitlement_ledg_1` (`tenant_id`,`entitlement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权益变动不可覆盖账本';

CREATE TABLE IF NOT EXISTS `crm_appointment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '机构',
  `entitlement_id` BIGINT UNSIGNED NULL COMMENT '拟使用权益',
  `manager_user_id` BIGINT UNSIGNED NOT NULL COMMENT '管家',
  `status` VARCHAR(48) NOT NULL COMMENT '待确认/已确认/已到院等',
  `scheduled_at` DATETIME(3) NULL COMMENT '预约到院时间',
  `confirmed_at` DATETIME(3) NULL COMMENT '机构接诊确认时间',
  `checked_in_at` DATETIME(3) NULL COMMENT '真实到院时间',
  `notes` TEXT NULL COMMENT '备注',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_appointment_1` (`tenant_id`,`institution_id`,`status`,`scheduled_at`),
  KEY `idx_appointment_2` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='到院预约';

CREATE TABLE IF NOT EXISTS `crm_redemption` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `appointment_id` BIGINT UNSIGNED NOT NULL COMMENT '预约',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '实际服务医院',
  `entitlement_id` BIGINT UNSIGNED NOT NULL COMMENT '权益',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '实际项目',
  `count` INT NOT NULL DEFAULT 0 COMMENT '本次核销次数',
  `allocated_amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '按约定分摊核销金额',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/POSTED/REJECTED/REVERSED',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '请求幂等键',
  `verified_by_institution_user_id` BIGINT UNSIGNED NULL COMMENT '机构最终确认者',
  `service_at` DATETIME(3) NULL COMMENT '实际服务日期',
  `posted_at` DATETIME(3) NULL COMMENT '正式核销时间',
  `reversal_of` VARCHAR(128) NULL COMMENT '冲正原流水号',
  `voucher_file_id` BIGINT UNSIGNED NULL COMMENT '履约凭证',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_redemption_1` (`tenant_id`,`idempotency_key`),
  KEY `idx_redemption_1` (`tenant_id`,`institution_id`,`posted_at`),
  KEY `idx_redemption_2` (`tenant_id`,`entitlement_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医院确认的核销记录';

CREATE TABLE IF NOT EXISTS `crm_refund` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `refund_no` VARCHAR(64) NOT NULL COMMENT '退款编号',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '原订单',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `requested_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '申请金额',
  `approved_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '批准金额',
  `succeeded_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '实际成功退款金额',
  `reason_code` VARCHAR(64) NOT NULL COMMENT '退款原因',
  `status` VARCHAR(48) NOT NULL COMMENT 'REQUESTED/APPROVED/PROCESSING/SUCCEEDED/FAILED',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '申请人',
  `approved_by` BIGINT UNSIGNED NULL COMMENT '审批人',
  `succeeded_at` DATETIME(3) NULL COMMENT '成功时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_1` (`tenant_id`,`refund_no`),
  KEY `idx_refund_1` (`tenant_id`,`order_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款业务主单';

CREATE TABLE IF NOT EXISTS `crm_refund_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `refund_id` BIGINT UNSIGNED NOT NULL COMMENT '退款主单',
  `entitlement_id` BIGINT UNSIGNED NOT NULL COMMENT '权益',
  `revoke_count` INT NOT NULL DEFAULT 0 COMMENT '拟回收剩余次数',
  `allocated_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '对应现金金额',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_item_1` (`tenant_id`,`refund_id`,`entitlement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款对应已购服务权益';

CREATE TABLE IF NOT EXISTS `crm_refund_payment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `refund_id` BIGINT UNSIGNED NOT NULL COMMENT '退款',
  `provider` VARCHAR(48) NOT NULL COMMENT '支付来源',
  `external_refund_no` VARCHAR(128) NULL COMMENT '外部退款单号',
  `amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '实际退款金额',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/SUCCEEDED/FAILED',
  `succeeded_at` DATETIME(3) NULL COMMENT '退款成功回执',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_payment_1` (`tenant_id`,`provider`,`external_refund_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实际退款通道结果';

CREATE TABLE IF NOT EXISTS `crm_settlement_batch` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '机构',
  `period_key` VARCHAR(32) NOT NULL COMMENT '结算期YYYYMM或批次',
  `status` VARCHAR(32) NOT NULL COMMENT 'DRAFT/CONFIRMED/PAID',
  `gross_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '核销总额',
  `commission_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '佣金分',
  `net_payable_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '应结净额',
  `paid_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '已付款额',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_batch_1` (`tenant_id`,`institution_id`,`period_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医院结算账单批次';

CREATE TABLE IF NOT EXISTS `crm_settlement_line` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `settlement_batch_id` BIGINT UNSIGNED NOT NULL COMMENT '结算批次',
  `redemption_id` BIGINT UNSIGNED NOT NULL COMMENT '核销',
  `gross_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '核销金额',
  `commission_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '佣金',
  `payable_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '净额',
  `rate_snapshot` JSON NULL COMMENT '当时佣金规则快照',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_line_1` (`tenant_id`,`redemption_id`),
  UNIQUE KEY `uk_settlement_line_2` (`tenant_id`,`settlement_batch_id`,`redemption_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医院结算明细';

CREATE TABLE IF NOT EXISTS `crm_settlement_payment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `settlement_batch_id` BIGINT UNSIGNED NOT NULL COMMENT '结算批次',
  `amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '支付金额',
  `bank_trade_no` VARCHAR(128) NULL COMMENT '银行流水',
  `paid_at` DATETIME(3) NULL COMMENT '付款时间',
  `voucher_file_id` BIGINT UNSIGNED NULL COMMENT '付款凭证',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_payme_1` (`tenant_id`,`bank_trade_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机构打款或收款凭证';

CREATE TABLE IF NOT EXISTS `crm_youzan_order_raw` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(96) NOT NULL COMMENT '店铺/应用标识',
  `external_order_no` VARCHAR(128) NOT NULL COMMENT '有赞订单号',
  `sync_status` VARCHAR(48) NOT NULL COMMENT 'RECEIVED/MAPPED/ERROR',
  `payload_json` JSON NULL COMMENT '脱敏后的接口数据',
  `payload_object_ref` VARCHAR(255) NULL COMMENT '加密完整payload对象引用',
  `source_updated_at` DATETIME(3) NULL COMMENT '源端更新时间',
  `last_error` TEXT NULL COMMENT '同步异常',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_order_raw_1` (`tenant_id`,`shop_id`,`external_order_no`),
  KEY `idx_youzan_order_raw_1` (`tenant_id`,`sync_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞订单原始数据引用';

CREATE TABLE IF NOT EXISTS `crm_youzan_sku_mapping` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(96) NOT NULL COMMENT '店铺',
  `external_sku_id` VARCHAR(128) NOT NULL COMMENT '外部SKU',
  `crm_sku_id` BIGINT UNSIGNED NOT NULL COMMENT '内部SKU',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_sku_mappi_1` (`tenant_id`,`shop_id`,`external_sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞商品映射SKU';

CREATE TABLE IF NOT EXISTS `crm_wecom_contact` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企业ID',
  `external_userid` VARCHAR(192) NOT NULL COMMENT '企微外部客户ID',
  `customer_id` BIGINT UNSIGNED NULL COMMENT '客户',
  `staff_user_id` BIGINT UNSIGNED NOT NULL COMMENT '关联企微管家员工',
  `consent_status` VARCHAR(32) NOT NULL COMMENT '同意状态',
  `last_synced_at` DATETIME(3) NULL COMMENT '最近同步',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_contact_1` (`tenant_id`,`corp_id`,`external_userid`,`staff_user_id`),
  KEY `idx_wecom_contact_1` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微外部联系人身份';

CREATE TABLE IF NOT EXISTS `crm_wecom_cursor` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企业ID',
  `archive_scope` VARCHAR(128) NOT NULL COMMENT '拉取范围',
  `last_seq` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最新成功seq',
  `last_success_at` DATETIME(3) NULL COMMENT '最后成功同步',
  `status` VARCHAR(32) NOT NULL COMMENT 'RUNNING/ERROR',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_cursor_1` (`tenant_id`,`corp_id`,`archive_scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微会话归档采集游标';

CREATE TABLE IF NOT EXISTS `crm_wecom_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `corp_id` VARCHAR(96) NOT NULL COMMENT '企业ID',
  `msg_id` VARCHAR(192) NOT NULL COMMENT '消息唯一ID',
  `archive_seq` BIGINT UNSIGNED NOT NULL COMMENT '拉取序号',
  `customer_id` BIGINT UNSIGNED NULL COMMENT '关联客户',
  `conversation_id` VARCHAR(128) NULL COMMENT '会话ID',
  `message_type` VARCHAR(48) NOT NULL COMMENT '消息类型',
  `sender_type` VARCHAR(32) NOT NULL COMMENT 'EMPLOYEE/CUSTOMER',
  `sent_at` DATETIME(3) NULL COMMENT '发送时间',
  `encrypted_object_ref` VARCHAR(255) NULL COMMENT '加密原文存储引用',
  `status` VARCHAR(32) NOT NULL COMMENT 'STORED/UNMATCHED/REDACTED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_message_1` (`tenant_id`,`corp_id`,`msg_id`),
  KEY `idx_wecom_message_1` (`tenant_id`,`customer_id`,`sent_at`),
  KEY `idx_wecom_message_2` (`tenant_id`,`corp_id`,`archive_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='已合法归档的聊天消息元数据';

CREATE TABLE IF NOT EXISTS `crm_ai_insight` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `conversation_id` VARCHAR(128) NULL COMMENT '会话',
  `insight_type` VARCHAR(48) NOT NULL COMMENT '预约/退款/投诉/复购',
  `status` VARCHAR(32) NOT NULL COMMENT 'PROPOSED/CONFIRMED/REJECTED',
  `confidence` DECIMAL(10,6) NULL COMMENT '置信度0-1',
  `evidence_message_ids` JSON NULL COMMENT '证据消息ID',
  `extracted_json` JSON NULL COMMENT '结构化建议',
  `model_version` VARCHAR(128) NULL COMMENT '模型版本',
  `reviewed_by` BIGINT UNSIGNED NULL COMMENT '审核人',
  `reviewed_at` DATETIME(3) NULL COMMENT '审核日期',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_ai_insight_1` (`tenant_id`,`customer_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI意图/摘要与证据';

CREATE TABLE IF NOT EXISTS `crm_material` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `material_no` VARCHAR(64) NOT NULL COMMENT '素材编号',
  `title` VARCHAR(160) NOT NULL COMMENT '素材标题',
  `category_code` VARCHAR(48) NOT NULL COMMENT '素材类型',
  `institution_id` BIGINT UNSIGNED NULL COMMENT '机构',
  `project_id` BIGINT UNSIGNED NULL COMMENT '项目',
  `city_code` VARCHAR(64) NULL COMMENT '城市',
  `file_id` BIGINT UNSIGNED NULL COMMENT '对象文件ID',
  `review_status` VARCHAR(32) NOT NULL COMMENT 'DRAFT/PENDING/APPROVED/REJECTED',
  `reviewer_id` BIGINT UNSIGNED NULL COMMENT '审核人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_1` (`tenant_id`,`material_no`),
  KEY `idx_material_1` (`tenant_id`,`institution_id`,`review_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='案例/医生/仪器/产品素材';

CREATE TABLE IF NOT EXISTS `crm_file_object` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `object_key` VARCHAR(255) NOT NULL COMMENT '存储对象Key',
  `original_name` VARCHAR(255) NULL COMMENT '原文件名',
  `mime_type` VARCHAR(128) NOT NULL COMMENT 'MIME',
  `size_bytes` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '大小字节',
  `sha256` VARCHAR(64) NOT NULL COMMENT '内容hash',
  `classification` VARCHAR(48) NOT NULL COMMENT '合同/证照/医疗/普通',
  `scan_status` VARCHAR(32) NOT NULL COMMENT 'PENDING/CLEAN/BLOCKED',
  `uploaded_by` BIGINT UNSIGNED NOT NULL COMMENT '上传人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_object_1` (`tenant_id`,`object_key`),
  KEY `idx_file_object_1` (`tenant_id`,`sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私有文件元信息';

CREATE TABLE IF NOT EXISTS `crm_export_job` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '申请账号',
  `export_type` VARCHAR(48) NOT NULL COMMENT '客户/订单/核销/机构',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
  `filters_json` JSON NULL COMMENT '查询筛选及范围',
  `columns_json` JSON NULL COMMENT '字段清单',
  `reason` TEXT NULL COMMENT '导出理由',
  `row_count` INT NOT NULL DEFAULT 0 COMMENT '实际导出条数',
  `file_id` BIGINT UNSIGNED NULL COMMENT '导出文件',
  `expires_at` DATETIME(3) NULL COMMENT '有效期',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_export_job_1` (`tenant_id`,`requested_by`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异步数据导出';

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

CREATE TABLE IF NOT EXISTS `crm_customer_legacy` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `legacy_table` VARCHAR(128) NULL COMMENT '原表名称',
  `legacy_row_id` VARCHAR(128) NULL COMMENT '原表记录ID',
  `legacy_submitted_at` DATETIME(3) NULL COMMENT '原提交时间',
  `legacy_first_import_at` DATETIME(3) NULL COMMENT '原首次入库时间',
  `legacy_account` VARCHAR(128) NULL COMMENT '原所属账号',
  `legacy_writer` VARCHAR(128) NULL COMMENT '原填写账号',
  `legacy_new_old` VARCHAR(32) NULL COMMENT '原新客老客',
  `legacy_formulas_json` JSON NULL COMMENT '原公式值及计算来源',
  `legacy_full_json` JSON NULL COMMENT '原23字段原值',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_legacy_1` (`tenant_id`,`legacy_table`,`legacy_row_id`),
  KEY `idx_customer_legacy_1` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='原23字段/公式/来源数据保留扩展';

CREATE TABLE IF NOT EXISTS `crm_order_status_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单',
  `from_status` VARCHAR(48) NOT NULL COMMENT '变更前状态',
  `to_status` VARCHAR(48) NOT NULL COMMENT '变更后状态',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '操作人',
  `trigger_type` VARCHAR(48) NOT NULL COMMENT '支付/取消等事件',
  `trigger_ref` VARCHAR(128) NULL COMMENT '事件ID',
  `reason` TEXT NULL COMMENT '理由',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_order_status_his_1` (`tenant_id`,`order_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单状态追加历史';

CREATE TABLE IF NOT EXISTS `crm_appointment_status_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `appointment_id` BIGINT UNSIGNED NOT NULL COMMENT '预约',
  `from_status` VARCHAR(48) NOT NULL COMMENT '原状态',
  `to_status` VARCHAR(48) NOT NULL COMMENT '新状态',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '执行人',
  `reason` TEXT NULL COMMENT '原因',
  `source_ref` VARCHAR(128) NULL COMMENT '来源事件',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_appointment_stat_1` (`tenant_id`,`appointment_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约状态追加历史';

CREATE TABLE IF NOT EXISTS `crm_risk_case` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `risk_case_no` VARCHAR(64) NOT NULL COMMENT '台账编号',
  `risk_type` VARCHAR(64) NOT NULL COMMENT 'MAPPING/PAYMENT/ENTITLEMENT/SECURITY',
  `severity` VARCHAR(24) NOT NULL COMMENT 'HIGH/MEDIUM/LOW',
  `source_type` VARCHAR(48) NOT NULL COMMENT '来源业务类型',
  `source_ref` VARCHAR(128) NOT NULL COMMENT '来源业务ID',
  `status` VARCHAR(32) NOT NULL COMMENT 'OPEN/ASSIGNED/RESOLVED/CLOSED',
  `owner_user_id` BIGINT UNSIGNED NULL COMMENT '认领人',
  `evidence` TEXT NULL COMMENT '风险证据摘要',
  `resolution` TEXT NULL COMMENT '处理结论',
  `resolved_at` DATETIME(3) NULL COMMENT '关闭时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_risk_case_1` (`tenant_id`,`risk_case_no`),
  KEY `idx_risk_case_1` (`tenant_id`,`status`,`severity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异常订单/权限/核销/对账风控台账';

CREATE TABLE IF NOT EXISTS `crm_daily_analytics` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `stat_date` DATE NOT NULL COMMENT '统计日期',
  `dimension_type` VARCHAR(48) NOT NULL COMMENT 'STAFF/INSTITUTION/CHANNEL/CAMPAIGN',
  `dimension_ref` VARCHAR(96) NOT NULL COMMENT '维度ID',
  `metric_code` VARCHAR(64) NOT NULL COMMENT '触达/成交/核销/金额指标',
  `metric_value` BIGINT NOT NULL DEFAULT 0 COMMENT '数值整数;金额指标是分',
  `metric_version` VARCHAR(32) NOT NULL COMMENT '口径版本',
  `source_watermark` DATETIME(3) NULL COMMENT '数据同步水位',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_daily_analytics_1` (`tenant_id`,`stat_date`,`dimension_type`,`dimension_ref`,`metric_code`,`metric_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按维度聚合的运营日报';

CREATE TABLE IF NOT EXISTS `crm_performance_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `period_key` VARCHAR(32) NOT NULL COMMENT 'YYYY-MM',
  `staff_user_id` BIGINT UNSIGNED NOT NULL COMMENT '员工',
  `performance_role` VARCHAR(32) NOT NULL COMMENT 'MANAGER/BUSINESS',
  `gross_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '汇总实付金额',
  `refund_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '退款冲减金额',
  `net_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '净业绩金额',
  `target_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '月目标',
  `rule_version` VARCHAR(32) NOT NULL COMMENT '归属规则版本',
  `status` VARCHAR(32) NOT NULL COMMENT 'PREVIEW/APPROVED',
  `approved_by` BIGINT UNSIGNED NULL COMMENT '审批人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_performance_snap_1` (`tenant_id`,`period_key`,`staff_user_id`,`performance_role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管家/商务月业绩结算快照';

CREATE TABLE IF NOT EXISTS `crm_material_revision` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `material_id` BIGINT UNSIGNED NOT NULL COMMENT '素材',
  `revision_no` INT NOT NULL DEFAULT 0 COMMENT '版本序号',
  `file_id` BIGINT UNSIGNED NULL COMMENT '附件版本',
  `status` VARCHAR(32) NOT NULL COMMENT 'DRAFT/PENDING/APPROVED',
  `reviewer_id` BIGINT UNSIGNED NULL COMMENT '审核人',
  `change_note` TEXT NULL COMMENT '变更内容',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_revisio_1` (`tenant_id`,`material_id`,`revision_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='素材审核与历史版本';

CREATE TABLE IF NOT EXISTS `crm_import_batch` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `batch_no` VARCHAR(64) NOT NULL COMMENT '导入批次ID',
  `source_name` VARCHAR(128) NOT NULL COMMENT '原系统表名',
  `status` VARCHAR(32) NOT NULL COMMENT 'CREATED/VALIDATING/IMPORTING/FINISHED/FAILED',
  `total_rows` INT NOT NULL DEFAULT 0 COMMENT '源数据行数',
  `succeeded_rows` INT NOT NULL DEFAULT 0 COMMENT '成功行数',
  `failed_rows` INT NOT NULL DEFAULT 0 COMMENT '失败行数',
  `source_sha256` VARCHAR(64) NULL COMMENT '原文件摘要',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_import_batch_1` (`tenant_id`,`batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='历史数据导入批次';

CREATE TABLE IF NOT EXISTS `crm_import_row_mapping` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `import_batch_id` BIGINT UNSIGNED NOT NULL COMMENT '导入批次',
  `source_row_id` VARCHAR(128) NOT NULL COMMENT '原行ID',
  `target_type` VARCHAR(64) NOT NULL COMMENT 'CRM目标对象',
  `target_id` BIGINT UNSIGNED NULL COMMENT '目标ID',
  `status` VARCHAR(32) NOT NULL COMMENT 'MAPPED/PENDING/FAILED',
  `mapping_error` TEXT NULL COMMENT '映射失败原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_import_row_mappi_1` (`tenant_id`,`import_batch_id`,`source_row_id`,`target_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='旧数据行到新实体映射';

ALTER TABLE `crm_order` ADD CONSTRAINT `fk_order_customer_id` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_order_item` ADD CONSTRAINT `fk_order_item_order_id` FOREIGN KEY (`order_id`) REFERENCES `crm_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_entitlement` ADD CONSTRAINT `fk_entitlement_order_item_id` FOREIGN KEY (`order_item_id`) REFERENCES `crm_order_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_entitlement_ledger` ADD CONSTRAINT `fk_entitlement_ledg_entitlement_id` FOREIGN KEY (`entitlement_id`) REFERENCES `crm_entitlement` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_appointment` ADD CONSTRAINT `fk_appointment_customer_id` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_appointment` ADD CONSTRAINT `fk_appointment_institution_id` FOREIGN KEY (`institution_id`) REFERENCES `crm_institution` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_redemption` ADD CONSTRAINT `fk_redemption_appointment_id` FOREIGN KEY (`appointment_id`) REFERENCES `crm_appointment` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_redemption` ADD CONSTRAINT `fk_redemption_entitlement_id` FOREIGN KEY (`entitlement_id`) REFERENCES `crm_entitlement` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_refund` ADD CONSTRAINT `fk_refund_order_id` FOREIGN KEY (`order_id`) REFERENCES `crm_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_refund_item` ADD CONSTRAINT `fk_refund_item_refund_id` FOREIGN KEY (`refund_id`) REFERENCES `crm_refund` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_institution_contract` ADD CONSTRAINT `fk_institution_cont_institution_id` FOREIGN KEY (`institution_id`) REFERENCES `crm_institution` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
