-- V60 有赞历史订单只读快照（与 CRM 正式订单/权益账本严格隔离）及 C 端年终 H5 统一免密登录基础设施。
-- 约束：
-- 1. 金额一律 BIGINT 整数分；日期 UTC 存储，展示与自然年口径按 Asia/Shanghai；
-- 2. OpenID / UnionID / 手机号均仅存 HMAC 盲索引（*_hash）及脱敏展示串，不落明文列；
-- 3. crm_youzan_annual_order_snapshot 仅供年终 H5 只读展示，严禁触发权益发放或改写 crm_order。

CREATE TABLE `crm_youzan_annual_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '有赞店铺kdt_id',
  `yz_open_id` VARCHAR(128) NOT NULL COMMENT '有赞买家唯一开放标识yz_open_id',
  `customer_id` BIGINT UNSIGNED NULL COMMENT '可选关联CRM客户ID（仅只读关联，不自动合并）',
  `display_name` VARCHAR(96) NOT NULL COMMENT '脱敏展示名称（优先有赞客户姓名>昵称>默认称呼）',
  `gender_hint` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'FEMALE/MALE/UNKNOWN',
  `weixin_open_id_hash` CHAR(64) NULL COMMENT '微信服务号OpenID HMAC盲索引',
  `weixin_union_id_hash` CHAR(64) NULL COMMENT '微信开放平台UnionID HMAC盲索引',
  `mobile_hash` CHAR(64) NULL COMMENT '归一化手机号HMAC盲索引（与crm_customer.mobile_hash同算法）',
  `mobile_masked` VARCHAR(32) NULL COMMENT '脱敏手机号138****5678',
  `order_count` INT NOT NULL DEFAULT 0 COMMENT '只读快照累计已支付订单数',
  `total_payment_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '只读快照累计净实付金额（整数分）',
  `first_order_at` DATETIME(3) NULL COMMENT '有赞最早实付时间UTC',
  `last_order_at` DATETIME(3) NULL COMMENT '有赞最近实付时间UTC',
  `synced_at` DATETIME(3) NOT NULL COMMENT '最近同步时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_yz_annual_profile_buyer` (`tenant_id`, `shop_id`, `yz_open_id`),
  KEY `idx_yz_annual_profile_openid` (`tenant_id`, `weixin_open_id_hash`),
  KEY `idx_yz_annual_profile_unionid` (`tenant_id`, `weixin_union_id_hash`),
  KEY `idx_yz_annual_profile_mobile` (`tenant_id`, `mobile_hash`),
  KEY `idx_yz_annual_profile_customer` (`tenant_id`, `customer_id`),
  CONSTRAINT `fk_yz_annual_profile_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞买家年度只读档案镜像（不含明文手机号与明文OpenID）';

CREATE TABLE `crm_youzan_annual_order_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '有赞店铺kdt_id',
  `tid` VARCHAR(64) NOT NULL COMMENT '有赞订单号tid',
  `yz_open_id` VARCHAR(128) NOT NULL COMMENT '有赞买家yz_open_id',
  `order_status` VARCHAR(48) NOT NULL COMMENT '有赞订单状态原文',
  `is_paid` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已真实支付（排除未付/关闭单）',
  `ordered_at` DATETIME(3) NULL COMMENT '有赞下单时间UTC',
  `paid_at` DATETIME(3) NULL COMMENT '有赞支付时间UTC',
  `payment_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '订单实付金额（整数分）',
  `refunded_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '已退款金额（整数分）',
  `title_summary` VARCHAR(160) NOT NULL COMMENT '商品主标题脱敏摘要',
  `items_json` JSON NOT NULL COMMENT '有赞商品明细只读快照JSON数组',
  `synced_at` DATETIME(3) NOT NULL COMMENT '最近同步时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_yz_annual_order_tid` (`tenant_id`, `shop_id`, `tid`),
  KEY `idx_yz_annual_order_buyer_paid` (`tenant_id`, `yz_open_id`, `is_paid`, `paid_at`),
  KEY `idx_yz_annual_order_shop_paid` (`tenant_id`, `shop_id`, `is_paid`, `paid_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞历史订单只读快照（仅用于年终H5轨迹展示，不发放权益）';

CREATE TABLE `crm_h5_c_identity_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `channel_type` VARCHAR(24) NOT NULL COMMENT 'WECHAT_MP/WECHAT_MINI',
  `openid_hash` CHAR(64) NOT NULL COMMENT '当前渠道OpenID HMAC盲索引',
  `unionid_hash` CHAR(64) NULL COMMENT '微信开放平台UnionID HMAC盲索引',
  `shop_id` VARCHAR(64) NULL COMMENT '关联有赞店铺kdt_id',
  `yz_open_id` VARCHAR(128) NULL COMMENT '关联有赞买家yz_open_id',
  `customer_id` BIGINT UNSIGNED NULL COMMENT '关联CRM客户ID',
  `mobile_hash` CHAR(64) NULL COMMENT '验证通过的手机号HMAC盲索引',
  `bound_via` VARCHAR(32) NOT NULL COMMENT 'WECHAT_OAUTH/MINIPROGRAM/SMS_VERIFY',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '绑定时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_h5_c_binding_openid` (`tenant_id`, `channel_type`, `openid_hash`),
  KEY `idx_h5_c_binding_unionid` (`tenant_id`, `unionid_hash`),
  KEY `idx_h5_c_binding_yz` (`tenant_id`, `yz_open_id`),
  CONSTRAINT `fk_h5_c_binding_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='C端年终H5微信身份与有赞/CRM客户绑定表';

CREATE TABLE `crm_h5_sms_verification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `mobile_hash` CHAR(64) NOT NULL COMMENT '手机号HMAC盲索引',
  `code_hash` CHAR(64) NOT NULL COMMENT '验证码HMAC哈希（不存明文）',
  `attempt_count` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已尝试校验次数',
  `expires_at` DATETIME(3) NOT NULL COMMENT '过期时间UTC',
  `consumed_at` DATETIME(3) NULL COMMENT '已核销时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  KEY `idx_h5_sms_mobile_created` (`tenant_id`, `mobile_hash`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='C端年终H5手机号验证码校验记录';

CREATE TABLE `crm_h5_c_visit_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '有赞店铺kdt_id或CRM',
  `yz_open_id` VARCHAR(128) NOT NULL COMMENT '有赞买家yz_open_id或CRM客户标识',
  `customer_id` BIGINT UNSIGNED NULL COMMENT '可选关联CRM客户ID',
  `display_name_snapshot` VARCHAR(96) NOT NULL COMMENT '访问时脱敏称呼快照',
  `report_year` SMALLINT UNSIGNED NOT NULL COMMENT '盘点自然年',
  `session_key` VARCHAR(64) NOT NULL COMMENT '单次打开会话标识',
  `entry_channel` VARCHAR(32) NOT NULL DEFAULT 'UNIFIED' COMMENT 'WECHAT_OAUTH/MINIPROGRAM/SMS_VERIFY/UNIFIED',
  `first_open_at` DATETIME(3) NOT NULL COMMENT '首次打开时间UTC',
  `last_active_at` DATETIME(3) NOT NULL COMMENT '最近活跃时间UTC',
  `visible_duration_ms` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '有效可见停留毫秒数',
  `max_scene_reached` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '最深到达幕1..6',
  `viewed_scene_count` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '已看幕数1..6',
  `viewed_scenes_json` JSON NOT NULL COMMENT '已看幕数组',
  `scene_durations_json` JSON NOT NULL COMMENT '各幕累计停留毫秒对象',
  `ignited_lamp` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否点亮灯箱',
  `opened_order_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '打开订单明细次数',
  `toggled_privacy` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否切换隐私模式',
  `generated_poster` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否导出海报',
  `clicked_book_butler` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否提交管家预约意向',
  `client_device` VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'MOBILE/DESKTOP/WECHAT/UNKNOWN',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_h5_c_visit_session` (`tenant_id`, `report_year`, `yz_open_id`, `session_key`),
  KEY `idx_h5_c_visit_year_active` (`tenant_id`, `report_year`, `last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='C端统一入口（纯有赞快照用户）年终H5访问埋点统计';
