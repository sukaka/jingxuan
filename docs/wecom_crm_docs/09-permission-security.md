# 09. 权限、安全与审计

## 1. 权限点

`wecom:sidebar:view`、`customer:view`、`customer:mobile:view`、`package:view`、`writeoff:create`、`appointment:create`、`followup:create`、`todo:create`、`quickreply:view/manage`、`ai:reply`、`conversation:view/export`。

## 2. 数据权限

管家：自己负责客户。  
机构管理员：本机构。  
总部管理员：全机构。  
系统管理员：系统配置，不默认拥有全部业务数据查看权。

## 3. 后端强校验

所有 API 检查当前用户、角色、机构、客户归属和操作权限；前端隐藏按钮不是权限控制。

## 4. Secret

仅环境变量、Secret Manager 或配置中心加密存储。禁止 Git、前端、localStorage、浏览器响应、普通日志。

## 5. 敏感字段

手机号、身份证、地址、私密备注、医疗/消费敏感信息默认脱敏；明文查看需权限并审计。

## 6. 审计

记录 operator、customer、action、resource、old_value、new_value、ip、user_agent、time、trace_id。覆盖完整手机号查看、核销、退款、预约、发送话术、修改话术、AI 建议采纳、导出等。

## 7. AI 边界

AI 不可直接退款、核销、改订单、改客户归属、自动发普通客户消息。仅输出候选、建议、分类、摘要。


## 8. V51 群聊补充约束

群聊配置读取复用 `wecom:read`，写操作复用 `wecom:monitor`，不另造仅前端生效的权限。管理员保存完整群清单需 CSRF、幂等键、版本、理由和审计。后端按 `tenantId + corpId + roomId` 隔离配置，群消息不属于任一单独客户。

群消息的可见性需要当前可采群员工满足 ALL/TEAM/SELF 范围、消息同意快照有效、已登记外部参与人授权仍有效。退群和停用不擦除历史，但不再接受该退群发送人的新消息；历史外部参与人撤回同意后不可借退群绕过读取限制。正文、媒体与撤回均复用相同的消息授权检查，不在 Java 中先读全量再过滤。
