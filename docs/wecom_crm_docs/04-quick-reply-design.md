# 04. 快捷话术系统设计

## 1. 目标

话术分为：固定话术、动态话术、场景话术、AI 话术。统一经过预览 -> 员工编辑 -> 员工确认 -> 当前会话发送。

## 2. 固定话术

例如：`您好，请问有什么可以帮您？`

## 3. 动态话术

模板：

```text
{customer_name}您好，
您购买的{project_name}共{total_count}次，
目前已使用{used_count}次，
还剩{remaining_count}次。
```

## 4. 变量注册表

第一版：`customer_name`、`manager_name`、`project_name`、`total_count`、`used_count`、`remaining_count`、`last_visit_date`、`last_writeoff_date`、`appointment_date`、`appointment_time`、`institution_name`、`institution_address`、`doctor_name`、`expire_date`、`order_no`。

## 5. Resolver 机制

```java
public interface QuickReplyVariableResolver {
    String code();
    Object resolve(QuickReplyContext context);
}
```

实现 CustomerNameResolver、RemainingCountResolver、LastVisitDateResolver 等。禁止运营配置任意 SQL 或脚本。

## 6. 缺失变量

必填变量为空时，不得发送；返回 `missingVariables` 并提示具体缺失项。

## 7. 作用域

`GLOBAL`、`INSTITUTION`、`DEPARTMENT`、`PERSONAL`。排序：个人收藏 > 最近使用 > AI 推荐 > 公司推荐 > 分类排序。

## 8. 分类

初次沟通、预约、到院、核销、项目咨询、术前、术后、回访、退款、复购、投诉、活动。必须后台维护。

## 9. 发送前预览

支持 `[复制] [编辑] [确认发送]`。动态话术和 AI 话术必须预览。

## 10. 使用统计

记录 template_id、employee_id、customer_id、external_userid、scene、original_content、final_content、edited、send_status、send_time。

## 11. 第一版快捷操作

查询剩余次数、查询购买项目、发送最近核销、创建预约、发送预约、发送机构地址、发送项目介绍、术前须知、术后须知、回访、复购提醒、AI 推荐回复。

## 12. V44 当前实现

- 已建立 `crm_quick_reply_category/template/variable/usage` 四表及回滚；分类和模板支持新增、修改、停用、作用域、版本、幂等与审计。
- 当前消息类型为 TEXT。变量使用后端注册的 Resolver，不支持任意 SQL、脚本或前端传入可信业务数值。客户、管家、权益、订单、预约、机构与正式核销数据均从现有 Application Service 读取，并继承原权限。
- 预览接口为 `POST /api/v1/quick-replies/{id}/preview`；返回 `content、variables、missingVariables、canSend、allowEdit`。未知变量或必填事实为空时 `canSend=false`，不得绕过。
- 使用记录接口为 `POST /api/v1/quick-replies/{id}/usage`，查询为 `GET /api/v1/quick-replies/usages`。`COPIED` 只表示复制；`SDK_SUCCESS/FAILED/UNKNOWN` 只是当前会话 SDK 回调结果，不表示客户已读。
- 使用日志中的原始与最终内容均脱敏，历史禁止更新或删除。侧边栏“工具”页已接入分类选择、真实预览、员工编辑、复制与当前单聊发送；发送前重新校验入口，所有结果进入使用日志。真实企微客户端仍需供应商环境验收。

## 13. V45 收藏、排序与统计

- 支持个人收藏与取消收藏，使用独立幂等接口，使用日志不变。
- 列表顺序为个人收藏 > 当前员工最近使用 > AI 候选 > 公司推荐 > 分类排序；返回当前员工的 `favorite、lastUsedAt、usageCount`。
- 统计直接聚合只追加日志，支持日期区间和员工筛选；普通员工仅本人，管理权限可查看团队聚合。
- `successRate` 仅使用 SDK_SUCCESS/SDK_FAILED/SDK_UNKNOWN 作为分母，复制不计入；未知不当作成功，任何指标都不代表客户已读。

## 14. V46 后台管理与 AI 候选闭环

- 系统配置新增 `/settings/quick-replies` 页面，仅 `quickreply:manage` 可见。提供话术列表、搜索与分类筛选、新建、编辑、停用、分类/作用域管理、变量插入、真实客户预览和使用统计，不再要求管理员直接调用 HTTP 工具维护配置。
- 编辑采用列表与工作区并列布局；话术作用域继承所选分类，分类与话术作用域仍由后端复核。新增不传 version，编辑与停用携带当前 version 和必填理由，所有写操作带 CSRF 与幂等键。
- 管理列表展示启用配置；停用后不再进入员工可选列表，历史 AI 候选、使用日志和统计仍保留，不提供物理删除。真实客户预览只读取服务端已保存模板，不是发送操作。
- 侧边栏新增“AI 助手”页签。AI 候选必须经本人编辑/采纳或驳回，采纳前没有复制或发送入口；采纳后可手动复制或调用当前单聊 SDK，使用日志通过 `aiSuggestionId` 关联原候选。
- 员工措辞要求不是业务事实。Worker 使用服务端白名单事实并校验场景、事实引用、数值和安全字段，Mock 明示；模型失败时不生成伪成功记录。
