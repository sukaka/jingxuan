CREATE TABLE IF NOT EXISTS `crm_integration_runtime_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离，每租户一条',
  `youzan_real_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '真实有赞全局开关；仍受店铺授权与同步开关约束',
  `wecom_archive_real_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '企微真实会话归档全局开关；仍受Q10与官方Adapter约束',
  `wecom_official_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '企微官方客户联系API全局开关；仍受企业凭证约束',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_integration_runtime_config_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每租户外部集成全局运行开关，不含供应商密钥';

INSERT INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT DISTINCT r.`tenant_id`,'integration:runtime:manage','外部集成全局运行开关管理' FROM `sys_role` r
ON DUPLICATE KEY UPDATE `description`=VALUES(`description`);

INSERT IGNORE INTO `sys_role_permission` (`tenant_id`,`role_id`,`permission_id`)
SELECT r.`tenant_id`,r.`id`,p.`id` FROM `sys_role` r
JOIN `sys_permission` p ON p.`tenant_id`=r.`tenant_id` AND p.`permission_code`='integration:runtime:manage'
WHERE r.`role_code`='ADMIN';

-- 回滚需先停用相关同步并备份配置，再删除 ADMIN 的 integration:runtime:manage 授权、权限及本表；不得在生产直接执行。
