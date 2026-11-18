ALTER TABLE `crm_customer_tier_metric_snapshot`
  DROP COLUMN `actual_consumption_fen`;

ALTER TABLE `crm_customer_tier_level`
  DROP COLUMN `min_consumption_fen`;
