-- 回滚 V58__backfill_user_default_data_scopes.sql
-- V58 仅对缺失 sys_data_scope 的存量内部员工幂等补齐默认数据范围，未变更表结构或索引。
-- 为避免误删既有管理员或手工授予的数据范围，回滚脚本不执行批量 DELETE；
-- 如需恢复至升级前精确数据范围快照，请从升级前 mysqldump 备份按 user_id 定点恢复 sys_data_scope。
SELECT 1;
