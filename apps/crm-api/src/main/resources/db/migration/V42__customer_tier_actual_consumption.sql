ALTER TABLE `crm_customer_tier_level`
  ADD COLUMN `min_consumption_fen` BIGINT UNSIGNED NULL COMMENT '进入该等级所需最低实际消费金额（分）；订单实付减成功退款，负数按0' AFTER `min_score`;

ALTER TABLE `crm_customer_tier_metric_snapshot`
  ADD COLUMN `actual_consumption_fen` BIGINT UNSIGNED NULL COMMENT '本次实际消费金额（分）：订单实付减成功退款，负数按0；旧评分快照为空' AFTER `communication_complete`;
