package com.whale.crm.integration.wecom.infrastructure.mapper;

import com.whale.crm.integration.wecom.domain.WecomCallbackEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface WecomCallbackMapper {
    @Insert("INSERT IGNORE INTO crm_wecom_callback_event (tenant_id,corp_id,event_fingerprint,event_type,change_type,wecom_userid,external_userid,event_time,status,received_at) VALUES (#{tenantId},#{e.corpId},#{e.eventFingerprint},#{e.eventType},#{e.changeType},#{e.wecomUserid},#{e.externalUserid},#{e.eventTime},'RECEIVED',#{now})")
    int insert(@Param("tenantId") long tenantId, @Param("now") Instant now, @Param("e") WecomCallbackEvent event);

    @Select("SELECT id,corp_id AS corpId,event_fingerprint AS eventFingerprint,event_type AS eventType,change_type AS changeType,wecom_userid AS wecomUserid,external_userid AS externalUserid,event_time AS eventTime,status,attempts FROM crm_wecom_callback_event WHERE tenant_id=#{tenantId} AND status IN ('RECEIVED','RETRYABLE') AND (next_retry_at IS NULL OR next_retry_at<=#{now}) ORDER BY id LIMIT #{limit}")
    List<WecomCallbackEvent> selectPending(@Param("tenantId") long tenantId, @Param("now") Instant now, @Param("limit") int limit);

    @Update("UPDATE crm_wecom_callback_event SET status='PROCESSING',attempts=attempts+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND status IN ('RECEIVED','RETRYABLE') AND (next_retry_at IS NULL OR next_retry_at<=#{now}) AND attempts=#{expectedAttempts}")
    int claim(@Param("tenantId") long tenantId, @Param("id") long id,
              @Param("expectedAttempts") int expectedAttempts, @Param("now") Instant now);

    @Update("UPDATE crm_wecom_callback_event SET status=#{status},next_retry_at=#{nextRetry},error_masked=#{error},processed_at=#{processed} WHERE tenant_id=#{tenantId} AND id=#{id}")
    int finish(@Param("tenantId") long tenantId, @Param("id") long id, @Param("status") String status,
               @Param("nextRetry") Instant nextRetry, @Param("error") String error, @Param("processed") Instant processed);
}
