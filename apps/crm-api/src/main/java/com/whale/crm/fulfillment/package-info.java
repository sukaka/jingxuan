/**
 * 预约、履约、权益、核销（P0-3/P0-4 实现）。
 *
 * 分层固定为 api / application / domain / infrastructure(+mapper) / security，
 * 跨模块查询只允许通过 Application Service 或只读 QueryService。
 */
package com.whale.crm.fulfillment;
