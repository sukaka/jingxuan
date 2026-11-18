CREATE TABLE `crm_wecom_h5_card` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '项目ID',
  `project_name_snapshot` VARCHAR(160) NOT NULL COMMENT '分享时项目名称快照',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '拟分享CRM客户，不在公开页暴露',
  `employee_id` BIGINT UNSIGNED NOT NULL COMMENT '创建员工，不在公开页暴露',
  `external_userid` VARCHAR(192) NOT NULL COMMENT '企微外部联系身份，不公开',
  `title` VARCHAR(160) NOT NULL COMMENT '公开卡片标题，已脱敏',
  `summary` VARCHAR(500) NOT NULL COMMENT '公开卡片摘要，已脱敏',
  `content` VARCHAR(4000) NOT NULL COMMENT '公开项目介绍，已脱敏',
  `share_key` CHAR(43) NOT NULL COMMENT '高随机公开链接标识，不是客户或会话ID',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED，停用后公开页不可见',
  `disable_reason` VARCHAR(1000) NULL COMMENT '停用理由，已脱敏',
  `disabled_by` BIGINT UNSIGNED NULL COMMENT '停用员工',
  `disabled_at` DATETIME(3) NULL COMMENT '停用时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '停用乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_h5_card_share_key` (`share_key`),
  KEY `idx_wecom_h5_card_employee_customer` (`tenant_id`,`employee_id`,`customer_id`,`created_at`),
  KEY `idx_wecom_h5_card_customer` (`tenant_id`,`customer_id`,`created_at`),
  CONSTRAINT `fk_h5_card_project` FOREIGN KEY (`project_id`) REFERENCES `crm_project` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_h5_card_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_h5_card_employee` FOREIGN KEY (`employee_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_h5_card_disabled_by` FOREIGN KEY (`disabled_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企微H5项目分享卡片，内容快照不可覆盖';

CREATE TABLE `crm_wecom_h5_card_usage` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `card_id` BIGINT UNSIGNED NOT NULL COMMENT 'H5分享卡片',
  `employee_id` BIGINT UNSIGNED NOT NULL COMMENT '实际操作员工',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '当前客户',
  `send_status` VARCHAR(24) NOT NULL COMMENT 'COPIED/SDK_SUCCESS/SDK_FAILED/SDK_UNKNOWN',
  `client_result` VARCHAR(512) NULL COMMENT '脱敏客户端结果，不表示客户已读',
  `send_time` DATETIME(3) NOT NULL COMMENT '客户端结果记录时间UTC',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '结果记录幂等键',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_h5_card_usage_key` (`tenant_id`,`idempotency_key`),
  KEY `idx_wecom_h5_card_usage_card` (`tenant_id`,`card_id`,`created_at`),
  KEY `idx_wecom_h5_card_usage_employee` (`tenant_id`,`employee_id`,`created_at`),
  CONSTRAINT `fk_h5_card_usage_card` FOREIGN KEY (`card_id`) REFERENCES `crm_wecom_h5_card` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_h5_card_usage_employee` FOREIGN KEY (`employee_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_h5_card_usage_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='H5卡片复制与当前会话SDK结果，只追加';

CREATE TRIGGER `trg_h5_card_snapshot_guard` BEFORE UPDATE ON `crm_wecom_h5_card`
FOR EACH ROW BEGIN
  IF NOT (OLD.status='ACTIVE' AND NEW.status='DISABLED' AND NEW.version=OLD.version+1
    AND NEW.tenant_id=OLD.tenant_id AND NEW.project_id=OLD.project_id AND NEW.customer_id=OLD.customer_id
    AND NEW.employee_id=OLD.employee_id AND NEW.external_userid=OLD.external_userid
    AND NEW.project_name_snapshot=OLD.project_name_snapshot AND NEW.title=OLD.title
    AND NEW.summary=OLD.summary AND NEW.content=OLD.content AND NEW.share_key=OLD.share_key) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='H5 card snapshot only allows active to disabled transition';
  END IF;
END;
CREATE TRIGGER `trg_h5_card_usage_no_update` BEFORE UPDATE ON `crm_wecom_h5_card_usage`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='H5 card usage is append only';
CREATE TRIGGER `trg_h5_card_usage_no_delete` BEFORE DELETE ON `crm_wecom_h5_card_usage`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='H5 card usage is append only';
