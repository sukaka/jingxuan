/**
 * 客户、身份、转交、积分、跟进（P0-1 起实现）。
 *
 * 分层固定为 api / application / domain / infrastructure(+mapper) / security，
 * 跨模块查询只允许通过 Application Service 或只读 QueryService。
 */
package com.whale.crm.customer;
