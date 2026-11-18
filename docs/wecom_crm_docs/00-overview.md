# 00. 系统总体设计

## 1. 建设目标

系统不是重新实现企业微信聊天，而是在企业微信客户聊天场景中嵌入 CRM 能力。

```text
客户微信
   │
   ▼
企业微信员工
   │
   ├── 原生聊天
   │
   └── CRM侧边栏
        ├── 客户档案
        ├── 套餐/剩余次数
        ├── 订单/核销/退款
        ├── 预约/回访/待办
        ├── 快捷话术
        └── AI建议
```

## 2. 产品边界

企业微信负责：即时通讯、当前聊天上下文、客户联系关系、当前会话交互、会话内容存档能力。  
CRM 负责：客户主数据、机构、渠道、管家/商务、产品套餐、订单、核销、退款、回访、预约、待办、快捷话术、AI、审计和分析。

## 3. 总体架构

```text
                    企业微信
                       │
        ┌──────────────┼──────────────┐
        │              │              │
      OAuth         客户联系API      JS-SDK
        │              │              │
        └──────────────┼──────────────┘
                       ▼
                wecom-service
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
       CRM API       Redis        Kafka
          │                         │
          ▼                         ▼
        MySQL                    AI Service
          │                         │
          └──────────────┬──────────┘
                         ▼
                    CRM Sidebar
                     Vue3 / H5
```

P2 增加：

```text
企业微信会话内容存档
        │
        ▼
archive-service
        │
     官方SDK
        │
       解密
        │
        ▼
      Kafka
   ┌────┼────┐
   ▼    ▼    ▼
消息库  AI   事件引擎
```

## 4. 推荐技术栈

前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Axios、Element Plus、企业微信 JS-SDK。  
后端：Java 17+、Spring Boot 3.x、MyBatis Plus/JPA、Spring Security、MySQL 8、Redis、Kafka。  
AI：独立 `ai-service`，负责推荐回复、摘要、意图识别、事件识别、复购和回访建议；不得直接写核心业务表。

## 5. 关键对象

```text
企业微信员工 userid -> CRM employee_id
企业微信客户 external_userid -> CRM customer_id
企业微信群 chat_id -> CRM group relation
```

## 6. 阶段

P0：侧边栏与快捷话术。  
P1：AI 辅助、卡片、收藏、统计。  
P2：会话存档、Kafka、AI 事件。  
P3：运营分析。


## 7. 当前实现口径（V51）

原始技术建议不是对当前代码版本的约束。本仓库实际使用 Java 21、Spring Boot 4.0.x、MyBatis、MySQL 8.4、Vue 3 + TypeScript，AI Worker 复用现有 FastAPI；核心事务保持单库同步，异步使用 Outbox + Kafka + Inbox，Redis 先单实例，不拆成大量微服务。

群聊以 `roomid` 作为独立会话，不等同于任一 CRM 客户。客户群需显式确认完整参与人清单、有效员工映射/归档覆盖和外部参与人同意；群消息不生成单聊客户归属或自动回复。真实供应商能力、存档许可与生产启用以 Q10/Q23 验收为准。
