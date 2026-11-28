-- V67: 客户所在地区（全国省/市/区三级行政区划）与详细地址（选填）字段及历史 city_code 兼容回填。
-- 约束：
-- 1. 保留现有 city_code 列以兼容客户分群、批量打标、数据导出及历史字典数据；
-- 2. 新增 province_code/region_city_code/district_code 存国标行政区划码（含无区县地级市9位镇街码），均可空；
-- 3. detail_address 为客户详细地址（选填，最长255字符）；
-- 4. 对已有命中标准 49 城市编码的老客户自动补齐 province/region_city 字段，不覆盖已有值。

ALTER TABLE `crm_customer`
  ADD COLUMN `province_code` VARCHAR(32) NULL COMMENT '省份行政区划码(GB/T 2260)' AFTER `city_code`,
  ADD COLUMN `province_name` VARCHAR(64) NULL COMMENT '省份名称' AFTER `province_code`,
  ADD COLUMN `region_city_code` VARCHAR(32) NULL COMMENT '城市行政区划码(GB/T 2260)' AFTER `province_name`,
  ADD COLUMN `region_city_name` VARCHAR(64) NULL COMMENT '城市名称' AFTER `region_city_code`,
  ADD COLUMN `district_code` VARCHAR(32) NULL COMMENT '区县行政区划码(GB/T 2260)' AFTER `region_city_name`,
  ADD COLUMN `district_name` VARCHAR(64) NULL COMMENT '区县名称' AFTER `district_code`,
  ADD COLUMN `detail_address` VARCHAR(255) NULL COMMENT '详细地址（选填）' AFTER `district_name`,
  ADD KEY `idx_customer_region` (`tenant_id`, `deleted`, `province_code`, `region_city_code`, `district_code`, `id`);

-- 将存量已维护标准 cities 字典编码的客户安全回填至省/市结构化字段（仅补空，不触发 updated_at 抖动）
UPDATE `crm_customer` c
JOIN (
  SELECT 'BJ' AS city_code, '110000' AS p_code, '北京市' AS p_name, '110100' AS c_code, '北京市' AS c_name
  UNION ALL SELECT 'SH', '310000', '上海市', '310100', '上海市'
  UNION ALL SELECT 'GZ', '440000', '广东省', '440100', '广州市'
  UNION ALL SELECT 'SZ', '440000', '广东省', '440300', '深圳市'
  UNION ALL SELECT 'HZ', '330000', '浙江省', '330100', '杭州市'
  UNION ALL SELECT 'CD', '510000', '四川省', '510100', '成都市'
  UNION ALL SELECT 'CQ', '500000', '重庆市', '500100', '重庆市'
  UNION ALL SELECT 'NJ', '320000', '江苏省', '320100', '南京市'
  UNION ALL SELECT 'WH', '420000', '湖北省', '420100', '武汉市'
  UNION ALL SELECT 'SUZ', '320000', '江苏省', '320500', '苏州市'
  UNION ALL SELECT 'TJ', '120000', '天津市', '120100', '天津市'
  UNION ALL SELECT 'XA', '610000', '陕西省', '610100', '西安市'
  UNION ALL SELECT 'QD', '370000', '山东省', '370200', '青岛市'
  UNION ALL SELECT 'CS', '430000', '湖南省', '430100', '长沙市'
  UNION ALL SELECT 'ZZ', '410000', '河南省', '410100', '郑州市'
  UNION ALL SELECT 'NB', '330000', '浙江省', '330200', '宁波市'
  UNION ALL SELECT 'WX', '320000', '江苏省', '320200', '无锡市'
  UNION ALL SELECT 'XM', '350000', '福建省', '350200', '厦门市'
  UNION ALL SELECT 'FZ', '350000', '福建省', '350100', '福州市'
  UNION ALL SELECT 'HF', '340000', '安徽省', '340100', '合肥市'
  UNION ALL SELECT 'JN', '370000', '山东省', '370100', '济南市'
  UNION ALL SELECT 'DL', '210000', '辽宁省', '210200', '大连市'
  UNION ALL SELECT 'SY', '210000', '辽宁省', '210100', '沈阳市'
  UNION ALL SELECT 'KM', '530000', '云南省', '530100', '昆明市'
  UNION ALL SELECT 'FS', '440000', '广东省', '440600', '佛山市'
  UNION ALL SELECT 'DG', '440000', '广东省', '441900', '东莞市'
  UNION ALL SELECT 'HRB', '230000', '黑龙江省', '230100', '哈尔滨市'
  UNION ALL SELECT 'CC', '220000', '吉林省', '220100', '长春市'
  UNION ALL SELECT 'SJZ', '130000', '河北省', '130100', '石家庄市'
  UNION ALL SELECT 'TY', '140000', '山西省', '140100', '太原市'
  UNION ALL SELECT 'NC', '360000', '江西省', '360100', '南昌市'
  UNION ALL SELECT 'NN', '450000', '广西壮族自治区', '450100', '南宁市'
  UNION ALL SELECT 'GY', '520000', '贵州省', '520100', '贵阳市'
  UNION ALL SELECT 'LZ', '620000', '甘肃省', '620100', '兰州市'
  UNION ALL SELECT 'HK', '460000', '海南省', '460100', '海口市'
  UNION ALL SELECT 'SYA', '460000', '海南省', '460200', '三亚市'
  UNION ALL SELECT 'WLMQ', '650000', '新疆维吾尔自治区', '650100', '乌鲁木齐市'
  UNION ALL SELECT 'HHHT', '150000', '内蒙古自治区', '150100', '呼和浩特市'
  UNION ALL SELECT 'YC', '640000', '宁夏回族自治区', '640100', '银川市'
  UNION ALL SELECT 'XN', '630000', '青海省', '630100', '西宁市'
  UNION ALL SELECT 'LS', '540000', '西藏自治区', '540100', '拉萨市'
  UNION ALL SELECT 'WZ', '330000', '浙江省', '330300', '温州市'
  UNION ALL SELECT 'ZH', '440000', '广东省', '440400', '珠海市'
  UNION ALL SELECT 'CZ', '320000', '江苏省', '320400', '常州市'
  UNION ALL SELECT 'NT', '320000', '江苏省', '320600', '南通市'
  UNION ALL SELECT 'YT', '370000', '山东省', '370600', '烟台市'
  UNION ALL SELECT 'HKG', '810000', '香港特别行政区', NULL, NULL
  UNION ALL SELECT 'MAC', '820000', '澳门特别行政区', NULL, NULL
  UNION ALL SELECT 'TPE', '710000', '台湾省', '710100', '台北市'
) m ON m.city_code = c.city_code
SET c.province_code = m.p_code,
    c.province_name = m.p_name,
    c.region_city_code = m.c_code,
    c.region_city_name = m.c_name,
    c.updated_at = c.updated_at
WHERE c.province_code IS NULL;
