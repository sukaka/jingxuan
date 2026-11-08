-- P1-1 仅 local：有赞 Mock 映射夹具，全部虚构，不对应真实店铺、商品或个人。
INSERT INTO crm_customer (id,tenant_id,owner_staff_id,team_id,customer_no,display_name,nickname,city_code,source_code,channel_code,customer_status)
VALUES (1010,1,2,1,'C0000001010','有赞Mock客户','YZ-MOCK-CLIENT','BJ','EXTERNAL_CONTENT','YOUZAN_MOCK','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),nickname=VALUES(nickname),owner_staff_id=VALUES(owner_staff_id),team_id=VALUES(team_id),customer_status=VALUES(customer_status);

INSERT INTO crm_youzan_sku_map (tenant_id,shop_id,external_sku_id,crm_sku_id,status) VALUES
 (1,'MOCK-SHOP-001','YZ-SKU-301',301,'ACTIVE'),
 (1,'MOCK-SHOP-001','YZ-SKU-302',302,'ACTIVE')
  ON DUPLICATE KEY UPDATE crm_sku_id=VALUES(crm_sku_id),status=VALUES(status);

INSERT INTO crm_youzan_customer_link (tenant_id,shop_id,external_customer_id,customer_id,link_type,confidence,reason,created_by)
VALUES (1,'MOCK-SHOP-001','YZ-DEMO-CUSTOMER-1010',1010,'STRONG','HIGH','DEV ONLY：虚构人工确认映射',1)
  ON DUPLICATE KEY UPDATE customer_id=VALUES(customer_id),link_type=VALUES(link_type),confidence=VALUES(confidence),reason=VALUES(reason);

INSERT INTO crm_youzan_sync_cursor (tenant_id,shop_id,cursor_value,last_sync_at,last_external_updated_at)
VALUES (1,'MOCK-SHOP-001','0',NULL,NULL)
  ON DUPLICATE KEY UPDATE cursor_value=VALUES(cursor_value);
