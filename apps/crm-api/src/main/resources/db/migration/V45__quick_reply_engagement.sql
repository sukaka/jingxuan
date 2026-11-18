ALTER TABLE `crm_quick_reply_template`
  ADD COLUMN `featured` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否公司推荐，排序在AI候选之后、普通分类之前' AFTER `ai_enabled`;

CREATE TABLE `crm_quick_reply_favorite` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `template_id` BIGINT UNSIGNED NOT NULL COMMENT '话术模板',
  `employee_id` BIGINT UNSIGNED NOT NULL COMMENT '个人收藏员工',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '收藏时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quick_reply_favorite` (`tenant_id`,`template_id`,`employee_id`),
  KEY `idx_quick_reply_favorite_employee` (`tenant_id`,`employee_id`,`created_at`),
  CONSTRAINT `fk_quick_reply_favorite_template` FOREIGN KEY (`template_id`) REFERENCES `crm_quick_reply_template` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_quick_reply_favorite_employee` FOREIGN KEY (`employee_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工个人话术收藏，独立于历史使用日志';
