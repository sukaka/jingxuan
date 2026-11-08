/**
 * 子项目、套餐、规格、SKU、机构适用（P0-2 实现）。
 *
 * 分层固定为 api / application / domain / infrastructure(+mapper) / security，
 * 跨模块查询只允许通过 Application Service 或只读 QueryService。
 */
package com.whale.crm.product;
