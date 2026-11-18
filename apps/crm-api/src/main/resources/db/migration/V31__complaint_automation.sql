-- 客诉关键词检测与人工闭环。命中、正式单和主管快照分离；历史仅追加。
CREATE TABLE `crm_complaint_policy` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '规则检测开关，与AI开关无关',
  `fallback_recipient_user_id` BIGINT UNSIGNED NULL COMMENT '团队无主管时的唯一兜底接收人',
  `empathy_script` VARCHAR(2000) NOT NULL COMMENT '仅供人工复制的共情话术，不自动发送',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_complaint_policy_tenant` (`tenant_id`),
  CONSTRAINT `fk_complaint_policy_recipient` FOREIGN KEY (`fallback_recipient_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客诉自动化租户级策略';

CREATE TABLE `crm_complaint_keyword` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `keyword` VARCHAR(128) NOT NULL COMMENT '规则关键词，不使用AI或正则执行',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_by` BIGINT UNSIGNED NULL COMMENT '创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_complaint_keyword_text` (`tenant_id`,`keyword`),
  KEY `idx_complaint_keyword_active` (`tenant_id`,`status`,`id`),
  CONSTRAINT `fk_complaint_keyword_creator` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客诉检测关键词配置，仅可修改或停用';

CREATE TABLE `crm_complaint_hit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT 'CRM客户唯一ID',
  `owner_staff_id_snapshot` BIGINT UNSIGNED NOT NULL COMMENT '检测时客户管家快照',
  `team_id_snapshot` BIGINT UNSIGNED NULL COMMENT '检测时客户团队快照',
  `source_type` VARCHAR(32) NOT NULL COMMENT 'WECOM_MESSAGE/FOLLOWUP',
  `source_id` VARCHAR(128) NOT NULL COMMENT '稳定来源记录ID',
  `keyword_id` BIGINT UNSIGNED NULL COMMENT '检测时关键词配置ID',
  `keyword_snapshot` VARCHAR(128) NOT NULL COMMENT '检测时关键词快照',
  `evidence_masked` VARCHAR(2000) NOT NULL COMMENT '脱敏证据，不保存聊天原文',
  `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN待核查/CONVERTED/DISMISSED',
  `routing_status` VARCHAR(16) NOT NULL COMMENT 'ROUTED/EXCEPTION',
  `routing_error` VARCHAR(64) NULL COMMENT 'NO_RECIPIENT等稳定异常代码',
  `recipient_user_id_snapshot` BIGINT UNSIGNED NULL COMMENT '检测时唯一通知接收人快照',
  `recipient_source_snapshot` VARCHAR(32) NOT NULL COMMENT 'TEAM_LEADER/TEAM_MANAGER_LEAD/FALLBACK/NONE',
  `policy_version_snapshot` BIGINT UNSIGNED NOT NULL COMMENT '检测时策略版本快照',
  `complaint_case_id` BIGINT UNSIGNED NULL COMMENT '人工确认后的正式客诉单',
  `review_reason_masked` VARCHAR(1000) NULL COMMENT '人工核查理由',
  `reviewed_by` BIGINT UNSIGNED NULL COMMENT '人工核查人',
  `reviewed_at` DATETIME(3) NULL COMMENT '人工核查时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '检测时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_complaint_hit_source` (`tenant_id`,`source_type`,`source_id`,`keyword_id`),
  KEY `idx_complaint_hit_scope` (`tenant_id`,`team_id_snapshot`,`owner_staff_id_snapshot`,`status`,`id`),
  KEY `idx_complaint_hit_customer` (`tenant_id`,`customer_id`,`id`),
  KEY `idx_complaint_hit_recipient` (`tenant_id`,`recipient_user_id_snapshot`,`routing_status`,`id`),
  CONSTRAINT `fk_complaint_hit_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_complaint_hit_keyword` FOREIGN KEY (`keyword_id`) REFERENCES `crm_complaint_keyword` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_complaint_hit_recipient` FOREIGN KEY (`recipient_user_id_snapshot`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_complaint_hit_reviewer` FOREIGN KEY (`reviewed_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客诉关键词命中待核查，不等于正式客诉';

CREATE TABLE `crm_complaint_case` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `complaint_case_no` VARCHAR(64) NOT NULL COMMENT '正式客诉单编号',
  `complaint_hit_id` BIGINT UNSIGNED NOT NULL COMMENT '经人工确认的来源命中',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT 'CRM客户唯一ID',
  `owner_staff_id_snapshot` BIGINT UNSIGNED NOT NULL COMMENT '确认时沿用的管家快照',
  `team_id_snapshot` BIGINT UNSIGNED NULL COMMENT '确认时沿用的团队快照',
  `recipient_user_id_snapshot` BIGINT UNSIGNED NULL COMMENT '确认时沿用的主管快照',
  `recipient_source_snapshot` VARCHAR(32) NOT NULL COMMENT '主管解析来源快照',
  `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ASSIGNED/RESOLVED/CLOSED/DISMISSED',
  `assignee_user_id` BIGINT UNSIGNED NULL COMMENT '人工认领人',
  `resolution_masked` VARCHAR(2000) NULL COMMENT '人工处理结论',
  `resolved_at` DATETIME(3) NULL COMMENT '处理完成时间UTC',
  `closed_at` DATETIME(3) NULL COMMENT '关闭时间UTC',
  `dismiss_reason_masked` VARCHAR(1000) NULL COMMENT '非终态驳回理由',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '人工确认人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '确认时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_complaint_case_no` (`tenant_id`,`complaint_case_no`),
  UNIQUE KEY `uk_complaint_case_hit` (`tenant_id`,`complaint_hit_id`),
  KEY `idx_complaint_case_scope` (`tenant_id`,`team_id_snapshot`,`owner_staff_id_snapshot`,`status`,`id`),
  KEY `idx_complaint_case_customer` (`tenant_id`,`customer_id`,`id`),
  CONSTRAINT `fk_complaint_case_hit` FOREIGN KEY (`complaint_hit_id`) REFERENCES `crm_complaint_hit` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_complaint_case_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_complaint_case_recipient` FOREIGN KEY (`recipient_user_id_snapshot`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_complaint_case_assignee` FOREIGN KEY (`assignee_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_complaint_case_creator` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人工确认的正式客诉单，状态不可逆';

CREATE TABLE `crm_complaint_hit_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `complaint_hit_id` BIGINT UNSIGNED NOT NULL COMMENT '客诉命中',
  `action` VARCHAR(32) NOT NULL COMMENT 'DETECT/CONFIRM/DISMISS',
  `from_status` VARCHAR(16) NULL COMMENT '变更前状态',
  `to_status` VARCHAR(16) NOT NULL COMMENT '变更后状态',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '操作人，系统检测为空',
  `reason_masked` VARCHAR(1000) NULL COMMENT '脱敏理由',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间UTC',
  PRIMARY KEY (`id`),
  KEY `idx_complaint_hit_history` (`tenant_id`,`complaint_hit_id`,`id`),
  CONSTRAINT `fk_complaint_hit_history_hit` FOREIGN KEY (`complaint_hit_id`) REFERENCES `crm_complaint_hit` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_complaint_hit_history_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客诉命中追加历史';

CREATE TABLE `crm_complaint_case_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `complaint_case_id` BIGINT UNSIGNED NOT NULL COMMENT '正式客诉单',
  `action` VARCHAR(32) NOT NULL COMMENT 'CONFIRM/ASSIGN/RESOLVE/CLOSE/DISMISS',
  `from_status` VARCHAR(16) NULL COMMENT '变更前状态',
  `to_status` VARCHAR(16) NOT NULL COMMENT '变更后状态',
  `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '人工操作人',
  `reason_masked` VARCHAR(2000) NOT NULL COMMENT '脱敏理由或处理结论',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间UTC',
  PRIMARY KEY (`id`),
  KEY `idx_complaint_case_history` (`tenant_id`,`complaint_case_id`,`id`),
  CONSTRAINT `fk_complaint_case_history_case` FOREIGN KEY (`complaint_case_id`) REFERENCES `crm_complaint_case` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_complaint_case_history_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='正式客诉追加处理历史';

INSERT IGNORE INTO `crm_complaint_policy` (`tenant_id`,`enabled`,`fallback_recipient_user_id`,`empathy_script`)
SELECT refs.`tenant_id`,1,NULL,'我们理解您的感受，感谢告知。我们会由专人核实并跟进处理。'
FROM (SELECT o.`tenant_id` FROM `sys_org` o UNION SELECT 1) refs;

INSERT IGNORE INTO `sys_role` (`tenant_id`,`role_code`,`role_name`,`status`)
SELECT o.`tenant_id`,v.`role_code`,v.`role_name`,'ACTIVE' FROM `sys_org` o
CROSS JOIN (SELECT 'MANAGER_LEAD' AS `role_code`,'管家主管' AS `role_name`
  UNION ALL SELECT 'MANAGER','管家'
  UNION ALL SELECT 'OPERATION','运营'
  UNION ALL SELECT 'OPERATION_LEAD','运营主管') v;

-- 全新默认租户需兼容开发种子的固定角色主键；不得抢占 ADMIN(id=1)/OWNER(id=2)。
INSERT IGNORE INTO `sys_role` (`id`,`tenant_id`,`role_code`,`role_name`,`status`)
SELECT v.`id`,1,v.`role_code`,v.`role_name`,'ACTIVE' FROM (
  SELECT 3 AS `id`,'MANAGER_LEAD' AS `role_code`,'管家主管' AS `role_name`
  UNION ALL SELECT 4,'MANAGER','管家'
  UNION ALL SELECT 9,'OPERATION','运营'
  UNION ALL SELECT 10,'OPERATION_LEAD','运营主管') v
WHERE NOT EXISTS (SELECT 1 FROM `sys_org` o WHERE o.`tenant_id`=1);

INSERT IGNORE INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT refs.`tenant_id`,v.`permission_code`,v.`description`
FROM (SELECT o.`tenant_id` FROM `sys_org` o UNION SELECT 1) refs
CROSS JOIN (SELECT 'complaint:read' AS `permission_code`,'客诉命中与正式单查看' AS `description`
  UNION ALL SELECT 'complaint:write','客诉检测、核查与正式处理'
  UNION ALL SELECT 'complaint:config','客诉策略与关键词配置') v;

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code` LIKE 'complaint:%'
WHERE r.`role_code`='ADMIN';
INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code` IN ('complaint:read','complaint:write')
WHERE r.`role_code` IN ('MANAGER_LEAD','MANAGER','OPERATION_LEAD');
INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code`='complaint:read'
WHERE r.`role_code` IN ('OWNER','OPERATION');
INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code`='complaint:config'
WHERE r.`role_code`='OPERATION_LEAD';

CREATE TRIGGER `trg_complaint_policy_no_delete` BEFORE DELETE ON `crm_complaint_policy`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint policy cannot be deleted';
CREATE TRIGGER `trg_complaint_keyword_no_delete` BEFORE DELETE ON `crm_complaint_keyword`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint keyword cannot be deleted';

CREATE TRIGGER `trg_complaint_hit_immutable` BEFORE UPDATE ON `crm_complaint_hit`
FOR EACH ROW
BEGIN
  IF NOT (OLD.`tenant_id` <=> NEW.`tenant_id` AND OLD.`customer_id` <=> NEW.`customer_id`
    AND OLD.`owner_staff_id_snapshot` <=> NEW.`owner_staff_id_snapshot` AND OLD.`team_id_snapshot` <=> NEW.`team_id_snapshot`
    AND OLD.`source_type` <=> NEW.`source_type` AND OLD.`source_id` <=> NEW.`source_id`
    AND OLD.`keyword_id` <=> NEW.`keyword_id` AND OLD.`keyword_snapshot` <=> NEW.`keyword_snapshot`
    AND OLD.`evidence_masked` <=> NEW.`evidence_masked` AND OLD.`routing_status` <=> NEW.`routing_status`
    AND OLD.`routing_error` <=> NEW.`routing_error` AND OLD.`recipient_user_id_snapshot` <=> NEW.`recipient_user_id_snapshot`
    AND OLD.`recipient_source_snapshot` <=> NEW.`recipient_source_snapshot`
    AND OLD.`policy_version_snapshot` <=> NEW.`policy_version_snapshot` AND OLD.`created_at` <=> NEW.`created_at`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint hit evidence and snapshots are immutable';
  END IF;
  IF OLD.`complaint_case_id` IS NOT NULL AND NOT (OLD.`complaint_case_id` <=> NEW.`complaint_case_id`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint hit case link is immutable';
  END IF;
  IF NOT ((OLD.`status`='OPEN' AND NEW.`status` IN ('OPEN','CONVERTED','DISMISSED'))
    OR (OLD.`status`='CONVERTED' AND NEW.`status`='CONVERTED')
    OR (OLD.`status`='DISMISSED' AND NEW.`status`='DISMISSED')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid complaint hit transition';
  END IF;
END;
CREATE TRIGGER `trg_complaint_hit_no_delete` BEFORE DELETE ON `crm_complaint_hit`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint hit is append only';

CREATE TRIGGER `trg_complaint_case_immutable` BEFORE UPDATE ON `crm_complaint_case`
FOR EACH ROW
BEGIN
  IF NOT (OLD.`tenant_id` <=> NEW.`tenant_id` AND OLD.`complaint_case_no` <=> NEW.`complaint_case_no`
    AND OLD.`complaint_hit_id` <=> NEW.`complaint_hit_id` AND OLD.`customer_id` <=> NEW.`customer_id`
    AND OLD.`owner_staff_id_snapshot` <=> NEW.`owner_staff_id_snapshot` AND OLD.`team_id_snapshot` <=> NEW.`team_id_snapshot`
    AND OLD.`recipient_user_id_snapshot` <=> NEW.`recipient_user_id_snapshot`
    AND OLD.`recipient_source_snapshot` <=> NEW.`recipient_source_snapshot`
    AND OLD.`created_by` <=> NEW.`created_by` AND OLD.`created_at` <=> NEW.`created_at`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint case facts and snapshots are immutable';
  END IF;
  IF NOT ((OLD.`status`='OPEN' AND NEW.`status` IN ('OPEN','ASSIGNED','DISMISSED'))
    OR (OLD.`status`='ASSIGNED' AND NEW.`status` IN ('ASSIGNED','RESOLVED','DISMISSED'))
    OR (OLD.`status`='RESOLVED' AND NEW.`status` IN ('RESOLVED','CLOSED','DISMISSED'))
    OR (OLD.`status`='CLOSED' AND NEW.`status`='CLOSED')
    OR (OLD.`status`='DISMISSED' AND NEW.`status`='DISMISSED')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid complaint case transition';
  END IF;
  IF (OLD.`resolved_at` IS NOT NULL AND NOT (OLD.`resolved_at` <=> NEW.`resolved_at`))
    OR (OLD.`closed_at` IS NOT NULL AND NOT (OLD.`closed_at` <=> NEW.`closed_at`)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint case completion facts are immutable';
  END IF;
END;
CREATE TRIGGER `trg_complaint_case_no_delete` BEFORE DELETE ON `crm_complaint_case`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint case is append only';
CREATE TRIGGER `trg_complaint_hit_history_no_update` BEFORE UPDATE ON `crm_complaint_hit_history`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint hit history is append only';
CREATE TRIGGER `trg_complaint_hit_history_no_delete` BEFORE DELETE ON `crm_complaint_hit_history`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint hit history is append only';
CREATE TRIGGER `trg_complaint_case_history_no_update` BEFORE UPDATE ON `crm_complaint_case_history`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint case history is append only';
CREATE TRIGGER `trg_complaint_case_history_no_delete` BEFORE DELETE ON `crm_complaint_case_history`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint case history is append only';
