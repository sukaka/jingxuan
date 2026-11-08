/**
 * 业绩、素材、活动、风险、报表（P2 实现）。
 *
 * 分层固定为 api / application / domain / infrastructure(+mapper) / security，
 * 跨模块查询只允许通过 Application Service 或只读 QueryService。
 */
package com.whale.crm.operation;
