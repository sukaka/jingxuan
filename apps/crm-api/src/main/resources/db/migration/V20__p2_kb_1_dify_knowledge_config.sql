CREATE TABLE IF NOT EXISTS `crm_dify_knowledge_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离，每租户一条',
  `provider` VARCHAR(32) NOT NULL DEFAULT 'disabled' COMMENT 'disabled/mock/dify',
  `base_url` VARCHAR(1024) NULL COMMENT 'Dify API 基础地址，默认要求 HTTPS',
  `app_api_key_cipher` TEXT NULL COMMENT 'Chatflow App API Key 的 AES-GCM 密文，禁止回显与审计',
  `timeout_seconds` INT UNSIGNED NOT NULL DEFAULT 30 COMMENT 'Dify 调用超时秒数',
  `enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '该租户知识问答开关，真实调用另受 FEATURE_REAL_DIFY 限制',
  `connection_status` VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED' COMMENT 'UNVERIFIED/CONNECTED/FAILED',
  `last_error_masked` VARCHAR(500) NULL COMMENT '脱敏连接错误，不含密钥与供应商原文',
  `last_connected_at` DATETIME(3) NULL COMMENT '最近连接成功时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dify_knowledge_config_tenant` (`tenant_id`),
  CONSTRAINT `chk_dify_knowledge_provider` CHECK (`provider` IN ('disabled','mock','dify')),
  CONSTRAINT `chk_dify_knowledge_timeout` CHECK (`timeout_seconds` BETWEEN 1 AND 300),
  CONSTRAINT `chk_dify_knowledge_status` CHECK (`connection_status` IN ('UNVERIFIED','CONNECTED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每租户 Dify 知识问答配置，密钥仅保存密文';

INSERT INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT DISTINCT r.`tenant_id`,'ai:knowledge:config','Dify 知识问答配置管理' FROM `sys_role` r
ON DUPLICATE KEY UPDATE `description`=VALUES(`description`);

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code`='ai:knowledge:config'
WHERE r.`role_code`='ADMIN';
