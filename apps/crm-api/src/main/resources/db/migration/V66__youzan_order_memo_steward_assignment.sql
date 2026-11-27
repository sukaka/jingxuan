-- V66: 有赞订单商家备注（trade_memo）解析、按「账号」自动/人工分配管家及账号别名映射表。
-- 约束：
-- 1. 仅作用于有赞订单层的归属管家记录与管家/团队数据可见性隔离，绝不自动覆盖已有 CRM 客户归属或自动发放权益；
-- 2. 所有备注与下单表单手机号均经脱敏处理，严禁在展示列落明文手机号或明文凭证；
-- 3. 人工指派（assignment_source='MANUAL'）在后续订单状态同步更新时保持不被自动覆盖。

CREATE TABLE IF NOT EXISTS `crm_youzan_steward_alias` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `alias_name` VARCHAR(64) NOT NULL COMMENT '有赞商家备注「账号」后人名原文（去首尾空白）',
  `alias_key` VARCHAR(64) NOT NULL COMMENT '归一化匹配键（去不可见字符与空白并转小写）',
  `steward_user_id` BIGINT UNSIGNED NOT NULL COMMENT '映射的CRM在职管家ID（sys_user.id）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INACTIVE',
  `remark` VARCHAR(255) NULL COMMENT '配置说明',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '创建人',
  `updated_by` BIGINT UNSIGNED NOT NULL COMMENT '最后更新人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_yz_steward_alias_key` (`tenant_id`,`alias_key`),
  KEY `idx_yz_steward_alias_user` (`tenant_id`,`steward_user_id`,`status`),
  CONSTRAINT `fk_yz_steward_alias_user` FOREIGN KEY (`steward_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞订单备注账号别名与CRM管家映射表';

CREATE TABLE IF NOT EXISTS `crm_youzan_order_assignment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '有赞店铺 kdt_id',
  `external_order_no` VARCHAR(128) NOT NULL COMMENT '有赞订单号 tid',
  `latest_event_id` BIGINT UNSIGNED NULL COMMENT '最近提取所对应的原始事件ID',
  `provider_order_status` VARCHAR(64) NULL COMMENT '有赞订单状态码（如 WAIT_BUYER_CONFIRM_GOODS）',
  `provider_status_text` VARCHAR(64) NULL COMMENT '有赞订单状态中文说明（如 已发货）',
  `pay_way_desc` VARCHAR(64) NULL COMMENT '有赞支付方式说明（如 微信支付）',
  `ordered_at` DATETIME(3) NULL COMMENT '有赞下单时间，UTC',
  `consigned_at` DATETIME(3) NULL COMMENT '有赞发货时间，UTC',
  `buyer_form_name_masked` VARCHAR(64) NULL COMMENT '下单表单姓名脱敏（orders[].buyer_messages）',
  `buyer_form_mobile_masked` VARCHAR(32) NULL COMMENT '下单表单手机号脱敏（orders[].buyer_messages）',
  `trade_memo_masked` VARCHAR(1000) NULL COMMENT '有赞商家备注（手机号已脱敏）',
  `memo_performance_owner` VARCHAR(64) NULL COMMENT '备注解析：业绩归属人',
  `memo_channel` VARCHAR(64) NULL COMMENT '备注解析：渠道',
  `memo_customer_type` VARCHAR(64) NULL COMMENT '备注解析：客户类型（如新客/老客）',
  `memo_source` VARCHAR(128) NULL COMMENT '备注解析：来源',
  `memo_account_name` VARCHAR(64) NULL COMMENT '备注解析：账号后人名（管家分配依据）',
  `memo_account_key` VARCHAR(64) NULL COMMENT '备注解析：账号名归一化匹配键',
  `memo_institution_name` VARCHAR(128) NULL COMMENT '备注解析：到院机构',
  `memo_extra_note` VARCHAR(500) NULL COMMENT '备注解析：其余补充说明（手机号已脱敏）',
  `assigned_steward_id` BIGINT UNSIGNED NULL COMMENT '分配的归属管家ID（sys_user.id）',
  `assigned_team_id` BIGINT UNSIGNED NULL COMMENT '归属管家所属团队ID快照',
  `assignment_status` VARCHAR(32) NOT NULL DEFAULT 'NO_ACCOUNT' COMMENT '分配状态：ASSIGNED/UNMATCHED/AMBIGUOUS/NO_ACCOUNT',
  `assignment_source` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '分配来源：AUTO_ALIAS/AUTO_DISPLAY_NAME/MANUAL/NONE',
  `assignment_reason` VARCHAR(500) NULL COMMENT '自动匹配说明或人工分配理由',
  `assigned_by` BIGINT UNSIGNED NULL COMMENT '人工分配操作人ID（自动分配为空）',
  `assigned_at` DATETIME(3) NULL COMMENT '最近分配生效时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_yz_order_assign_order` (`tenant_id`,`shop_id`,`external_order_no`),
  KEY `idx_yz_order_assign_steward` (`tenant_id`,`shop_id`,`assigned_steward_id`,`assignment_status`),
  KEY `idx_yz_order_assign_team` (`tenant_id`,`shop_id`,`assigned_team_id`),
  KEY `idx_yz_order_assign_account` (`tenant_id`,`shop_id`,`memo_account_key`,`assignment_status`),
  CONSTRAINT `fk_yz_order_assign_steward` FOREIGN KEY (`assigned_steward_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞订单备注解析与管家分配记录';
