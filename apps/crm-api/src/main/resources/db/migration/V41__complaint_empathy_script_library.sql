-- 客诉人工共情话术库：支持多条场景模板，仅供人工选择与复制，不自动发送。
CREATE TABLE `crm_complaint_empathy_script` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `title` VARCHAR(64) NOT NULL COMMENT '话术场景标题',
  `script` VARCHAR(2000) NOT NULL COMMENT '脱敏话术内容，仅供人工复制',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_by` BIGINT UNSIGNED NULL COMMENT '创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_complaint_empathy_script_title` (`tenant_id`,`title`),
  KEY `idx_complaint_empathy_script_active` (`tenant_id`,`status`,`id`),
  CONSTRAINT `fk_complaint_empathy_script_creator` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客诉人工共情话术库，可修改或停用但不可删除';

INSERT INTO `crm_complaint_empathy_script` (`tenant_id`,`title`,`script`,`status`,`created_by`)
SELECT p.`tenant_id`,'默认共情话术',p.`empathy_script`,'ACTIVE',NULL FROM `crm_complaint_policy` p;

CREATE TRIGGER `trg_complaint_empathy_script_guard` BEFORE UPDATE ON `crm_complaint_empathy_script`
FOR EACH ROW
BEGIN
  IF NOT (OLD.`tenant_id` <=> NEW.`tenant_id` AND OLD.`created_by` <=> NEW.`created_by`
    AND OLD.`created_at` <=> NEW.`created_at`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint empathy script ownership is immutable';
  END IF;
  IF NOT ((OLD.`status`='ACTIVE' AND NEW.`status` IN ('ACTIVE','DISABLED'))
    OR (OLD.`status`='DISABLED' AND NEW.`status`='DISABLED')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid complaint empathy script transition';
  END IF;
END;
CREATE TRIGGER `trg_complaint_empathy_script_no_delete` BEFORE DELETE ON `crm_complaint_empathy_script`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Complaint empathy script cannot be deleted';
