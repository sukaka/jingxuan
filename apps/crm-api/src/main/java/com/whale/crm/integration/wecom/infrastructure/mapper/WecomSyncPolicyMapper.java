package com.whale.crm.integration.wecom.infrastructure.mapper;

import com.whale.crm.integration.wecom.domain.WecomSyncPolicy;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface WecomSyncPolicyMapper {
    String COLUMNS = "id,tenant_id AS tenantId,corp_id AS corpId,enabled,contact_sync_enabled AS contactSyncEnabled,archive_sync_enabled AS archiveSyncEnabled,interval_seconds AS intervalSeconds,next_run_at AS nextRunAt,last_scheduled_at AS lastScheduledAt,last_status AS lastStatus,last_error AS lastError,version";

    @Insert("INSERT INTO crm_wecom_sync_policy (tenant_id,corp_id,enabled,contact_sync_enabled,archive_sync_enabled,interval_seconds,next_run_at,last_scheduled_at,last_status,last_error) "
            + "VALUES (#{p.tenantId},#{p.corpId},#{p.enabled},#{p.contactSyncEnabled},#{p.archiveSyncEnabled},#{p.intervalSeconds},#{p.nextRunAt},#{p.lastScheduledAt},#{p.lastStatus},#{p.lastError})")
    void insert(@Param("p") WecomSyncPolicy policy);

    @Select("SELECT " + COLUMNS + " FROM crm_wecom_sync_policy WHERE tenant_id=#{tenantId} AND corp_id=#{corpId}")
    WecomSyncPolicy select(@Param("tenantId") long tenantId, @Param("corpId") String corpId);

    @Select("SELECT " + COLUMNS + " FROM crm_wecom_sync_policy WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} FOR UPDATE")
    WecomSyncPolicy selectForUpdate(@Param("tenantId") long tenantId, @Param("corpId") String corpId);

    @Update("UPDATE crm_wecom_sync_policy SET enabled=#{p.enabled},contact_sync_enabled=#{p.contactSyncEnabled},archive_sync_enabled=#{p.archiveSyncEnabled},interval_seconds=#{p.intervalSeconds},next_run_at=#{p.nextRunAt},last_status=#{p.lastStatus},last_error=#{p.lastError},version=version+1 WHERE tenant_id=#{p.tenantId} AND id=#{p.id} AND version=#{p.version}")
    int update(@Param("p") WecomSyncPolicy policy);

    @Select("SELECT " + COLUMNS + " FROM crm_wecom_sync_policy WHERE enabled=1 AND next_run_at IS NOT NULL AND next_run_at<=#{now} ORDER BY next_run_at,id LIMIT #{limit}")
    List<WecomSyncPolicy> selectDue(@Param("now") Instant now, @Param("limit") int limit);

    @Update("UPDATE crm_wecom_sync_policy SET last_scheduled_at=#{scheduledAt},next_run_at=#{nextRunAt},version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version}")
    int claim(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
              @Param("scheduledAt") Instant scheduledAt, @Param("nextRunAt") Instant nextRunAt);

    @Update("UPDATE crm_wecom_sync_policy SET last_status=#{status},last_error=#{error},version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version}")
    int finish(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
               @Param("status") String status, @Param("error") String error);
}
