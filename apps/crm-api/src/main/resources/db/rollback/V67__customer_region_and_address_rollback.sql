-- V67 回滚脚本：移除客户省市区与详细地址扩展列及索引，保留原 city_code 不受影响。
ALTER TABLE `crm_customer`
  DROP KEY `idx_customer_region`,
  DROP COLUMN `detail_address`,
  DROP COLUMN `district_name`,
  DROP COLUMN `district_code`,
  DROP COLUMN `region_city_name`,
  DROP COLUMN `region_city_code`,
  DROP COLUMN `province_name`,
  DROP COLUMN `province_code`;
