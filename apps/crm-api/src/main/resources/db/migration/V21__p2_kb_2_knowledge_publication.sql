ALTER TABLE `crm_dify_knowledge_config`
  ADD COLUMN `dataset_id` VARCHAR(128) NULL COMMENT 'Dify Dataset ID，不同租户显式映射' AFTER `app_api_key_cipher`,
  ADD COLUMN `dataset_api_key_cipher` TEXT NULL COMMENT 'Dataset API Key 的 AES-GCM 密文，禁止回显与审计' AFTER `dataset_id`,
  ADD COLUMN `sync_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '资料同步开关，真实调用另受 FEATURE_REAL_DIFY 限制' AFTER `dataset_api_key_cipher`;

CREATE TABLE IF NOT EXISTS `crm_knowledge_document` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `material_id` BIGINT UNSIGNED NOT NULL COMMENT '素材，每素材一条当前映射',
  `active_revision_id` BIGINT UNSIGNED NULL COMMENT '当前可检索修订，索引成功后才更新',
  `dataset_id` VARCHAR(128) NULL COMMENT '当前映射的 Dify Dataset',
  `dify_document_id` VARCHAR(128) NULL COMMENT '当前映射的 Dify document',
  `status` VARCHAR(32) NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/SYNCING/ACTIVE/FAILED/DISABLED',
  `published_by` BIGINT UNSIGNED NOT NULL COMMENT '首次发布人',
  `published_at` DATETIME(3) NULL COMMENT '最近索引成功时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_document_material` (`tenant_id`,`material_id`),
  KEY `idx_knowledge_document_status` (`tenant_id`,`status`,`id`),
  KEY `idx_knowledge_document_revision` (`tenant_id`,`active_revision_id`),
  CONSTRAINT `fk_knowledge_document_material` FOREIGN KEY (`material_id`) REFERENCES `crm_material` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_knowledge_document_revision` FOREIGN KEY (`active_revision_id`) REFERENCES `crm_material_revision` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_knowledge_document_publisher` FOREIGN KEY (`published_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_knowledge_document_status` CHECK (`status` IN ('WAITING','SYNCING','ACTIVE','FAILED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='素材到 Dify 的当前发布映射，审核历史不覆盖';

CREATE TABLE IF NOT EXISTS `crm_knowledge_sync_job` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `document_id` BIGINT UNSIGNED NOT NULL COMMENT '知识发布映射',
  `revision_id` BIGINT UNSIGNED NOT NULL COMMENT '拟发布素材修订',
  `content_hash` CHAR(64) NOT NULL COMMENT '源文件 SHA-256',
  `status` VARCHAR(32) NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/PENDING/RUNNING/INDEXING/RETRY/SUCCEEDED/FAILED',
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已执行次数',
  `max_attempts` INT UNSIGNED NOT NULL DEFAULT 5 COMMENT '最大执行次数',
  `next_retry_at` DATETIME(3) NULL COMMENT '下次可重试时间，UTC',
  `lease_owner` VARCHAR(128) NULL COMMENT '执行实例租约',
  `lease_expires_at` DATETIME(3) NULL COMMENT '租约到期时间，UTC',
  `dify_document_id` VARCHAR(128) NULL COMMENT '创建或更新目标文档',
  `dify_batch` VARCHAR(128) NULL COMMENT 'Dify 索引批次',
  `last_error_masked` VARCHAR(500) NULL COMMENT '脱敏失败类别，不含正文或密钥',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '发布申请人',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '发布幂等键',
  `request_hash` CHAR(64) NOT NULL COMMENT '规范化请求 SHA-256',
  `started_at` DATETIME(3) NULL COMMENT '首次执行时间，UTC',
  `finished_at` DATETIME(3) NULL COMMENT '最终完成时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_job_content` (`tenant_id`,`document_id`,`revision_id`,`content_hash`),
  UNIQUE KEY `uk_knowledge_job_idempotency` (`tenant_id`,`requested_by`,`idempotency_key`),
  KEY `idx_knowledge_job_claim` (`tenant_id`,`status`,`next_retry_at`,`lease_expires_at`,`id`),
  KEY `idx_knowledge_job_document` (`tenant_id`,`document_id`,`id`),
  CONSTRAINT `fk_knowledge_job_document` FOREIGN KEY (`document_id`) REFERENCES `crm_knowledge_document` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_knowledge_job_revision` FOREIGN KEY (`revision_id`) REFERENCES `crm_material_revision` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_knowledge_job_requester` FOREIGN KEY (`requested_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_knowledge_job_status` CHECK (`status` IN ('WAITING','PENDING','RUNNING','INDEXING','RETRY','SUCCEEDED','FAILED')),
  CONSTRAINT `chk_knowledge_job_attempts` CHECK (`max_attempts` BETWEEN 1 AND 20 AND `attempt_count` <= `max_attempts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识资料异步同步任务，外部调用不放入数据库事务';

INSERT INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT DISTINCT r.`tenant_id`,'ai:knowledge:read','知识发布状态查看' FROM `sys_role` r
ON DUPLICATE KEY UPDATE `description`=VALUES(`description`);

INSERT INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT DISTINCT r.`tenant_id`,'ai:knowledge:publish','知识资料发布与停用' FROM `sys_role` r
ON DUPLICATE KEY UPDATE `description`=VALUES(`description`);

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code` IN ('ai:knowledge:read','ai:knowledge:publish')
WHERE r.`role_code`='ADMIN';
