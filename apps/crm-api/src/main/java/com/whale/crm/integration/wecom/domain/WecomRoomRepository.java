package com.whale.crm.integration.wecom.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WecomRoomRepository {
    Optional<WecomRoom> findRoom(long tenantId, String corpId, String roomId);
    Optional<WecomRoom> findRoomForUpdate(long tenantId, String corpId, String roomId);
    List<WecomRoom> listRooms(long tenantId, String corpId, String scopeType, List<Long> teamIds, long selfId);
    List<WecomRoomParticipant> findParticipants(long tenantId, String corpId, String roomId);
    Optional<WecomRoomParticipant> findParticipant(long tenantId, String corpId, String roomId, String wecomUserid);
    void insertRoom(long tenantId, WecomRoom room, long actorUserId, Instant now);
    int updateRoom(long tenantId, WecomRoom room, long actorUserId, Instant now);
    void insertParticipant(long tenantId, WecomRoomParticipant participant, Instant now);
    int updateParticipant(long tenantId, WecomRoomParticipant participant, Instant now);
    int countCoveredStaff(long tenantId, String corpId, String roomId);
    boolean senderCoveredStaff(long tenantId, String corpId, String roomId, String wecomUserid);
    boolean allExternalConsentGranted(long tenantId, String corpId, String roomId);
}
