# 鲸选美学 CRM V2.0 · 前端(crm-web)

P0-1 阶段:Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router 4 + axios,已接入认证与权限。

## 开发

```bash
npm install
npm run dev        # http://localhost:5173,/api 与 /actuator 代理到 http://localhost:8080
```

## 质量命令

```bash
npm run typecheck  # vue-tsc --noEmit
npm run lint       # eslint . --max-warnings 0(ESLint 9 flat config)
npm run test       # vitest run(jsdom)
npm run build      # vue-tsc -b && vite build
```

## 约定

- 后端 API 前缀 `/api/v1`,统一响应结构 `ApiResponse`,错误规范化为 `ApiError {status, code, message, requestId, details}`(见 `src/types/api.ts`、`src/api/http.ts`)。
- 认证(P0-1):登录后后端 `Set-Cookie` 下发 HttpOnly JWT(`CRM_TOKEN`),前端不接触 token 明文;写方法(POST/PUT/PATCH/DELETE,除 `/auth/login`)自动从可读的 `CRM_CSRF` Cookie 注入 `X-CSRF-Token` 头,缺失时后端返回 403 `CSRF_TOKEN_INVALID`。
- 会话:刷新页面后路由守卫调用 `GET /auth/me` 恢复登录主体;权限/角色只认 `/auth/me` 实时返回的 code 列表(`src/stores/auth.ts`)。
- 授权:菜单(`src/router/menu.ts`)与写操作按钮按权限过滤/渲染,但菜单隐藏不等于授权,后端 `@PreAuthorize` 强制校验;路由守卫对无权限页面跳转 `/403`。
- 页面:工作台、用户管理、角色权限、字典管理、审计日志(`src/views/`),列表统一 `items/total/page/size` 分页,编辑携带 `version` 乐观锁(409 提示刷新)。
