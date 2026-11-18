# 02. 企业微信接入设计

> 开发时必须核对企业微信官方最新接口文档。本文定义架构和调用方向，不允许开发模型凭记忆臆造接口。

## 1. 自建应用

建议应用：`CRM助手`。服务端保存 CorpID、AgentID、Secret；Secret 严禁下发前端。

## 2. 可信域名

例如：`https://crm.example.com`。配置网页授权可信域名、JS-SDK 可信域名、校验文件、HTTPS。

## 3. OAuth 员工免登录

```text
侧边栏页面 -> 检查登录态 -> 企业微信OAuth -> 获取UserId -> 匹配crm_employee -> 建立CRM Session
```

推荐 HttpOnly Cookie + 服务端 Session/短期 JWT。禁止信任前端自行传入 `employee_id`。

## 4. JS-SDK 初始化

统一封装 `src/services/wecomSdk.ts`。服务端提供：

```http
POST /api/wecom/jssdk/signature
```

服务端负责 jsapi_ticket 缓存、nonce、timestamp、signature。前端只接收签名结果。

## 5. 当前上下文

页面加载后获取聊天工具栏上下文，区分单聊、群聊和普通入口。非聊天工具栏入口不得执行当前客户识别和当前会话发送。

## 6. 当前客户

单聊场景获取 `external_userid`。它仅是企业微信侧标识，不是 CRM 客户主键。

## 7. 客户映射

```text
external_userid
   ↓
已有映射？
 ┌─┴─┐
是   否
│    │
▼    ▼
直接  拉取企微客户资料
CRM   ↓
      匹配CRM客户
      ↓
      规则/人工确认
```

建议匹配优先级：已有 external_userid > unionid（合法可用时）> 已验证手机号 > 人工确认。禁止用昵称头像自动合并。

## 8. 客户详情同步

统一封装 `WeComClient.getExternalContact()`，同步昵称、头像、跟进员工、标签、添加方式、企业信息等官方可用字段。

## 9. 消息发送边界

正确：员工点击 -> 预览/编辑 -> 检查当前聊天上下文 -> 企业微信 JS-SDK -> 当前会话。  
禁止：后台定时任务直接冒充员工向普通客户自动发送消息。

## 10. WeComClient

统一处理 access_token、Redis 缓存、错误码、限流、重试、日志和 trace_id。业务 Service 不直接拼 HTTP 调企业微信。


## 11. V47 H5 分享卡片

使用官方 [分享消息到当前会话](https://developer.work.weixin.qq.com/document/path/100735) 的 `ww.sendChatMessage`，消息类型为 `news`：`link` 是 H5 页面 URL，`title` 是标题，`desc` 是摘要，`imgUrl` 是封面。仅 `single_chat_tools` 允许员工主动发送，发送前重新检查上下文；普通客户资料页只能复制链接。

卡片为不可变项目介绍快照，公开链接使用高随机 `shareKey`，公开页不返回客户、订单、剩余次数、租户、员工或企微身份。员工可停用公开链接，但不能覆盖已分享内容；复制和 SDK 成功/失败/未知分别写入只追加日志，不代表客户已读。

不使用个人微信 Hook、客户端注入或后台冒充员工群发。真实客户端卡片样式、权限和版本兼容性仍需在供应商环境验收。
