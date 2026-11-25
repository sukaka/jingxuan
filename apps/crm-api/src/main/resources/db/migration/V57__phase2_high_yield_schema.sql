-- V57: 阶段二高收益结构优化（预约里程碑时间戳、有赞订单最新事件投影、企微未关联生成列、素材最新版本与标签表、P1-A7/P1-P7约束与索引补齐）。
-- 所有变更与 V1..V56 保持严格兼容，不破坏历史账本与外键约束；回填 DML 显式保留原 updated_at 与 version。

-- ============================================================================
-- 1. crm_appointment 里程碑时间戳 + 安全回填 + 索引
-- ============================================================================
ALTER TABLE `crm_appointment`
  ADD COLUMN `confirmed_at` DATETIME(3) NULL COMMENT '机构确认时间，UTC' AFTER `status`,
  ADD COLUMN `checked_in_at` DATETIME(3) NULL COMMENT '客户实际到院签到时间，UTC' AFTER `confirmed_at`,
  ADD COLUMN `service_done_at` DATETIME(3) NULL COMMENT '机构服务完成时间，UTC' AFTER `checked_in_at`;

UPDATE `crm_appointment` a
INNER JOIN (
  SELECT `tenant_id`,
         `appointment_id`,
         MIN(CASE WHEN `to_status` = 'CONFIRMED' THEN `created_at` END) AS `confirmed_at`,
         MIN(CASE WHEN `to_status` = 'CHECKED_IN' THEN `created_at` END) AS `checked_in_at`,
         MIN(CASE WHEN `from_status` = 'CHECKED_IN' AND `to_status` = 'SERVICE_DONE' THEN `created_at` END) AS `service_done_at`
  FROM `crm_appointment_history`
  GROUP BY `tenant_id`, `appointment_id`
  HAVING `confirmed_at` IS NOT NULL
      OR `checked_in_at` IS NOT NULL
      OR `service_done_at` IS NOT NULL
) h ON h.`tenant_id` = a.`tenant_id` AND h.`appointment_id` = a.`id`
SET a.`confirmed_at` = h.`confirmed_at`,
    a.`checked_in_at` = h.`checked_in_at`,
    a.`service_done_at` = h.`service_done_at`,
    a.`updated_at` = a.`updated_at`;

ALTER TABLE `crm_appointment`
  ADD KEY `idx_appointment_confirmed_at` (`tenant_id`, `confirmed_at`),
  ADD KEY `idx_appointment_checked_in_at` (`tenant_id`, `checked_in_at`),
  ADD KEY `idx_appointment_service_done_at` (`tenant_id`, `service_done_at`);

-- ============================================================================
-- 2. crm_youzan_order_latest 投影表 + 回填
-- ============================================================================
CREATE TABLE IF NOT EXISTS `crm_youzan_order_latest` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '店铺ID',
  `external_order_no` VARCHAR(128) NOT NULL COMMENT '外部订单号',
  `latest_event_id` BIGINT UNSIGNED NOT NULL COMMENT '该外部订单最新原始事件ID(crm_youzan_event.id)',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_order_latest_1` (`tenant_id`, `shop_id`, `external_order_no`),
  KEY `idx_youzan_order_latest_event` (`tenant_id`, `shop_id`, `latest_event_id`),
  KEY `idx_youzan_order_latest_ev_id` (`latest_event_id`),
  CONSTRAINT `fk_youzan_order_latest_event` FOREIGN KEY (`latest_event_id`)
    REFERENCES `crm_youzan_event` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞外部订单最新原始事件投影表';

INSERT INTO `crm_youzan_order_latest` (`tenant_id`, `shop_id`, `external_order_no`, `latest_event_id`)
SELECT `tenant_id`, `shop_id`, `external_order_no`, MAX(`id`) AS `latest_event_id`
FROM `crm_youzan_event`
WHERE `external_order_no` IS NOT NULL
  AND `external_order_no` <> ''
GROUP BY `tenant_id`, `shop_id`, `external_order_no`
ON DUPLICATE KEY UPDATE
  `latest_event_id` = GREATEST(`latest_event_id`, VALUES(`latest_event_id`));

-- ============================================================================
-- 3. crm_wecom_contact.is_unlinked 生成列与索引
-- ============================================================================
ALTER TABLE `crm_wecom_contact`
  ADD COLUMN `is_unlinked` TINYINT(1)
    GENERATED ALWAYS AS (CASE WHEN `customer_id` IS NULL THEN 1 ELSE 0 END) STORED NOT NULL
    COMMENT '是否未关联客户(1=未关联,0=已关联)' AFTER `customer_id`,
  ADD KEY `idx_wecom_contact_unlinked` (`tenant_id`, `is_unlinked`, `id`);

-- ============================================================================
-- 4. crm_material.latest_revision_id 与 crm_material_tag 标签表
-- ============================================================================
ALTER TABLE `crm_material`
  ADD COLUMN `latest_revision_id` BIGINT UNSIGNED NULL COMMENT '最新修订版本ID(按revision_no最大，冗余加速)' AFTER `current_revision_id`,
  ADD KEY `idx_material_latest_revision` (`tenant_id`, `latest_revision_id`),
  ADD CONSTRAINT `fk_material_latest_revision` FOREIGN KEY (`latest_revision_id`)
    REFERENCES `crm_material_revision` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

UPDATE `crm_material` m
INNER JOIN (
  SELECT r.`tenant_id`, r.`material_id`, r.`id` AS `latest_revision_id`
  FROM `crm_material_revision` r
  INNER JOIN (
    SELECT `tenant_id`, `material_id`, MAX(`revision_no`) AS `max_revision_no`
    FROM `crm_material_revision`
    GROUP BY `tenant_id`, `material_id`
  ) mx ON mx.`tenant_id` = r.`tenant_id`
      AND mx.`material_id` = r.`material_id`
      AND mx.`max_revision_no` = r.`revision_no`
) lr ON lr.`tenant_id` = m.`tenant_id` AND lr.`material_id` = m.`id`
SET m.`latest_revision_id` = lr.`latest_revision_id`,
    m.`updated_at` = m.`updated_at`;

CREATE TABLE IF NOT EXISTS `crm_material_tag` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `material_id` BIGINT UNSIGNED NOT NULL COMMENT '素材ID',
  `tag_code` VARCHAR(64) NOT NULL COMMENT '素材标签代码',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_tag_1` (`tenant_id`, `material_id`, `tag_code`),
  KEY `idx_material_tag_reverse` (`tenant_id`, `tag_code`, `material_id`),
  KEY `idx_material_tag_material` (`material_id`),
  CONSTRAINT `fk_material_tag_material` FOREIGN KEY (`material_id`)
    REFERENCES `crm_material` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='素材标签索引表';

INSERT IGNORE INTO `crm_material_tag` (`tenant_id`, `material_id`, `tag_code`)
SELECT DISTINCT
  m.`tenant_id`,
  m.`id`,
  TRIM(JSON_UNQUOTE(jt.`elem`)) AS `tag_code`
FROM `crm_material` m
LEFT JOIN `crm_material_revision` r
  ON r.`tenant_id` = m.`tenant_id` AND r.`id` = m.`latest_revision_id`
JOIN JSON_TABLE(
  CASE
    WHEN JSON_VALID(COALESCE(r.`tags_json`, m.`tags_json`)) = 1
      AND JSON_TYPE(COALESCE(r.`tags_json`, m.`tags_json`)) = 'ARRAY'
    THEN COALESCE(r.`tags_json`, m.`tags_json`)
    ELSE JSON_ARRAY()
  END,
  '$[*]' COLUMNS (`elem` JSON PATH '$')
) jt
WHERE JSON_TYPE(jt.`elem`) = 'STRING'
  AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(jt.`elem`))) BETWEEN 1 AND 64;

-- ============================================================================
-- 5. P1-A7 / P1-P7 低风险约束与列表索引补齐
-- ============================================================================
DELETE g1 FROM `crm_wecom_data_gap` g1
INNER JOIN `crm_wecom_data_gap` g2
  ON g2.`tenant_id` = g1.`tenant_id`
  AND g2.`corp_id` = g1.`corp_id`
  AND g2.`archive_scope` = g1.`archive_scope`
  AND g2.`gap_type` = g1.`gap_type`
  AND (g2.`from_seq` = 0 OR (g2.`from_seq` IS NULL AND g2.`id` < g1.`id`))
WHERE g1.`from_seq` IS NULL;

UPDATE `crm_wecom_data_gap`
SET `from_seq` = 0
WHERE `from_seq` IS NULL;

ALTER TABLE `crm_wecom_data_gap`
  MODIFY COLUMN `from_seq` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '缺口起始seq';

ALTER TABLE `crm_order`
  ADD COLUMN `external_order_dedupe_key` VARCHAR(274)
    GENERATED ALWAYS AS (
      CASE
        WHEN `external_order_no` IS NOT NULL
        THEN CONCAT(`source_system`, ':', COALESCE(`external_shop_id`, ''), ':', `external_order_no`)
        ELSE NULL
      END
    ) VIRTUAL COMMENT '外部订单去重键(解决external_shop_id为NULL时uk_order_2失效问题)' AFTER `external_shop_id`,
  ADD UNIQUE KEY `uk_order_external_dedupe` (`tenant_id`, `external_order_dedupe_key`);

ALTER TABLE `crm_institution`
  ADD KEY `idx_institution_archived_status_id` (`tenant_id`, `archived`, `status`, `id`);

ALTER TABLE `crm_profile_change_request`
  ADD KEY `idx_profile_change_status_id` (`tenant_id`, `status`, `id`);
