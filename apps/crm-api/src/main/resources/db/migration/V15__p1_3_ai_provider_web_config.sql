CREATE TABLE IF NOT EXISTS `crm_ai_provider_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离，每租户一条',
  `provider` VARCHAR(32) NOT NULL DEFAULT 'disabled' COMMENT 'disabled/mock/openai_compatible',
  `base_url` VARCHAR(1024) NULL COMMENT 'OpenAI 兼容供应商基础地址，默认要求 HTTPS',
  `api_key_cipher` TEXT NULL COMMENT 'API Key 的 AES-GCM 密文，禁止回显与审计',
  `model` VARCHAR(128) NULL COMMENT '模型名称',
  `timeout_seconds` INT UNSIGNED NOT NULL DEFAULT 30 COMMENT '供应商调用超时秒数',
  `enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '该租户 AI 任务 Web 总开关',
  `connection_status` VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED' COMMENT 'UNVERIFIED/CONNECTED/FAILED',
  `last_error_masked` VARCHAR(500) NULL COMMENT '脱敏连接错误，不含密钥与供应商原文',
  `last_connected_at` DATETIME(3) NULL COMMENT '最近连接成功时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_provider_config_tenant` (`tenant_id`),
  CONSTRAINT `chk_ai_provider_config_provider` CHECK (`provider` IN ('disabled','mock','openai_compatible')),
  CONSTRAINT `chk_ai_provider_config_timeout` CHECK (`timeout_seconds` BETWEEN 1 AND 300),
  CONSTRAINT `chk_ai_provider_config_status` CHECK (`connection_status` IN ('UNVERIFIED','CONNECTED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每租户 AI 供应商 Web 配置，密钥仅保存密文';

INSERT INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT DISTINCT r.`tenant_id`,'ai:config','AI 供应商配置管理' FROM `sys_role` r
ON DUPLICATE KEY UPDATE `description`=VALUES(`description`);

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code`='ai:config'
WHERE r.`role_code`='ADMIN';
