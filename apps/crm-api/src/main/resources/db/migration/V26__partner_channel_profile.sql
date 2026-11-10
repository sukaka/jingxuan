ALTER TABLE `crm_partner_channel`
  ADD COLUMN `commission_percent` DECIMAL(5,2) NULL COMMENT '分佣比例百分比，0-100，例如12.50表示12.5%' AFTER `platform_code`,
  ADD COLUMN `real_name` VARCHAR(80) NULL COMMENT '合作方真实姓名' AFTER `business_owner_user_id`,
  ADD COLUMN `contact_phone_cipher` VARCHAR(255) NULL COMMENT '联系电话AES-GCM密文，API解密后按权限返回' AFTER `real_name`,
  ADD COLUMN `id_card_number_cipher` VARCHAR(255) NULL COMMENT '18位身份证AES-GCM密文，禁止明文落库' AFTER `contact_phone_cipher`,
  ADD COLUMN `settlement_method` VARCHAR(64) NULL COMMENT '结算方式字典settlementTypes' AFTER `id_card_number_cipher`,
  ADD COLUMN `cooperation_start_date` DATE NULL COMMENT '合作开始日期' AFTER `settlement_method`,
  ADD COLUMN `cooperation_end_date` DATE NULL COMMENT '合作结束日期' AFTER `cooperation_start_date`,
  ADD COLUMN `payment_account_name_cipher` VARCHAR(512) NULL COMMENT '收款账户名称AES-GCM密文' AFTER `cooperation_end_date`,
  ADD COLUMN `payment_account_no_cipher` VARCHAR(512) NULL COMMENT '收款账号AES-GCM密文' AFTER `payment_account_name_cipher`,
  ADD COLUMN `payment_bank_name` VARCHAR(255) NULL COMMENT '收款开户行' AFTER `payment_account_no_cipher`,
  ADD KEY `idx_partner_channel_cooperation` (`tenant_id`,`status`,`cooperation_start_date`,`cooperation_end_date`),
  ADD CONSTRAINT `chk_partner_channel_commission` CHECK (`commission_percent` IS NULL OR `commission_percent` BETWEEN 0 AND 100),
  ADD CONSTRAINT `chk_partner_channel_dates` CHECK (`cooperation_end_date` IS NULL OR `cooperation_start_date` IS NULL OR `cooperation_end_date` >= `cooperation_start_date`);

-- 回滚前先停用渠道编辑并备份；回滚脚本仅用于明确批准的下线，不得在生产直接删除敏感字段。
