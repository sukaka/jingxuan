-- P1-2 仅本地虚构企微数据，不代表真实企业、员工或客户授权。
INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) VALUES
 (1,'MOCK-CORP-001','staff-2',2,1),(1,'MOCK-CORP-001','staff-3',3,1)
 ON DUPLICATE KEY UPDATE staff_user_id=VALUES(staff_user_id),enabled=VALUES(enabled);

INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code,evidence_ref) VALUES
 (1,'MOCK-CORP-001',2,1,'MOCK','mock://archive-member/staff-2'),
 (1,'MOCK-CORP-001',3,1,'MOCK','mock://archive-member/staff-3')
 ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),source_code=VALUES(source_code),evidence_ref=VALUES(evidence_ref);

INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,consent_status,consent_evidence_ref,link_type,link_reason,created_by) VALUES
 (1,'MOCK-CORP-001','WECOM-EXT-1010',2,1010,'GRANTED','mock://consent/1010','MANUAL','DEV ONLY：虚构人工确认',1),
 (1,'MOCK-CORP-001','WECOM-EXT-UNMATCHED',2,NULL,'GRANTED','mock://consent/unmatched',NULL,NULL,NULL),
 (1,'MOCK-CORP-001','WECOM-EXT-REVOKED',3,NULL,'REVOKED','mock://consent/revoked',NULL,NULL,NULL)
 ON DUPLICATE KEY UPDATE consent_status=VALUES(consent_status),consent_evidence_ref=VALUES(consent_evidence_ref);

INSERT INTO crm_wecom_cursor (tenant_id,corp_id,archive_scope,last_seq,status,data_gap,gap_reason)
VALUES (1,'MOCK-CORP-001','MOCK_ARCHIVE',0,'NEVER_SYNCED',1,'尚未采集，无法判断沟通情况')
 ON DUPLICATE KEY UPDATE corp_id=VALUES(corp_id);
