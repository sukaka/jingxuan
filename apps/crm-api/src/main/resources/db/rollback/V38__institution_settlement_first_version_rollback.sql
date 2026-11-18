-- 回滚说明：只撤销数据库默认值和版本下限，不把已发布版本降回0。
-- 版本1可能来自V38升级，也可能来自V38后的首次写入，无法可靠区分；强制降级会重新引入version=0丢失更新。
ALTER TABLE `crm_institution_settlement_config`
  DROP CHECK `chk_institution_settlement_config_version`,
  MODIFY COLUMN `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本';
