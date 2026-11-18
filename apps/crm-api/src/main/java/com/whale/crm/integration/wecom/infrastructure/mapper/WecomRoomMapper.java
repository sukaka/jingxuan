package com.whale.crm.integration.wecom.infrastructure.mapper;

import com.whale.crm.integration.wecom.domain.WecomRoom;
import com.whale.crm.integration.wecom.domain.WecomRoomParticipant;
import com.whale.crm.integration.wecom.domain.WecomRoomRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface WecomRoomMapper extends WecomRoomRepository {
    @Override
    Optional<WecomRoom> findRoom(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                                 @Param("roomId") String roomId);

    @Override
    Optional<WecomRoom> findRoomForUpdate(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                                          @Param("roomId") String roomId);

    @Override
    List<WecomRoom> listRooms(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                              @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds,
                              @Param("selfId") long selfId);

    @Override
    List<WecomRoomParticipant> findParticipants(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                                                @Param("roomId") String roomId);

    @Override
    Optional<WecomRoomParticipant> findParticipant(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                                                   @Param("roomId") String roomId,
                                                   @Param("wecomUserid") String wecomUserid);

    @Override
    void insertRoom(@Param("tenantId") long tenantId, @Param("room") WecomRoom room,
                    @Param("actorUserId") long actorUserId, @Param("now") Instant now);

    @Override
    int updateRoom(@Param("tenantId") long tenantId, @Param("room") WecomRoom room,
                   @Param("actorUserId") long actorUserId, @Param("now") Instant now);

    @Override
    void insertParticipant(@Param("tenantId") long tenantId, @Param("participant") WecomRoomParticipant participant,
                           @Param("now") Instant now);

    @Override
    int updateParticipant(@Param("tenantId") long tenantId, @Param("participant") WecomRoomParticipant participant,
                          @Param("now") Instant now);

    @Override
    int countCoveredStaff(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                          @Param("roomId") String roomId);

    @Override
    boolean senderCoveredStaff(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                               @Param("roomId") String roomId, @Param("wecomUserid") String wecomUserid);

    @Override
    boolean allExternalConsentGranted(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                                      @Param("roomId") String roomId);
}
