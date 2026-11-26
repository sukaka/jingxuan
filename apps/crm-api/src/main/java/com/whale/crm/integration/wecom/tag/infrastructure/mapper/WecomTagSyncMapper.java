package com.whale.crm.integration.wecom.tag.infrastructure.mapper;

import com.whale.crm.integration.wecom.tag.domain.WecomCustomerTagPushTask;
import com.whale.crm.integration.wecom.tag.domain.WecomLinkedContactForTagSync;
import com.whale.crm.integration.wecom.tag.domain.WecomTagMapping;
import com.whale.crm.integration.wecom.tag.domain.WecomTagSyncRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface WecomTagSyncMapper {

    List<WecomTagMapping> selectMappings(@Param("tenantId") long tenantId, @Param("corpId") String corpId);

    WecomTagMapping selectMappingByCrmTagCode(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                                              @Param("crmTagCode") String crmTagCode);

    int upsertMapping(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                      @Param("crmTagCode") String crmTagCode, @Param("wecomGroupId") String wecomGroupId,
                      @Param("wecomGroupName") String wecomGroupName, @Param("wecomTagId") String wecomTagId,
                      @Param("wecomTagName") String wecomTagName, @Param("syncStatus") String syncStatus,
                      @Param("lastSyncedAt") Instant lastSyncedAt, @Param("actorUserId") Long actorUserId);

    int expireStaleRuns(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                        @Param("syncScope") String syncScope, @Param("now") Instant now,
                        @Param("legacyCutoff") Instant legacyCutoff, @Param("error") String error);

    int insertRun(StartRunParam param);

    int finishRun(@Param("tenantId") long tenantId, @Param("runId") long runId, @Param("status") String status,
                  @Param("pulledCount") int pulledCount, @Param("pushedCount") int pushedCount,
                  @Param("skippedCount") int skippedCount, @Param("failedCount") int failedCount,
                  @Param("errorMasked") String errorMasked, @Param("finishedAt") Instant finishedAt);

    WecomTagSyncRun selectRunById(@Param("tenantId") long tenantId, @Param("runId") long runId);

    List<WecomTagSyncRun> selectRecentRuns(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                                           @Param("limit") int limit);

    int insertPushTask(PushTaskInsertParam param);

    WecomCustomerTagPushTask selectPushTaskById(@Param("tenantId") long tenantId, @Param("taskId") long taskId);

    List<WecomCustomerTagPushTask> selectPushTasks(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                                                   @Param("status") String status, @Param("limit") int limit);

    int recoverExpiredPushTasks(@Param("now") Instant now, @Param("errorMasked") String errorMasked);

    List<WecomCustomerTagPushTask> selectPendingPushTasks(@Param("now") Instant now, @Param("limit") int limit);

    int claimPushTask(@Param("tenantId") long tenantId, @Param("taskId") long taskId,
                      @Param("expectedAttempts") int expectedAttempts, @Param("now") Instant now,
                      @Param("leaseUntil") Instant leaseUntil);

    int finishPushTask(@Param("tenantId") long tenantId, @Param("taskId") long taskId,
                       @Param("expectedAttempts") int expectedAttempts, @Param("status") String status,
                       @Param("nextRetryAt") Instant nextRetryAt, @Param("errorMasked") String errorMasked);

    int resetPushTaskForRetry(@Param("tenantId") long tenantId, @Param("taskId") long taskId,
                              @Param("now") Instant now);

    List<WecomLinkedContactForTagSync> selectLinkedContactsByCustomerId(@Param("tenantId") long tenantId,
                                                                        @Param("corpId") String corpId,
                                                                        @Param("customerId") long customerId);

    List<WecomLinkedContactForTagSync> selectLinkedContactsByExternalUserid(@Param("tenantId") long tenantId,
                                                                            @Param("corpId") String corpId,
                                                                            @Param("externalUserid") String externalUserid);

    List<WecomLinkedContactForTagSync> selectAllLinkedContacts(@Param("tenantId") long tenantId,
                                                               @Param("corpId") String corpId);

    int updateContactTagSnapshot(@Param("tenantId") long tenantId, @Param("wecomContactId") long wecomContactId,
                                 @Param("tagSnapshotJson") String tagSnapshotJson);

    final class StartRunParam {
        private Long id;
        private long tenantId;
        private String corpId;
        private String syncScope;
        private String direction;
        private String triggerSource;
        private String adapterName;
        private Long actorUserId;
        private Instant startedAt;
        private Instant leaseUntil;

        public StartRunParam() {
        }

        public StartRunParam(long tenantId, String corpId, String syncScope, String direction, String triggerSource,
                             String adapterName, Long actorUserId, Instant startedAt, Instant leaseUntil) {
            this.tenantId = tenantId;
            this.corpId = corpId;
            this.syncScope = syncScope;
            this.direction = direction;
            this.triggerSource = triggerSource;
            this.adapterName = adapterName;
            this.actorUserId = actorUserId;
            this.startedAt = startedAt;
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

        public String getSyncScope() {
            return syncScope;
        }

        public void setSyncScope(String syncScope) {
            this.syncScope = syncScope;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String direction) {
            this.direction = direction;
        }

        public String getTriggerSource() {
            return triggerSource;
        }

        public void setTriggerSource(String triggerSource) {
            this.triggerSource = triggerSource;
        }

        public String getAdapterName() {
            return adapterName;
        }

        public void setAdapterName(String adapterName) {
            this.adapterName = adapterName;
        }

        public Long getActorUserId() {
            return actorUserId;
        }

        public void setActorUserId(Long actorUserId) {
            this.actorUserId = actorUserId;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(Instant startedAt) {
            this.startedAt = startedAt;
        }

        public Instant getLeaseUntil() {
            return leaseUntil;
        }

        public void setLeaseUntil(Instant leaseUntil) {
            this.leaseUntil = leaseUntil;
        }
    }

    final class PushTaskInsertParam {
        private Long id;
        private long tenantId;
        private String corpId;
        private long customerId;
        private long wecomContactId;
        private String wecomUserid;
        private String externalUserid;
        private String addTagIdsJson;
        private String removeTagIdsJson;
        private int maxAttempts;
        private Instant nextRetryAt;
        private String triggerSource;
        private Long actorUserId;

        public PushTaskInsertParam() {
        }

        public PushTaskInsertParam(long tenantId, String corpId, long customerId, long wecomContactId,
                                   String wecomUserid, String externalUserid, String addTagIdsJson,
                                   String removeTagIdsJson, int maxAttempts, Instant nextRetryAt,
                                   String triggerSource, Long actorUserId) {
            this.tenantId = tenantId;
            this.corpId = corpId;
            this.customerId = customerId;
            this.wecomContactId = wecomContactId;
            this.wecomUserid = wecomUserid;
            this.externalUserid = externalUserid;
            this.addTagIdsJson = addTagIdsJson;
            this.removeTagIdsJson = removeTagIdsJson;
            this.maxAttempts = maxAttempts;
            this.nextRetryAt = nextRetryAt;
            this.triggerSource = triggerSource;
            this.actorUserId = actorUserId;
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

        public long getCustomerId() {
            return customerId;
        }

        public void setCustomerId(long customerId) {
            this.customerId = customerId;
        }

        public long getWecomContactId() {
            return wecomContactId;
        }

        public void setWecomContactId(long wecomContactId) {
            this.wecomContactId = wecomContactId;
        }

        public String getWecomUserid() {
            return wecomUserid;
        }

        public void setWecomUserid(String wecomUserid) {
            this.wecomUserid = wecomUserid;
        }

        public String getExternalUserid() {
            return externalUserid;
        }

        public void setExternalUserid(String externalUserid) {
            this.externalUserid = externalUserid;
        }

        public String getAddTagIdsJson() {
            return addTagIdsJson;
        }

        public void setAddTagIdsJson(String addTagIdsJson) {
            this.addTagIdsJson = addTagIdsJson;
        }

        public String getRemoveTagIdsJson() {
            return removeTagIdsJson;
        }

        public void setRemoveTagIdsJson(String removeTagIdsJson) {
            this.removeTagIdsJson = removeTagIdsJson;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Instant getNextRetryAt() {
            return nextRetryAt;
        }

        public void setNextRetryAt(Instant nextRetryAt) {
            this.nextRetryAt = nextRetryAt;
        }

        public String getTriggerSource() {
            return triggerSource;
        }

        public void setTriggerSource(String triggerSource) {
            this.triggerSource = triggerSource;
        }

        public Long getActorUserId() {
            return actorUserId;
        }

        public void setActorUserId(Long actorUserId) {
            this.actorUserId = actorUserId;
        }
    }
}
