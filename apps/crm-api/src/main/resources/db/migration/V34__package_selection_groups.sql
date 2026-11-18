-- 套餐分组自选与独立参考价；SKU 售价仍独立保存，不由选项参考价合计推导。
ALTER TABLE `crm_package_project`
  ADD COLUMN `reference_price_fen` BIGINT NULL COMMENT '选项单品参考单价分；成交时复制到订单快照' AFTER `option_group`;

UPDATE `crm_package_project` pp
INNER JOIN `crm_project` p ON p.tenant_id=pp.tenant_id AND p.id=pp.project_id
SET pp.reference_price_fen=p.list_price_fen
WHERE pp.reference_price_fen IS NULL;

ALTER TABLE `crm_package_project`
  MODIFY COLUMN `reference_price_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '选项单品参考单价分；成交时复制到订单快照';

CREATE TABLE `crm_package_selection_group` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分组规则主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `package_version_id` BIGINT UNSIGNED NOT NULL COMMENT '套餐销售版本',
  `group_code` VARCHAR(48) NOT NULL COMMENT '选项组编码，如A/B',
  `group_name` VARCHAR(96) NOT NULL COMMENT '选项组显示名快照模板',
  `min_selections` INT UNSIGNED NOT NULL COMMENT '该组最少选择项目数',
  `max_selections` INT UNSIGNED NOT NULL COMMENT '该组最多选择项目数',
  `sort_order` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '组展示顺序',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_package_selection_group` (`tenant_id`,`package_version_id`,`group_code`),
  KEY `idx_package_selection_group_sort` (`tenant_id`,`package_version_id`,`sort_order`,`id`),
  CONSTRAINT `fk_package_selection_group_version` FOREIGN KEY (`package_version_id`) REFERENCES `crm_package_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_package_selection_group_range` CHECK (`min_selections` > 0 AND `max_selections` >= `min_selections`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CHOOSE_N结构化分组选择规则；激活后由版本机制冻结';

CREATE TABLE `crm_order_item_selection` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '订单选择快照主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `order_item_id` BIGINT UNSIGNED NOT NULL COMMENT '订单行',
  `package_version_id` BIGINT UNSIGNED NOT NULL COMMENT '成交套餐版本',
  `group_code` VARCHAR(48) NOT NULL COMMENT '成交时选项组编码',
  `group_name` VARCHAR(96) NOT NULL COMMENT '成交时选项组名称',
  `min_selections` INT UNSIGNED NOT NULL COMMENT '成交时该组最少选择数',
  `max_selections` INT UNSIGNED NOT NULL COMMENT '成交时该组最多选择数',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '实际选择的正规子项目',
  `project_code_snapshot` VARCHAR(64) NOT NULL COMMENT '成交时项目编码',
  `project_name_snapshot` VARCHAR(160) NOT NULL COMMENT '成交时项目名称',
  `grant_count` INT UNSIGNED NOT NULL COMMENT '该订单行实际发放次数，已乘购买份数',
  `reference_price_fen` BIGINT NOT NULL COMMENT '成交时单品参考单价分，不作为套餐定价或退款分摊依据',
  `sort_order` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '成交时组展示顺序',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_item_selection_group_project` (`tenant_id`,`order_item_id`,`group_code`,`project_id`),
  UNIQUE KEY `uk_order_item_selection_project` (`tenant_id`,`order_item_id`,`project_id`),
  KEY `idx_order_item_selection_version` (`tenant_id`,`package_version_id`,`project_id`),
  CONSTRAINT `fk_order_item_selection_item` FOREIGN KEY (`order_item_id`) REFERENCES `crm_order_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_order_item_selection_version` FOREIGN KEY (`package_version_id`) REFERENCES `crm_package_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_order_item_selection_project` FOREIGN KEY (`project_id`) REFERENCES `crm_project` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_order_item_selection_grant` CHECK (`grant_count` > 0 AND `reference_price_fen` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单实际分组选择不可变快照；支付仅发放这些项目';

CREATE TRIGGER `trg_order_item_selection_no_update` BEFORE UPDATE ON `crm_order_item_selection`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Order item selection snapshot is immutable';
CREATE TRIGGER `trg_order_item_selection_no_delete` BEFORE DELETE ON `crm_order_item_selection`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Order item selection snapshot is append only';
