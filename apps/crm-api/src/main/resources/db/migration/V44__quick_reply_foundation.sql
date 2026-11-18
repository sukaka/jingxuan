CREATE TABLE `crm_quick_reply_category` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `parent_id` BIGINT UNSIGNED NULL COMMENT '父分类',
  `name` VARCHAR(64) NOT NULL COMMENT '分类名称',
  `code` VARCHAR(48) NOT NULL COMMENT '稳定分类编码',
  `scope_type` VARCHAR(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/INSTITUTION/DEPARTMENT/PERSONAL',
  `scope_id` BIGINT UNSIGNED NULL COMMENT '作用域对象ID',
  `sort_no` INT NOT NULL DEFAULT 0 COMMENT '分类排序',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_by` BIGINT UNSIGNED NULL COMMENT '创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quick_reply_category_code` (`tenant_id`,`code`),
  KEY `idx_quick_reply_category_scope` (`tenant_id`,`status`,`scope_type`,`scope_id`,`sort_no`),
  CONSTRAINT `fk_quick_reply_category_parent` FOREIGN KEY (`parent_id`) REFERENCES `crm_quick_reply_category` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_quick_reply_category_creator` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快捷话术分类配置';

CREATE TABLE `crm_quick_reply_template` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `category_id` BIGINT UNSIGNED NOT NULL COMMENT '所属分类',
  `name` VARCHAR(96) NOT NULL COMMENT '话术名称',
  `code` VARCHAR(64) NOT NULL COMMENT '稳定话术编码',
  `message_type` VARCHAR(16) NOT NULL DEFAULT 'TEXT' COMMENT '当前仅TEXT',
  `content` VARCHAR(2000) NOT NULL COMMENT '固定文本或白名单变量模板',
  `scene` VARCHAR(64) NOT NULL COMMENT '业务场景编码',
  `scope_type` VARCHAR(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/INSTITUTION/DEPARTMENT/PERSONAL',
  `scope_id` BIGINT UNSIGNED NULL COMMENT '作用域对象ID',
  `allow_edit` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许员工编辑',
  `require_preview` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否必须预览',
  `ai_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否允许进入AI候选，不表示自动发送',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `sort_no` INT NOT NULL DEFAULT 0 COMMENT '分类内排序',
  `created_by` BIGINT UNSIGNED NULL COMMENT '创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quick_reply_template_code` (`tenant_id`,`code`),
  KEY `idx_quick_reply_template_list` (`tenant_id`,`status`,`scope_type`,`scope_id`,`category_id`,`sort_no`),
  CONSTRAINT `fk_quick_reply_template_category` FOREIGN KEY (`category_id`) REFERENCES `crm_quick_reply_category` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_quick_reply_template_creator` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快捷话术模板，只支持白名单变量';

CREATE TABLE `crm_quick_reply_variable` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `variable_code` VARCHAR(64) NOT NULL COMMENT '变量编码',
  `variable_name` VARCHAR(64) NOT NULL COMMENT '变量中文名称',
  `data_source` VARCHAR(48) NOT NULL COMMENT 'CRM事实来源',
  `resolver_type` VARCHAR(24) NOT NULL DEFAULT 'REGISTERED' COMMENT '固定REGISTERED',
  `resolver_key` VARCHAR(64) NOT NULL COMMENT '服务端Resolver注册键',
  `data_type` VARCHAR(16) NOT NULL COMMENT 'STRING/NUMBER/DATE/DATETIME',
  `required` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '模板使用时是否禁止缺失',
  `description` VARCHAR(255) NOT NULL COMMENT '业务口径说明',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quick_reply_variable_code` (`variable_code`),
  UNIQUE KEY `uk_quick_reply_variable_resolver` (`resolver_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快捷话术变量白名单，无SQL或脚本';

CREATE TABLE `crm_quick_reply_usage` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `template_id` BIGINT UNSIGNED NOT NULL COMMENT '使用的话术模板',
  `employee_id` BIGINT UNSIGNED NOT NULL COMMENT '实际操作员工',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT 'CRM客户唯一ID',
  `external_userid` VARCHAR(192) NOT NULL COMMENT '企微外部联系身份',
  `scene` VARCHAR(64) NOT NULL COMMENT '使用场景',
  `original_content` VARCHAR(2000) NOT NULL COMMENT '脱敏后的模板渲染快照',
  `final_content` VARCHAR(4000) NOT NULL COMMENT '脱敏后的员工最终内容',
  `edited` TINYINT(1) NOT NULL COMMENT '最终内容是否经过编辑',
  `send_status` VARCHAR(24) NOT NULL COMMENT 'COPIED/SDK_SUCCESS/SDK_FAILED/SDK_UNKNOWN',
  `client_result` VARCHAR(512) NULL COMMENT '脱敏后的客户端结果，不作为客户已读证据',
  `send_time` DATETIME(3) NOT NULL COMMENT '客户端结果记录时间UTC',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '使用记录幂等键',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quick_reply_usage_key` (`tenant_id`,`idempotency_key`),
  KEY `idx_quick_reply_usage_template` (`tenant_id`,`template_id`,`created_at`),
  KEY `idx_quick_reply_usage_employee` (`tenant_id`,`employee_id`,`created_at`),
  KEY `idx_quick_reply_usage_customer` (`tenant_id`,`customer_id`,`created_at`),
  CONSTRAINT `fk_quick_reply_usage_template` FOREIGN KEY (`template_id`) REFERENCES `crm_quick_reply_template` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_quick_reply_usage_employee` FOREIGN KEY (`employee_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_quick_reply_usage_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快捷话术复制与当前会话SDK结果日志，只追加';

CREATE TRIGGER `trg_quick_reply_usage_no_update` BEFORE UPDATE ON `crm_quick_reply_usage`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Quick reply usage is append only';
CREATE TRIGGER `trg_quick_reply_usage_no_delete` BEFORE DELETE ON `crm_quick_reply_usage`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Quick reply usage is append only';

INSERT INTO `crm_quick_reply_variable` (`variable_code`,`variable_name`,`data_source`,`resolver_key`,`data_type`,`required`,`description`) VALUES
 ('customer_name','客户称呼','CRM_CUSTOMER','customer_name','STRING',1,'CRM客户展示称呼'),
 ('manager_name','管家姓名','CRM_CUSTOMER_OWNER','manager_name','STRING',1,'当前归属管家展示姓名'),
 ('project_name','项目名称','CRM_ENTITLEMENT','project_name','STRING',1,'当前可用权益对应项目'),
 ('total_count','购买次数','CRM_ENTITLEMENT','total_count','NUMBER',1,'该权益实际发放次数'),
 ('used_count','已用次数','CRM_ENTITLEMENT','used_count','NUMBER',1,'发放次数减当前剩余次数'),
 ('remaining_count','剩余次数','CRM_ENTITLEMENT','remaining_count','NUMBER',1,'当前权益剩余服务次数'),
 ('last_visit_date','最近到院日期','CRM_APPOINTMENT','last_visit_date','DATE',0,'最近到院或服务完成日期'),
 ('last_writeoff_date','最近核销日期','CRM_REDEMPTION','last_writeoff_date','DATE',0,'机构正式核销确认日期'),
 ('appointment_date','预约日期','CRM_APPOINTMENT','appointment_date','DATE',1,'最近未来已确认预约日期'),
 ('appointment_time','预约时间','CRM_APPOINTMENT','appointment_time','STRING',1,'最近未来已确认预约时间'),
 ('institution_name','机构名称','CRM_INSTITUTION','institution_name','STRING',1,'当前预约机构展示名称'),
 ('institution_address','机构地址','CRM_INSTITUTION','institution_address','STRING',0,'当前预约机构地址'),
 ('doctor_name','医生姓名','CRM_APPOINTMENT','doctor_name','STRING',0,'未接入排班医生事实时为空'),
 ('expire_date','权益到期日','CRM_ENTITLEMENT','expire_date','DATE',0,'当前权益有效期截止日期'),
 ('order_no','最近订单号','CRM_ORDER','order_no','STRING',0,'当前客户最近订单编号');

INSERT IGNORE INTO `crm_quick_reply_category` (`tenant_id`,`name`,`code`,`sort_no`)
SELECT refs.`tenant_id`,v.`name`,v.`code`,v.`sort_no` FROM (SELECT o.`tenant_id` FROM `sys_org` o UNION SELECT 1) refs
CROSS JOIN (
  SELECT '初次沟通' AS `name`,'INITIAL' AS `code`,10 AS `sort_no` UNION ALL SELECT '预约','APPOINTMENT',20
  UNION ALL SELECT '到院','VISIT',30 UNION ALL SELECT '核销','REDEMPTION',40 UNION ALL SELECT '项目咨询','PROJECT',50
  UNION ALL SELECT '术前','PREOPERATIVE',60 UNION ALL SELECT '术后','POSTOPERATIVE',70 UNION ALL SELECT '回访','FOLLOWUP',80
  UNION ALL SELECT '退款','REFUND',90 UNION ALL SELECT '复购','REPURCHASE',100 UNION ALL SELECT '投诉','COMPLAINT',110
  UNION ALL SELECT '活动','CAMPAIGN',120
) v;

INSERT INTO `crm_quick_reply_template` (`tenant_id`,`category_id`,`name`,`code`,`content`,`scene`,`sort_no`)
SELECT c.`tenant_id`,c.`id`,'剩余次数说明','PROJECT_REMAINING',
  '{customer_name}您好，您购买的{project_name}共{total_count}次，目前已使用{used_count}次，还剩{remaining_count}次。','PACKAGE_REMAINING',10
FROM `crm_quick_reply_category` c WHERE c.`code`='PROJECT'
UNION ALL
SELECT c.`tenant_id`,c.`id`,'预约确认提醒','APPOINTMENT_CONFIRM',
  '{customer_name}您好，为您预留了{appointment_date} {appointment_time}在{institution_name}的服务时间，如需调整请提前联系我们。','APPOINTMENT',20
FROM `crm_quick_reply_category` c WHERE c.`code`='APPOINTMENT'
UNION ALL
SELECT c.`tenant_id`,c.`id`,'管家人工回访','FOLLOWUP_GREETING',
  '{customer_name}您好，我是您的专属管家{manager_name}。想了解您最近的使用感受，有任何问题都可以告诉我。','FOLLOWUP',30
FROM `crm_quick_reply_category` c WHERE c.`code`='FOLLOWUP';

INSERT IGNORE INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT refs.`tenant_id`,v.`code`,v.`description` FROM (SELECT o.`tenant_id` FROM `sys_org` o UNION SELECT 1) refs
CROSS JOIN (SELECT 'quickreply:view' AS `code`,'快捷话术查看、预览与使用记录' AS `description`
  UNION ALL SELECT 'quickreply:manage','快捷话术分类、模板与变量配置管理') v;

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code`='quickreply:view'
WHERE r.`role_code` IN ('ADMIN','OWNER','MANAGER','MANAGER_LEAD','OPERATION','OPERATION_LEAD','BUSINESS');
INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code`='quickreply:manage'
WHERE r.`role_code` IN ('ADMIN','OWNER','OPERATION_LEAD');
