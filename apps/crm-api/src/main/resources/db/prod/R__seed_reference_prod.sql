-- 核心业务所需的系统参考数据，不包含城市、客户、机构、订单等演示数据。
-- 重复执行只补齐缺失代码，不覆盖运营已维护的显示名、排序或启停状态。
INSERT INTO sys_dictionary_type (tenant_id,dict_code,dict_name,status) VALUES
  (1,'orderTypes','订单类型','ACTIVE'),
  (1,'institutionBusinessTypes','机构业务类型','ACTIVE'),
  (1,'institutionOwnerships','机构性质','ACTIVE'),
  (1,'merchantGrades','商户等级','ACTIVE'),
  (1,'settlementTypes','机构结算方式','ACTIVE')
  ON DUPLICATE KEY UPDATE dict_code=VALUES(dict_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'REGULAR','普通订单',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='orderTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'FLASH','快闪订单',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='orderTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'PROMOTION','大促活动',3,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='orderTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'MED_AESTHETIC','医美',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionBusinessTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'DENTAL','口腔',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionBusinessTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'INTIMATE','私密',3,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionBusinessTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'COMPREHENSIVE','综合',4,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionBusinessTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'PUBLIC','公立机构',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionOwnerships'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'PRIVATE','民营机构',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionOwnerships'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'S','S类商户',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='merchantGrades'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'A','A类商户',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='merchantGrades'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'B','B类商户',3,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='merchantGrades'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'C','C类商户',4,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='merchantGrades'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'MONTHLY','月结',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='settlementTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'PER_REDEMPTION','次卡次结',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='settlementTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
