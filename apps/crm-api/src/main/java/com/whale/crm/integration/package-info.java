/**
 * 有赞和企微适配端口 port（P1-1/P1-2 实现）。
 *
 * 分层固定为 api / application / domain / infrastructure(+mapper) / security，
 * 跨模块查询只允许通过 Application Service 或只读 QueryService。
 */
package com.whale.crm.integration;
