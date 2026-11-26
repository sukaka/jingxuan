-- V59 年终购买轨迹 H5 正式版：客户年度冻结快照、公开分享链接、访问时长/页数埋点与只追加事件账本。
-- 约束：
-- 1. 金额一律 BIGINT 整数分；日期 UTC 存储，年度口径按 Asia/Shanghai 计算；
-- 2. 快照字段受触发器保护不可覆盖，状态仅允许 ACTIVE -> DISABLED；
-- 3. 访问事件流水只追加，禁止 UPDATE / DELETE。

CREATE TABLE `crm_annual_report` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT 'CRM客户ID，不在公开页暴露',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '生成时客户归属管家快照，用于数据范围隔离',
  `team_id` BIGINT UNSIGNED NULL COMMENT '生成时客户团队快照',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '创建员工ID',
  `report_year` SMALLINT UNSIGNED NOT NULL COMMENT '盘点自然年，按Asia/Shanghai口径',
  `share_key` CHAR(43) NOT NULL COMMENT '256-bit高随机公开访问键，不携带客户或租户ID',
  `tone_code` VARCHAR(32) NOT NULL COMMENT '文案语调：POETIC/CLINICAL/MINIMAL',
  `tone_reason` VARCHAR(160) NOT NULL COMMENT '语调匹配依据说明',
  `display_name_snapshot` VARCHAR(96) NOT NULL COMMENT '脱敏后的客户称呼快照',
  `tier_label_snapshot` VARCHAR(64) NOT NULL COMMENT '会员等级称号快照',
  `butler_name_snapshot` VARCHAR(96) NOT NULL COMMENT '专属管家姓名快照',
  `order_count` INT NOT NULL DEFAULT 0 COMMENT '年度有效实付订单数',
  `net_spend_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '年度净消费金额（整数分，实付扣成功退款）',
  `redeem_count` INT NOT NULL DEFAULT 0 COMMENT '年度正式核销次数（排除已批准冲正）',
  `remaining_entitlement_count` INT NOT NULL DEFAULT 0 COMMENT '生成时剩余可用权益总次数',
  `snapshot_json` JSON NOT NULL COMMENT '冻结的脱敏公开只读快照JSON',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED，停用后公开链接返回404',
  `disable_reason` VARCHAR(512) NULL COMMENT '停用原因，已脱敏',
  `disabled_by` BIGINT UNSIGNED NULL COMMENT '停用操作人',
  `disabled_at` DATETIME(3) NULL COMMENT '停用时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_annual_report_share_key` (`share_key`),
  KEY `idx_annual_report_tenant_year` (`tenant_id`, `report_year`, `created_at`, `id`),
  KEY `idx_annual_report_customer_year` (`tenant_id`, `customer_id`, `report_year`, `id`),
  KEY `idx_annual_report_scope` (`tenant_id`, `owner_staff_id`, `team_id`, `report_year`),
  CONSTRAINT `fk_annual_report_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_annual_report_owner` FOREIGN KEY (`owner_staff_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_annual_report_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_annual_report_disabled_by` FOREIGN KEY (`disabled_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户年终购买轨迹H5只读快照，冻结不可覆盖';

CREATE TABLE `crm_annual_report_visit_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `report_id` BIGINT UNSIGNED NOT NULL COMMENT '年度报告快照ID',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户ID快照',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '归属管家快照',
  `team_id` BIGINT UNSIGNED NULL COMMENT '归属团队快照',
  `report_year` SMALLINT UNSIGNED NOT NULL COMMENT '盘点自然年',
  `session_key` VARCHAR(64) NOT NULL COMMENT '单次打开会话标识，幂等合并',
  `first_open_at` DATETIME(3) NOT NULL COMMENT '首次打开时间UTC',
  `last_active_at` DATETIME(3) NOT NULL COMMENT '最近活跃时间UTC',
  `visible_duration_ms` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计前台可见有效停留毫秒数',
  `max_scene_reached` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '最深到达幕序号1..6',
  `viewed_scene_count` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '去重已看幕数1..6',
  `viewed_scenes_json` JSON NOT NULL COMMENT '去重已看幕序号数组',
  `scene_durations_json` JSON NOT NULL COMMENT '各幕累计停留毫秒对象',
  `ignited_lamp` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否点亮灯箱',
  `opened_order_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '展开查看订单明细次数',
  `toggled_privacy` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否切换隐藏金额隐私模式',
  `generated_poster` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否生成PNG纪念海报',
  `clicked_book_butler` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否点击通知专属管家预约',
  `booking_task_id` BIGINT UNSIGNED NULL COMMENT '关联创建的管家FOLLOWUP待办任务ID',
  `client_device` VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'MOBILE/DESKTOP/WECHAT/UNKNOWN',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_annual_visit_session` (`tenant_id`, `report_id`, `session_key`),
  KEY `idx_annual_visit_report` (`tenant_id`, `report_id`, `last_active_at`),
  KEY `idx_annual_visit_customer_year` (`tenant_id`, `customer_id`, `report_year`),
  KEY `idx_annual_visit_scope` (`tenant_id`, `owner_staff_id`, `team_id`, `report_year`),
  CONSTRAINT `fk_annual_visit_report` FOREIGN KEY (`report_id`) REFERENCES `crm_annual_report` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_annual_visit_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户年终H5访问会话聚合统计';

CREATE TABLE `crm_annual_report_visit_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `report_id` BIGINT UNSIGNED NOT NULL COMMENT '年度报告快照ID',
  `session_id` BIGINT UNSIGNED NOT NULL COMMENT '访问会话ID',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户ID快照',
  `event_type` VARCHAR(48) NOT NULL COMMENT 'SESSION_OPEN/SCENE_CHANGE/HEARTBEAT/VISIBILITY_HIDE/PAGE_HIDE/IGNITE_LAMP/VIEW_ORDER/TOGGLE_PRIVACY/GENERATE_POSTER/BOOKING_INTENT',
  `scene_index` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前幕序号1..6',
  `delta_duration_ms` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本次上报新增有效停留毫秒（服务端封顶）',
  `detail_json` JSON NULL COMMENT '脱敏事件附加元数据',
  `occurred_at` DATETIME(3) NOT NULL COMMENT '事件发生时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间UTC',
  PRIMARY KEY (`id`),
  KEY `idx_annual_event_report` (`tenant_id`, `report_id`, `occurred_at`),
  KEY `idx_annual_event_session` (`tenant_id`, `session_id`, `id`),
  CONSTRAINT `fk_annual_event_report` FOREIGN KEY (`report_id`) REFERENCES `crm_annual_report` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_annual_event_session` FOREIGN KEY (`session_id`) REFERENCES `crm_annual_report_visit_session` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户年终H5访问与转化事件只追加账本';

CREATE TRIGGER `trg_annual_report_snapshot_guard` BEFORE UPDATE ON `crm_annual_report`
FOR EACH ROW BEGIN
  IF NOT (OLD.status = 'ACTIVE' AND NEW.status = 'DISABLED' AND NEW.version = OLD.version + 1
    AND NEW.tenant_id = OLD.tenant_id
    AND NEW.customer_id = OLD.customer_id
    AND NEW.owner_staff_id = OLD.owner_staff_id
    AND NEW.report_year = OLD.report_year
    AND NEW.share_key = OLD.share_key
    AND NEW.tone_code = OLD.tone_code
    AND NEW.order_count = OLD.order_count
    AND NEW.net_spend_fen = OLD.net_spend_fen
    AND NEW.redeem_count = OLD.redeem_count
    AND NEW.remaining_entitlement_count = OLD.remaining_entitlement_count) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Annual report snapshot only allows ACTIVE to DISABLED transition';
  END IF;
END;

CREATE TRIGGER `trg_annual_visit_event_no_update` BEFORE UPDATE ON `crm_annual_report_visit_event`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Annual report visit event is append only';

CREATE TRIGGER `trg_annual_visit_event_no_delete` BEFORE DELETE ON `crm_annual_report_visit_event`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Annual report visit event is append only';
