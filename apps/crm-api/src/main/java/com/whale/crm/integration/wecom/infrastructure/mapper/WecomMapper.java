package com.whale.crm.integration.wecom.infrastructure.mapper;

import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomCursor;
import com.whale.crm.integration.wecom.domain.WecomDataGap;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface WecomMapper {
    String CONTACT_COLUMNS = "id,corp_id AS corpId,external_userid AS externalUserid,staff_user_id AS staffUserId,name,avatar,contact_type AS contactType,gender,unionid,remark,description,add_way AS addWay,channel_state AS channelState,follow_created_at AS followCreatedAt,tag_snapshot_json AS tagSnapshotJson,follow_status AS followStatus,customer_id AS customerId,consent_status AS consentStatus,consent_evidence_ref AS consentEvidenceRef,link_type AS linkType,link_reason AS linkReason,created_by AS createdBy,last_synced_at AS lastSyncedAt,version";
    String MESSAGE_COLUMNS = "m.id,m.corp_id AS corpId,m.msg_id AS msgId,m.archive_seq AS archiveSeq,m.customer_id AS customerId,m.external_userid AS externalUserid,m.staff_user_id AS staffUserId,m.conversation_id AS conversationId,m.message_type AS messageType,m.sender_type AS senderType,m.sent_at AS sentAt,m.content_object_key AS contentObjectKey,m.content_sha256 AS contentSha256,m.consent_snapshot AS consentSnapshot,m.consent_evidence_ref AS consentEvidenceRef,m.status,c.consent_status AS currentConsentStatus,m.version";
    String MESSAGE_JOINS = " FROM crm_wecom_message m JOIN crm_wecom_contact c ON c.tenant_id=m.tenant_id AND c.corp_id=m.corp_id AND c.external_userid=m.external_userid AND c.staff_user_id=m.staff_user_id ";
    String CURRENTLY_READABLE = " m.consent_snapshot='GRANTED' AND c.consent_status='GRANTED' AND EXISTS (SELECT 1 FROM crm_wecom_archive_member am WHERE am.tenant_id=m.tenant_id AND am.corp_id=m.corp_id AND am.staff_user_id=m.staff_user_id AND am.enabled=1) ";
    String CUSTOMER_STAFF_SCOPE = """
            <choose>
              <when test="scopeType == 'ALL'">1=1</when>
              <when test="scopeType == 'TEAM' or scopeType == 'SELF'">
                m.customer_id IS NOT NULL
                AND EXISTS (SELECT 1 FROM crm_customer cu WHERE cu.tenant_id=m.tenant_id AND cu.id=m.customer_id AND cu.deleted=0
                  <choose>
                    <when test="scopeType == 'TEAM'">AND (cu.owner_staff_id=#{selfId} OR cu.team_id IN <foreach collection="teamIds" item="teamId" open="(" separator="," close=")">#{teamId}</foreach>)</when>
                    <otherwise>AND cu.owner_staff_id=#{selfId}</otherwise>
                  </choose>)
                AND EXISTS (SELECT 1 FROM sys_user su WHERE su.tenant_id=m.tenant_id AND su.id=m.staff_user_id
                  <choose>
                    <when test="scopeType == 'TEAM'">AND (su.id=#{selfId} OR su.team_id IN <foreach collection="teamIds" item="teamId" open="(" separator="," close=")">#{teamId}</foreach>)</when>
                    <otherwise>AND su.id=#{selfId}</otherwise>
                  </choose>)
              </when>
              <otherwise>1=0</otherwise>
            </choose>
            """;

    @Select("SELECT " + CONTACT_COLUMNS + " FROM crm_wecom_contact WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND external_userid=#{externalUserid} AND staff_user_id=#{staffUserId}")
    WecomContact selectContact(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                               @Param("externalUserid") String externalUserid, @Param("staffUserId") long staffUserId);

    @Select("SELECT " + CONTACT_COLUMNS + " FROM crm_wecom_contact WHERE tenant_id=#{tenantId} AND id=#{id}")
    WecomContact selectContactById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Insert("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,consent_status,consent_evidence_ref,link_type,link_reason,created_by,last_synced_at) VALUES (#{tenantId},#{e.corpId},#{e.externalUserid},#{e.staffUserId},#{e.customerId},#{e.consentStatus},#{e.consentEvidenceRef},#{e.linkType},#{e.linkReason},#{e.createdBy},#{e.lastSyncedAt})")
    void insertContact(@Param("tenantId") long tenantId, @Param("e") WecomContact contact);

    @Update("UPDATE crm_wecom_contact SET consent_status=#{status},consent_evidence_ref=#{evidenceRef},last_synced_at=#{syncedAt},version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version}")
    int updateContactConsent(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                             @Param("status") String status, @Param("evidenceRef") String evidenceRef,
                             @Param("syncedAt") Instant syncedAt);

    @Update("UPDATE crm_wecom_contact SET customer_id=#{customerId},link_type='MANUAL',link_reason=#{reason},created_by=#{actorUserId},version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version} AND customer_id IS NULL")
    int linkContact(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                    @Param("customerId") long customerId, @Param("reason") String reason, @Param("actorUserId") long actorUserId);

    @Select("<script>SELECT " + CONTACT_COLUMNS + " FROM crm_wecom_contact c WHERE c.tenant_id=#{tenantId} AND " +
            "<choose><when test=\"scopeType == 'ALL'\">1=1</when>" +
            "<when test=\"scopeType == 'TEAM' or scopeType == 'SELF'\">c.customer_id IS NOT NULL AND EXISTS (SELECT 1 FROM crm_customer cu WHERE cu.tenant_id=c.tenant_id AND cu.id=c.customer_id AND cu.deleted=0 AND " +
            "<choose><when test=\"scopeType == 'TEAM'\">(cu.owner_staff_id=#{selfId} OR cu.team_id IN <foreach collection=\"teamIds\" item=\"teamId\" open=\"(\" separator=\",\" close=\")\">#{teamId}</foreach>)</when><otherwise>cu.owner_staff_id=#{selfId}</otherwise></choose>) " +
            "<choose><when test=\"scopeType == 'TEAM'\">AND (c.staff_user_id=#{selfId} OR EXISTS (SELECT 1 FROM sys_user su WHERE su.tenant_id=c.tenant_id AND su.id=c.staff_user_id AND su.team_id IN <foreach collection=\"teamIds\" item=\"teamId\" open=\"(\" separator=\",\" close=\")\">#{teamId}</foreach>))</when><otherwise>AND c.staff_user_id=#{selfId}</otherwise></choose>" +
            "</when><otherwise>1=0</otherwise></choose> ORDER BY c.customer_id IS NULL DESC,c.id DESC LIMIT 200</script>")
    List<WecomContact> selectContacts(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                                      @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId);

    @Select("<script>SELECT COUNT(*) FROM sys_user su WHERE su.tenant_id=#{tenantId} AND su.id=#{staffUserId} AND su.status='ACTIVE' AND " +
            "<choose><when test=\"scopeType == 'ALL'\">1=1</when><when test=\"scopeType == 'SELF'\">su.id=#{selfId}</when>" +
            "<when test=\"scopeType == 'TEAM'\">(su.id=#{selfId} OR su.team_id IN <foreach collection=\"teamIds\" item=\"teamId\" open=\"(\" separator=\",\" close=\")\">#{teamId}</foreach>)</when><otherwise>1=0</otherwise></choose></script>")
    int countVisibleStaff(@Param("tenantId") long tenantId, @Param("staffUserId") long staffUserId,
                          @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds,
                          @Param("selfId") long selfId);

    @Select("SELECT COUNT(*) FROM crm_wecom_archive_member WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND staff_user_id=#{staffUserId} AND enabled=1")
    int countEnabledMember(@Param("tenantId") long tenantId, @Param("corpId") String corpId, @Param("staffUserId") long staffUserId);

    @Select("SELECT " + MESSAGE_COLUMNS + MESSAGE_JOINS + "WHERE m.tenant_id=#{tenantId} AND m.corp_id=#{corpId} AND m.msg_id=#{msgId}")
    WecomMessage selectMessage(@Param("tenantId") long tenantId, @Param("corpId") String corpId, @Param("msgId") String msgId);

    @Select("SELECT " + MESSAGE_COLUMNS + MESSAGE_JOINS + "WHERE m.tenant_id=#{tenantId} AND m.id=#{id}")
    WecomMessage selectMessageById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Select("<script>SELECT " + MESSAGE_COLUMNS + MESSAGE_JOINS + "WHERE m.tenant_id=#{tenantId} AND m.id=#{id} AND " + CURRENTLY_READABLE + " AND " + CUSTOMER_STAFF_SCOPE + "</script>")
    WecomMessage selectReadableMessage(@Param("tenantId") long tenantId, @Param("id") long id,
                                       @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds,
                                       @Param("selfId") long selfId);

    @Insert("INSERT INTO crm_wecom_message (tenant_id,corp_id,msg_id,archive_seq,customer_id,external_userid,staff_user_id,conversation_id,message_type,sender_type,sent_at,content_object_key,content_sha256,consent_snapshot,consent_evidence_ref,status) VALUES (#{tenantId},#{e.corpId},#{e.msgId},#{e.archiveSeq},#{e.customerId},#{e.externalUserid},#{e.staffUserId},#{e.conversationId},#{e.messageType},#{e.senderType},#{e.sentAt},#{e.contentObjectKey},#{e.contentSha256},#{e.consentSnapshot},#{e.consentEvidenceRef},#{e.status})")
    void insertMessage(@Param("tenantId") long tenantId, @Param("e") WecomMessage message);

    @Update("UPDATE crm_wecom_message SET customer_id=#{customerId},status='STORED',version=version+1 WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND external_userid=#{externalUserid} AND staff_user_id=#{staffUserId} AND customer_id IS NULL")
    int linkMessages(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                     @Param("externalUserid") String externalUserid, @Param("staffUserId") long staffUserId,
                     @Param("customerId") long customerId);

    @Select("<script>SELECT " + MESSAGE_COLUMNS + MESSAGE_JOINS + "WHERE m.tenant_id=#{tenantId} AND " + CURRENTLY_READABLE +
            "<if test=\"customerId != null\"> AND m.customer_id=#{customerId}</if> AND " + CUSTOMER_STAFF_SCOPE +
            " ORDER BY m.sent_at DESC,m.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<WecomMessage> selectMessages(@Param("tenantId") long tenantId, @Param("customerId") Long customerId,
                                      @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds,
                                      @Param("selfId") long selfId, @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*)" + MESSAGE_JOINS + "WHERE m.tenant_id=#{tenantId} AND " + CURRENTLY_READABLE +
            "<if test=\"customerId != null\"> AND m.customer_id=#{customerId}</if> AND " + CUSTOMER_STAFF_SCOPE + "</script>")
    long countScopedMessages(@Param("tenantId") long tenantId, @Param("customerId") Long customerId,
                             @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds,
                             @Param("selfId") long selfId);

    @Select("SELECT COUNT(*) FROM crm_wecom_message WHERE tenant_id=#{tenantId} AND corp_id=#{corpId}")
    long countAllMessages(@Param("tenantId") long tenantId, @Param("corpId") String corpId);

    @Select("SELECT id,corp_id AS corpId,archive_scope AS archiveScope,last_seq AS lastSeq,last_success_at AS lastSuccessAt,last_attempt_at AS lastAttemptAt,status,consecutive_failures AS consecutiveFailures,last_error AS lastError,data_gap AS dataGap,gap_reason AS gapReason,version FROM crm_wecom_cursor WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND archive_scope=#{archiveScope} FOR UPDATE")
    WecomCursor selectCursorForUpdate(@Param("tenantId") long tenantId, @Param("corpId") String corpId, @Param("archiveScope") String archiveScope);

    @Select("SELECT id,corp_id AS corpId,archive_scope AS archiveScope,last_seq AS lastSeq,last_success_at AS lastSuccessAt,last_attempt_at AS lastAttemptAt,status,consecutive_failures AS consecutiveFailures,last_error AS lastError,data_gap AS dataGap,gap_reason AS gapReason,version FROM crm_wecom_cursor WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND archive_scope=#{archiveScope}")
    WecomCursor selectCursor(@Param("tenantId") long tenantId, @Param("corpId") String corpId, @Param("archiveScope") String archiveScope);

    @Insert("INSERT INTO crm_wecom_cursor (tenant_id,corp_id,archive_scope,last_seq,last_success_at,last_attempt_at,status,consecutive_failures,last_error,data_gap,gap_reason) VALUES (#{tenantId},#{e.corpId},#{e.archiveScope},#{e.lastSeq},#{e.lastSuccessAt},#{e.lastAttemptAt},#{e.status},#{e.consecutiveFailures},#{e.lastError},#{e.dataGap},#{e.gapReason})")
    void insertCursor(@Param("tenantId") long tenantId, @Param("e") WecomCursor cursor);

    @Update("UPDATE crm_wecom_cursor SET last_seq=#{lastSeq},last_success_at=#{successAt},last_attempt_at=#{attemptedAt},status=#{status},consecutive_failures=#{failures},last_error=#{error},data_gap=#{dataGap},gap_reason=#{gapReason},version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version}")
    int updateCursor(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                     @Param("lastSeq") long lastSeq, @Param("successAt") Instant successAt, @Param("attemptedAt") Instant attemptedAt,
                     @Param("status") String status, @Param("failures") int failures, @Param("error") String error,
                     @Param("dataGap") boolean dataGap, @Param("gapReason") String gapReason);

    @Insert("INSERT INTO crm_wecom_data_gap (tenant_id,corp_id,archive_scope,gap_type,status,from_seq,to_seq,detail_masked,first_seen_at,last_seen_at) VALUES (#{tenantId},#{corpId},#{archiveScope},#{gapType},'OPEN',#{fromSeq},#{toSeq},#{detail},#{now},#{now}) ON DUPLICATE KEY UPDATE status='OPEN',to_seq=VALUES(to_seq),detail_masked=VALUES(detail_masked),last_seen_at=VALUES(last_seen_at),resolved_at=NULL,version=version+1")
    void insertGap(@Param("tenantId") long tenantId, @Param("corpId") String corpId, @Param("archiveScope") String archiveScope,
                   @Param("gapType") String gapType, @Param("fromSeq") Long fromSeq, @Param("toSeq") Long toSeq,
                   @Param("detail") String detail, @Param("now") Instant now);

    @Update("UPDATE crm_wecom_data_gap SET status='RESOLVED',resolved_at=#{resolvedAt},version=version+1 WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND archive_scope=#{archiveScope} AND gap_type='COLLECTOR_ERROR' AND status='OPEN'")
    int resolveCollectorGaps(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                             @Param("archiveScope") String archiveScope, @Param("resolvedAt") Instant resolvedAt);

    @Select("SELECT id,corp_id AS corpId,archive_scope AS archiveScope,gap_type AS gapType,status,from_seq AS fromSeq,to_seq AS toSeq,detail_masked AS detailMasked,first_seen_at AS firstSeenAt,last_seen_at AS lastSeenAt,resolved_at AS resolvedAt FROM crm_wecom_data_gap WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND archive_scope=#{archiveScope} AND status='OPEN' ORDER BY id")
    List<WecomDataGap> selectOpenGaps(@Param("tenantId") long tenantId, @Param("corpId") String corpId, @Param("archiveScope") String archiveScope);

    @Select("SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=#{tenantId} AND corp_id=#{corpId} AND archive_scope=#{archiveScope} AND status='RESOLVED'")
    long countResolvedGaps(@Param("tenantId") long tenantId, @Param("corpId") String corpId, @Param("archiveScope") String archiveScope);
}
