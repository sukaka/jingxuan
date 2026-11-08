-- P0-1b 兼容迁移：原客户信息表 23 字段映射字典（docs/01_需求与产品/15 第 1 节、docs/14 第 2 节要求独立维护）。
-- V3 已在 dev 环境应用，本迁移仅追加新表与基线映射行，不修改既有表。
-- 基线映射为业务参照数据（原名/原表/新字段/来源公式），随库走而非 dev 种子；original_table 均来自原"客户信息表"截图。

CREATE TABLE IF NOT EXISTS `crm_legacy_field_mapping` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `field_key` VARCHAR(64) NOT NULL COMMENT '稳定字段键(英文)',
  `original_name` VARCHAR(128) NOT NULL COMMENT '原表字段名(23字段原名)',
  `original_table` VARCHAR(128) NOT NULL COMMENT '原表名称',
  `target_field` VARCHAR(192) NULL COMMENT '新系统字段/去向说明',
  `source_formula` VARCHAR(512) NULL COMMENT '原公式值及计算来源说明',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示顺序(原表列序)',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '启用',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_legacy_field_mapp_1` (`tenant_id`,`field_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='原23字段映射字典(原名/原表/新字段/公式)';

-- 23 条基线映射（幂等；field_key 稳定，target_field 指向 P0-1b 已落地字段或 PENDING 去向）
INSERT INTO crm_legacy_field_mapping (tenant_id, field_key, original_name, original_table, target_field, source_formula, sort_order, enabled)
SELECT m.* FROM (
  SELECT 1 AS tenant_id, 'USER_NAME' AS field_key, '用户姓名' AS original_name, '客户信息表' AS original_table, 'crm_customer.display_name' AS target_field, NULL AS source_formula, 1 AS sort_order, 1 AS enabled
  UNION ALL SELECT 1, 'NICKNAME', '客户昵称', '客户信息表', 'crm_customer.nickname', '与真实姓名区分', 2, 1
  UNION ALL SELECT 1, 'MOBILE', '客户电话', '客户信息表', 'crm_customer.mobile_cipher/mobile_hash', '可空；密文+盲索引，不能作PK', 3, 1
  UNION ALL SELECT 1, 'SOURCE', '客户来源', '客户信息表', 'crm_customer.source_code', '与渠道独立，历史来源需映射', 4, 1
  UNION ALL SELECT 1, 'CHANNEL', '客户渠道', '客户信息表', 'crm_customer.channel_code', '不与来源合并', 5, 1
  UNION ALL SELECT 1, 'NEW_OLD', '新客/老客', '客户信息表', NULL, '推荐新增 new_old_code；是否自动计算需确认（未建列）', 6, 1
  UNION ALL SELECT 1, 'CITY', '所在城市', '客户信息表', 'crm_customer.city_code', '系统字典动态维护', 7, 1
  UNION ALL SELECT 1, 'ATTENTION_PROJECTS', '关注项目', '客户信息表', 'crm_customer.attention_projects', '原表公式/关联规则需核对', 8, 1
  UNION ALL SELECT 1, 'OWNER_NAME', '所属管家（真实姓名）', '客户信息表', 'crm_customer.owner_staff_id', '员工ID，历史转交日志', 9, 1
  UNION ALL SELECT 1, 'OWNER_ACCOUNT', '所属账号', '客户信息表', NULL, '建议新增 owner_account_id；账号语义待确认（未建列）', 10, 1
  UNION ALL SELECT 1, 'WRITER', '填写者（填写账号名称）', '客户信息表', 'crm_customer_legacy.legacy_writer', '旧字段来源待核对', 11, 1
  UNION ALL SELECT 1, 'MOBILE_DUPLICATED', '电话-是否重复', '客户信息表', 'crm_merge_case / computed', '不保存为人工填写判断；按 mobile_hash 实时等值计数', 12, 1
  UNION ALL SELECT 1, 'ORDER_COUNT', '下单数', '客户信息表', NULL, '聚合CRM有效订单（订单域 P0-3）；旧公式与新口径分别保存', 13, 1
  UNION ALL SELECT 1, 'ORDER_TOTAL_AMOUNT', '订单总金额', '客户信息表', NULL, '聚合订单原价/实付分别显示（订单域 P0-3）；不得与累计实付混用', 14, 1
  UNION ALL SELECT 1, 'CUSTOMER_STATUS', '客户状态', '客户信息表', 'crm_customer.customer_status', '动态字典', 15, 1
  UNION ALL SELECT 1, 'SUBMITTED_AT', '提交时间', '客户信息表', 'crm_customer_legacy.legacy_submitted_at', '不能默认等于创建时间', 16, 1
  UNION ALL SELECT 1, 'FIRST_IMPORT_AT', '客户首次入库时间', '客户信息表', 'crm_customer_legacy.legacy_first_import_at', '与客户添加时间不同', 17, 1
  UNION ALL SELECT 1, 'FIRST_ADDED_AT', '客户添加时间', '客户信息表', 'crm_customer.first_added_at', '是否企微添加需核实', 18, 1
  UNION ALL SELECT 1, 'FIRST_ORDER_AT', '首次下单时间', '客户信息表', 'crm_customer.first_order_at', '源订单证据决定', 19, 1
  UNION ALL SELECT 1, 'LAST_FOLLOWUP_AT', '最后一次跟进时间', '客户信息表', 'crm_customer.last_followup_at', '依真实跟进计算', 20, 1
  UNION ALL SELECT 1, 'BLOCKER_NOTE', '卡点情况', '客户信息表', 'crm_customer.blocker_note', '原公式需核对；AI建议分开', 21, 1
  UNION ALL SELECT 1, 'REMARK', '备注', '客户信息表', 'crm_customer.remark', '非结构化备注', 22, 1
  UNION ALL SELECT 1, 'REMARK_NAME', '备注名', '客户信息表', 'crm_customer.remark_name', '与客户昵称分开', 23, 1
) m
ON DUPLICATE KEY UPDATE original_name=VALUES(original_name), original_table=VALUES(original_table),
  target_field=VALUES(target_field), source_formula=VALUES(source_formula), sort_order=VALUES(sort_order),
  enabled=VALUES(enabled);
