package com.whale.crm.integration.wecom.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WecomContactSyncRepository {
    List<WecomStaffAccount> enabledStaff(long tenantId, String corpId);
    Optional<WecomStaffAccount> staff(long tenantId, String corpId, String wecomUserid);
    List<WecomStaffIdentity> activeStaffForAuth(long tenantId, String corpId, String wecomUserid);
    List<WecomStaffAccount> staffByUserId(long tenantId, String corpId, long staffUserId);
    long startRun(long tenantId, String corpId, String runType, Long actor, Instant now);
    long startRun(long tenantId, String corpId, String runType, Long actor, Instant now, Instant leaseUntil);
    int finishRun(long tenantId, long id, String status, String cursor, int received, int upserted,
                  int deactivated, String error, Instant finished);
    List<WecomContactSyncRun> runs(long tenantId, String corpId);
    void upsertContact(long tenantId, long staffUserId, long runId, Instant now, WecomContactProfile profile);
    int deactivateMissing(long tenantId, String corpId, long runId);
    void updateFollowStatus(long tenantId, String corpId, String externalUserid, long staffUserId, String status);
}
