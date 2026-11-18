-- 仅用于未投入生产前的显式回滚；已产生等级历史/复核事实时不得执行破坏性回滚。
DROP TABLE IF EXISTS `crm_customer_tier_downgrade_review`;
DROP TABLE IF EXISTS `crm_customer_tier_history`;
DROP TABLE IF EXISTS `crm_customer_tier_metric_snapshot`;
DROP TABLE IF EXISTS `crm_customer_tier_level`;
DROP TABLE IF EXISTS `crm_customer_tier_rule`;

ALTER TABLE `crm_customer`
  DROP COLUMN `tier_lock_reason`,
  DROP COLUMN `tier_locked`;
