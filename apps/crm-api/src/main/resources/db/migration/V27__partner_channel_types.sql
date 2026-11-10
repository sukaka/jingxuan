ALTER TABLE `crm_partner_channel`
  ADD COLUMN `channel_type` VARCHAR(32) NOT NULL DEFAULT 'BLOGGER' COMMENT '合作类型：BLOGGER博主达人/DISTRIBUTION分销/REFERRAL老带新' AFTER `name`,
  ADD KEY `idx_partner_channel_type_status` (`tenant_id`,`channel_type`,`status`),
  ADD CONSTRAINT `chk_partner_channel_type` CHECK (`channel_type` IN ('BLOGGER','DISTRIBUTION','REFERRAL'));

-- 存量渠道先按博主/达人归档，后续由授权人员核对修改；不修改订单归因快照。
-- 回滚需先备份并停用渠道编辑，再执行批准的 V27 回滚脚本。
