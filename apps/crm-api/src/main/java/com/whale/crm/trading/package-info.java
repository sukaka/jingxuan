/**
 * 订单、支付、退款（P0-3 起实现）。
 *
 * 分层固定为 api / application / domain / infrastructure(+mapper) / security，
 * 跨模块查询只允许通过 Application Service 或只读 QueryService。
 */
package com.whale.crm.trading;
