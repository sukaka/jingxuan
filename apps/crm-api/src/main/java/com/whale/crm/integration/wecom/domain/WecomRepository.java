package com.whale.crm.integration.wecom.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WecomRepository {
    Optional<WecomContact> findContact(long tenantId, String corpId, String externalUserid, long staffUserId);
    Optional<WecomContact> findContactById(long tenantId, long id);
    void insertContact(long tenantId, WecomContact contact);
    int updateContactConsent(long tenantId, long id, long version, String status, String evidenceRef, Instant syncedAt);
    int linkContact(long tenantId, long id, long version, long customerId, String reason, long actorUserId);
    List<WecomContact> listContacts(long tenantId, String scopeType, List<Long> teamIds, long selfId);
    boolean archiveMemberEnabled(long tenantId, String corpId, long staffUserId);
    boolean staffVisible(long tenantId, long staffUserId, String scopeType, List<Long> teamIds, long selfId);

    Optional<WecomMessage> findMessage(long tenantId, String corpId, String msgId);
    Optional<WecomMessage> findMessageById(long tenantId, long id);
    Optional<WecomMessage> findReadableMessage(long tenantId, long id, String scopeType, List<Long> teamIds, long selfId);
    void insertMessage(long tenantId, WecomMessage message);
    int linkMessages(long tenantId, String corpId, String externalUserid, long staffUserId, long customerId);
    List<WecomMessage> listMessages(long tenantId, Long customerId, String scopeType, List<Long> teamIds, long selfId, int offset, int size);
    long countMessages(long tenantId, Long customerId, String scopeType, List<Long> teamIds, long selfId);
    long countAllMessages(long tenantId, String corpId);

    Optional<WecomCursor> findCursorForUpdate(long tenantId, String corpId, String archiveScope);
    Optional<WecomCursor> findCursor(long tenantId, String corpId, String archiveScope);
    void insertCursor(long tenantId, WecomCursor cursor);
    int updateCursor(long tenantId, long id, long version, long lastSeq, Instant successAt, Instant attemptedAt,
                     String status, int failures, String error, boolean dataGap, String gapReason);

    void recordGap(long tenantId, String corpId, String archiveScope, String gapType, Long fromSeq, Long toSeq,
                   String detail, Instant now);
    int resolveCollectorGaps(long tenantId, String corpId, String archiveScope, Instant resolvedAt);
    List<WecomDataGap> findOpenGaps(long tenantId, String corpId, String archiveScope);
    long countResolvedGaps(long tenantId, String corpId, String archiveScope);
}
