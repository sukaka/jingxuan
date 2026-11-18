# 07. 数据库设计

## 1. 新增核心表

`crm_wecom_app`、`crm_wecom_employee`、`crm_wecom_customer_relation`、`crm_quick_reply_category`、`crm_quick_reply_template`、`crm_quick_reply_variable`、`crm_quick_reply_usage`、`crm_customer_event`、`crm_ai_reply_suggestion`、`crm_wecom_message`、`crm_wecom_message_media`、`crm_wecom_archive_cursor`、`crm_operation_audit`。

## 2. crm_wecom_customer_relation

字段建议：id、tenant_id、customer_id、corp_id、external_userid、unionid、follow_userid、wecom_name、avatar、add_way、state、relation_status、created_at、updated_at。  
索引：customer_id、external_userid、follow_userid。唯一关系按业务模型确定，至少避免同租户/企业/客户关系重复。

## 3. crm_quick_reply_category

字段：id、tenant_id、parent_id、name、code、scope_type、scope_id、sort_no、status、created_by、created_at、updated_at。

## 4. crm_quick_reply_template

字段：id、tenant_id、category_id、name、code、message_type、content、scene、scope_type、scope_id、allow_edit、require_preview、ai_enabled、status、sort_no、version、created_by、created_at、updated_at。

## 5. crm_quick_reply_variable

字段：id、variable_code、variable_name、data_source、resolver_type、resolver_key、data_type、required、description、status。

## 6. crm_quick_reply_usage

字段：id、tenant_id、template_id、employee_id、customer_id、external_userid、scene、original_content、final_content、edited、send_status、send_time、created_at。

## 7. crm_ai_reply_suggestion

字段：id、tenant_id、customer_id、employee_id、scene、input_snapshot(JSON)、suggestion、confidence、model、prompt_version、accepted、edited、final_content、created_at。

## 8. crm_wecom_message

字段：id、tenant_id、message_id(唯一)、seq_no、employee_userid、external_userid、room_id、message_type、content_json、send_time、created_at。

## 9. 原则

复用现有 customer/order/writeoff 等主表；企业微信表只做映射、同步、消息和辅助数据。JSON 仅用于快照/不固定结构，高频查询字段单列并建立索引；所有 schema 变更提供 migration。


## 10. V51 群聊表与消息字段

新增 `crm_wecom_room`：保存 tenant/corp/roomid、管理员显示名称、ACTIVE/DISABLED、清单来源证据引用、确认时间、操作人、UTC 时间戳和 version。`tenant_id + corp_id + room_id` 唯一。

新增 `crm_wecom_room_participant`：保存企微成员账号或 external_userid、STAFF/EXTERNAL、可空 staff_user_id/external_userid、ACTIVE/REMOVED、确认/退出时间及 version。检查约束要求员工与外部身份二选一；唯一键防止同群重复身份，员工与外部身份索引用于权限与同意查询，外键禁止删除仍有成员历史的群。

`crm_wecom_message` 新增 `conversation_type`、实际发送人 `sender_wecom_userid/sender_staff_user_id` 和 `room_roster_version`，`conversation_id` 扩至 192；群聊的单聊归属字段与 customer_id 必须为空。`crm_wecom_recall_event` 同步保存 conversation_type、conversation_id 和实际撤回人。V51 提供独立回滚脚本；含群消息或群撤回事实时不允许通过结构回滚强行塞回旧的非空单聊模型。
