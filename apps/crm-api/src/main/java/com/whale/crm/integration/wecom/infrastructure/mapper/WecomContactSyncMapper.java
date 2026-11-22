package com.whale.crm.integration.wecom.infrastructure.mapper;

import com.whale.crm.integration.wecom.domain.WecomContactProfile;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRun;
import com.whale.crm.integration.wecom.domain.WecomStaffAccount;
import com.whale.crm.integration.wecom.domain.WecomStaffIdentity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface WecomContactSyncMapper {
    @Select("SELECT id,corp_id AS corpId,wecom_userid AS wecomUserid,staff_user_id AS staffUserId,enabled FROM crm_wecom_staff_account WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND enabled=1 ORDER BY id")
    List<WecomStaffAccount> selectEnabledStaff(@Param("tenantId") long tenantId, @Param("corpId") String corpId);

    @Select("SELECT id,corp_id AS corpId,wecom_userid AS wecomUserid,staff_user_id AS staffUserId,enabled FROM crm_wecom_staff_account WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND wecom_userid=#{wecomUserid}")
    WecomStaffAccount selectStaff(@Param("tenantId") long tenantId, @Param("corpId") String corpId, @Param("wecomUserid") String wecomUserid);

    @Select("""
            SELECT sa.tenant_id AS tenantId, sa.staff_user_id AS staffUserId
            FROM crm_wecom_staff_account sa
            JOIN sys_user u ON u.tenant_id = sa.tenant_id AND u.id = sa.staff_user_id
            WHERE sa.tenant_id = #{tenantId} AND sa.corp_id = #{corpId} AND sa.wecom_userid = #{wecomUserid}
              AND sa.enabled = 1 AND u.user_type = 'EMPLOYEE' AND u.status = 'ACTIVE'
            ORDER BY sa.id
            """)
    List<WecomStaffIdentity> selectActiveStaffForAuth(@Param("tenantId") long tenantId,
                                                      @Param("corpId") String corpId,
                                                      @Param("wecomUserid") String wecomUserid);

    @Select("SELECT id,corp_id AS corpId,wecom_userid AS wecomUserid,staff_user_id AS staffUserId,enabled FROM crm_wecom_staff_account WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND staff_user_id=#{staffUserId} ORDER BY id")
    List<WecomStaffAccount> selectStaffByUserId(@Param("tenantId") long tenantId, @Param("corpId") String corpId, @Param("staffUserId") long staffUserId);

    @Update("UPDATE crm_wecom_contact_sync_run SET status='ERROR',error_masked=#{error},finished_at=#{now} "
            + "WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND status='RUNNING' "
            + "AND ((lease_until IS NOT NULL AND lease_until<#{now}) "
            + "OR (lease_until IS NULL AND started_at<#{legacyCutoff}))")
    int expireStaleRuns(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                        @Param("now") Instant now, @Param("legacyCutoff") Instant legacyCutoff,
                        @Param("error") String error);

    @Insert("INSERT INTO crm_wecom_contact_sync_run (tenant_id,corp_id,run_type,status,started_at,lease_until,created_by) "
            + "VALUES (#{tenantId},#{corpId},#{runType},'RUNNING',#{now},#{leaseUntil},#{actor})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertRun(RunInsertParam param);

    @Update("UPDATE crm_wecom_contact_sync_run SET status=#{status},cursor_value=#{cursor},received_count=#{received},upserted_count=#{upserted},deactivated_count=#{deactivated},error_masked=#{error},finished_at=#{finished} WHERE tenant_id=#{tenantId} AND id=#{id} AND status='RUNNING'")
    int finishRun(@Param("tenantId") long tenantId, @Param("id") long id, @Param("status") String status,
                  @Param("cursor") String cursor, @Param("received") int received, @Param("upserted") int upserted,
                  @Param("deactivated") int deactivated, @Param("error") String error, @Param("finished") Instant finished);

    @Select("SELECT id,corp_id AS corpId,run_type AS runType,status,cursor_value AS cursorValue,received_count AS receivedCount,upserted_count AS upsertedCount,deactivated_count AS deactivatedCount,error_masked AS errorMasked,started_at AS startedAt,finished_at AS finishedAt FROM crm_wecom_contact_sync_run WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} ORDER BY id DESC LIMIT 50")
    List<WecomContactSyncRun> selectRuns(@Param("tenantId") long tenantId, @Param("corpId") String corpId);

    @Insert("""
            INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,name,avatar,contact_type,gender,unionid,
              remark,description,add_way,channel_state,follow_created_at,tag_snapshot_json,follow_status,last_sync_run_id,
              consent_status,last_synced_at)
            VALUES (#{tenantId},#{p.corpId},#{p.externalUserid},#{staffUserId},#{p.name},#{p.avatar},#{p.contactType},#{p.gender},#{p.unionid},
              #{p.remark},#{p.description},#{p.addWay},#{p.channelState},#{p.followCreatedAt},#{p.tagSnapshotJson},'ACTIVE',#{runId},'PENDING',#{now})
            ON DUPLICATE KEY UPDATE name=VALUES(name),avatar=VALUES(avatar),contact_type=VALUES(contact_type),gender=VALUES(gender),
              unionid=VALUES(unionid),remark=VALUES(remark),description=VALUES(description),add_way=VALUES(add_way),
              channel_state=VALUES(channel_state),follow_created_at=VALUES(follow_created_at),tag_snapshot_json=VALUES(tag_snapshot_json),
              follow_status='ACTIVE',last_sync_run_id=VALUES(last_sync_run_id),last_synced_at=VALUES(last_synced_at),version=version+1
            """)
    int upsertContact(@Param("tenantId") long tenantId, @Param("staffUserId") long staffUserId,
                      @Param("runId") long runId, @Param("now") Instant now, @Param("p") WecomContactProfile profile);

    @Update("UPDATE crm_wecom_contact SET follow_status='DELETED_BY_STAFF',version=version+1 WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND follow_status='ACTIVE' AND (last_sync_run_id IS NULL OR last_sync_run_id<>#{runId})")
    int deactivateMissing(@Param("tenantId") long tenantId, @Param("corpId") String corpId, @Param("runId") long runId);

    @Update("UPDATE crm_wecom_contact SET follow_status=#{status},version=version+1 WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND external_userid=#{externalUserid} AND staff_user_id=#{staffUserId}")
    int updateFollowStatus(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                           @Param("externalUserid") String externalUserid, @Param("staffUserId") long staffUserId,
                           @Param("status") String status);

    final class RunInsertParam {
        private Long id;
        private long tenantId;
        private String corpId;
        private String runType;
        private Long actor;
        private Instant now;
        private Instant leaseUntil;

        public RunInsertParam() {
        }

        public RunInsertParam(long tenantId, String corpId, String runType, Long actor,
                              Instant now, Instant leaseUntil) {
            this.tenantId = tenantId;
            this.corpId = corpId;
            this.runType = runType;
            this.actor = actor;
            this.now = now;
            this.leaseUntil = leaseUntil;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getTenantId() {
            return tenantId;
        }

        public void setTenantId(long tenantId) {
            this.tenantId = tenantId;
        }

        public String getCorpId() {
            return corpId;
        }

        public void setCorpId(String corpId) {
            this.corpId = corpId;
        }

        public String getRunType() {
            return runType;
        }

        public void setRunType(String runType) {
            this.runType = runType;
        }

        public Long getActor() {
            return actor;
        }

        public void setActor(Long actor) {
            this.actor = actor;
        }

        public Instant getNow() {
            return now;
        }

        public void setNow(Instant now) {
            this.now = now;
        }

        public Instant getLeaseUntil() {
            return leaseUntil;
        }

        public void setLeaseUntil(Instant leaseUntil) {
            this.leaseUntil = leaseUntil;
        }
    }
}
