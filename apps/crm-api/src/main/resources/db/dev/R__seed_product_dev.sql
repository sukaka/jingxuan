-- 仅 local：全部为明确虚构的产品与机构资料，不是实际合作或价格承诺。
INSERT INTO crm_institution (id,tenant_id,institution_no,full_name,short_name,city_code,business_type_code,ownership_code,grade_code,status,settlement_type,online_rate,offline_rate,platform_rate,contact_phone_cipher,bank_name_cipher,bank_account_cipher,tax_id_cipher) VALUES
 (1,1,'INST-DEMO-BJ','鲸选北京示范机构（虚构）','北京示范','BJ','MEDICAL_BEAUTY','PRIVATE','A','ACTIVE','MONTHLY',0.180000,0.120000,0.150000,'saTIyXbj/FbSn2AC3COxbKN9BxjXzUvJCdNyDWF14PQt2NqVzFFycg==','KZanXYxJlmsMgzy2oGh7bljCfPcSOduAHecWX1jE/SJxFAUpUDSO1f96RUw/xXsA1DJ0Xd8oIudMww==','tg29d+etWWVBeePsnTKlJ1IOUL6KvHOPGL6+/U8Dkd8bB0SaQ7afcLxtd74=','uf0E923KtKuNaQeLWYvsz3Th9w3LIDb874YpSPKJ/mo9bMTvEBRho4yIy16/Bg=='),
 (2,1,'INST-DEMO-HZ','鲸选杭州示范机构（虚构）','杭州示范','HZ','MEDICAL_BEAUTY','PRIVATE','A','ACTIVE','PER_REDEMPTION',0.200000,0.150000,0.180000,NULL,NULL,NULL,NULL),
 (3,1,'INST-DEMO-GZ','鲸选广州示范机构（虚构）','广州示范','GZ','MEDICAL_BEAUTY','PRIVATE','B','ACTIVE','MONTHLY',0.190000,0.130000,0.160000,NULL,NULL,NULL,NULL)
 ON DUPLICATE KEY UPDATE full_name=VALUES(full_name),short_name=VALUES(short_name),status=VALUES(status),online_rate=VALUES(online_rate),offline_rate=VALUES(offline_rate),platform_rate=VALUES(platform_rate),contact_phone_cipher=VALUES(contact_phone_cipher),bank_name_cipher=VALUES(bank_name_cipher),bank_account_cipher=VALUES(bank_account_cipher),tax_id_cipher=VALUES(tax_id_cipher);

INSERT INTO crm_project (id,tenant_id,project_code,project_name,category_code,list_price_fen,status) VALUES
 (101,1,'PRJ-WATER','基础水光','水光',39900,'ACTIVE'),
 (102,1,'PRJ-PHOTON','黄金超光子全面部全模式','超光子',21900,'ACTIVE'),
 (103,1,'PRJ-REPAIR','舒敏修护','修护',26000,'ACTIVE')
 ON DUPLICATE KEY UPDATE project_name=VALUES(project_name),category_code=VALUES(category_code),list_price_fen=VALUES(list_price_fen),status=VALUES(status);
INSERT INTO crm_package (id,tenant_id,package_code,package_name,status) VALUES
 (201,1,'PKG-WG','光子水光囤货卡','ACTIVE'),
 (202,1,'PKG-SY','素颜卡','ACTIVE')
 ON DUPLICATE KEY UPDATE package_name=VALUES(package_name),status=VALUES(status);
INSERT INTO crm_package_version (id,tenant_id,package_id,version_no,selection_mode,choice_count,selection_rules,status) VALUES
 (301,1,201,1,'USE_N',3,NULL,'ACTIVE'),
 (302,1,201,2,'USE_N',6,NULL,'ACTIVE'),
 (303,1,201,3,'USE_N',10,NULL,'DRAFT'),
 (311,1,202,1,'CHOOSE_N',3,NULL,'DRAFT')
 ON DUPLICATE KEY UPDATE selection_mode=VALUES(selection_mode),choice_count=VALUES(choice_count),status=VALUES(status);
INSERT INTO crm_package_project (id,tenant_id,package_version_id,project_id,grant_count,selection_weight,allocation_price_fen,option_group) VALUES
 (301,1,301,101,3,1,0,'MAIN'),
 (302,1,302,101,6,1,0,'MAIN'),
 (303,1,303,101,10,1,0,'MAIN'),
 (311,1,311,101,1,1,0,'MAIN'),
 (312,1,311,102,1,1,0,'MAIN'),
 (313,1,311,103,1,1,0,'MAIN')
 ON DUPLICATE KEY UPDATE grant_count=VALUES(grant_count),selection_weight=VALUES(selection_weight);
INSERT INTO crm_sku (id,tenant_id,sku_code,package_version_id,spec_name,sale_price_fen,status,on_sale_at,off_sale_at) VALUES
 (301,1,'SKU-WG-001-03',301,'3次',181100,'ACTIVE',NOW(3),NULL),
 (302,1,'SKU-WG-001-06',302,'6次',331100,'ACTIVE',NOW(3),NULL),
 (303,1,'SKU-WG-001-10',303,'10次',499900,'INACTIVE',NULL,NOW(3)),
 (311,1,'SKU-MS-001-S3',311,'选3项',19900,'INACTIVE',NULL,NOW(3))
 ON DUPLICATE KEY UPDATE spec_name=VALUES(spec_name),sale_price_fen=VALUES(sale_price_fen);
INSERT INTO crm_sku_institution (tenant_id,sku_id,institution_id,status) VALUES
 (1,301,1,'ACTIVE'),(1,301,2,'ACTIVE'),(1,302,2,'ACTIVE'),(1,303,2,'ACTIVE'),
 (1,311,1,'ACTIVE'),(1,311,2,'ACTIVE')
 ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO crm_campaign (id,tenant_id,campaign_code,campaign_name,campaign_type,status) VALUES
 (1,1,'DAILY','日常销售','REGULAR','ACTIVE') ON DUPLICATE KEY UPDATE campaign_name=VALUES(campaign_name),status=VALUES(status);
INSERT INTO crm_campaign_batch (id,tenant_id,campaign_id,batch_code,batch_name) VALUES
 (1,1,1,'DEFAULT','默认批次') ON DUPLICATE KEY UPDATE batch_name=VALUES(batch_name);
INSERT INTO crm_partner_channel (id,tenant_id,channel_no,name,platform_code,business_owner_user_id,status,remarks) VALUES
 (1,1,'CH-DEMO-001','虚构内容渠道','WEIBO',6,'ACTIVE','仅本地演示，无真实归因数据')
 ON DUPLICATE KEY UPDATE name=VALUES(name),status=VALUES(status);
