-- H-SEC-03: 合作渠道财务与收款资料变更审批单（加密新旧快照 + 脱敏展示 + 单渠道单笔在途约束）
CREATE TABLE IF NOT EXISTS `crm_channel_change_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `channel_id` BIGINT UNSIGNED NOT NULL COMMENT '合作渠道ID',
  `base_version` BIGINT UNSIGNED NOT NULL COMMENT '提交申请时渠道主数据乐观锁版本',
  `before_cipher` TEXT NULL COMMENT '变更前财务敏感快照AES-GCM密文JSON',
  `after_cipher` TEXT NOT NULL COMMENT '变更后财务敏感快照AES-GCM密文JSON',
  `before_masked` TEXT NOT NULL COMMENT '变更前脱敏JSON快照',
  `after_masked` TEXT NOT NULL COMMENT '变更后脱敏JSON快照',
  `reason` VARCHAR(1000) NOT NULL COMMENT '变更原因',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT 'PENDING_REVIEW/APPROVED/REJECTED/CANCELLED/CONFLICT_REVALIDATE',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '申请人用户ID',
  `requested_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '申请时间，UTC',
  `reviewer_user_id` BIGINT UNSIGNED NULL COMMENT '财务审批人用户ID',
  `reviewed_at` DATETIME(3) NULL COMMENT '审批时间，UTC',
  `review_comment` VARCHAR(1000) NULL COMMENT '审批意见或驳回原因',
  `active_channel_id` BIGINT UNSIGNED
    GENERATED ALWAYS AS (CASE WHEN `status` = 'PENDING_REVIEW' THEN `channel_id` ELSE NULL END) VIRTUAL
    COMMENT '仅PENDING_REVIEW时非空，保证单渠道同时间最多一条待财务审批申请',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_change_request_active` (`tenant_id`, `active_channel_id`),
  KEY `idx_channel_change_request_channel` (`tenant_id`, `channel_id`, `status`, `id`),
  KEY `idx_channel_change_request_status` (`tenant_id`, `status`, `id`),
  CONSTRAINT `chk_channel_change_request_status`
    CHECK (`status` IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED', 'CONFLICT_REVALIDATE')),
  CONSTRAINT `fk_channel_change_request_channel`
    FOREIGN KEY (`channel_id`) REFERENCES `crm_partner_channel` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合作渠道财务与收款资料变更审批单';
