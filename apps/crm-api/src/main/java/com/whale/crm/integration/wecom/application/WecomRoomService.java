package com.whale.crm.integration.wecom.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.wecom.api.dto.WecomRoomParticipantView;
import com.whale.crm.integration.wecom.api.dto.WecomRoomSaveRequest;
import com.whale.crm.integration.wecom.api.dto.WecomRoomView;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.integration.wecom.domain.WecomRoom;
import com.whale.crm.integration.wecom.domain.WecomRoomParticipant;
import com.whale.crm.integration.wecom.domain.WecomRoomRepository;
import com.whale.crm.integration.wecom.domain.WecomStaffAccount;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WecomRoomService {
    private static final String SAVE_SCOPE = "wecom.room.save";

    private final WecomRoomRepository repository;
    private final WecomContactSyncRepository contacts;
    private final CustomerScopeSupport scopeSupport;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public WecomRoomService(WecomRoomRepository repository, WecomContactSyncRepository contacts,
                            CustomerScopeSupport scopeSupport, IdempotencyService idempotencyService,
                            AuditService auditService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.contacts = contacts;
        this.scopeSupport = scopeSupport;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<WecomRoomView> list(CurrentUser user, String corpId) {
        var query = scopeSupport.queryOf(scopeSupport.scope(user), user);
        return repository.listRooms(user.tenantId(), corpId.strip(), query.scopeType(), query.teamIds(), query.selfId())
                .stream().map(this::view).toList();
    }

    @Transactional
    public WecomRoomView save(CurrentUser user, String roomId, WecomRoomSaveRequest request, String idempotencyKey) {
        String normalizedRoomId = roomId.strip();
        if (normalizedRoomId.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "群聊 roomid 不能为空");
        }
        String corpId = request.corpId().strip();
        List<String> members = request.memberUserids().stream().map(String::strip).distinct().toList();
        String payload = payload(normalizedRoomId, corpId, members, request);
        var replay = idempotencyService.lookup(user.tenantId(), SAVE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            if (!normalizedRoomId.equals(replay.orElseThrow().getResourceId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "幂等结果与群聊资源不一致");
            }
            return view(repository.findRoom(user.tenantId(), corpId, normalizedRoomId).orElseThrow(this::notFound));
        }
        WecomRoom before = repository.findRoomForUpdate(user.tenantId(), corpId, normalizedRoomId).orElse(null);
        long expectedVersion = before == null ? 0 : before.version();
        if (request.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.CONFLICT, "群聊配置已变化，请刷新后重试");
        }
        List<WecomRoomParticipant> beforeParticipants = before == null ? List.of()
                : repository.findParticipants(user.tenantId(), corpId, normalizedRoomId);
        WecomRoom next = room(user.tenantId(), normalizedRoomId, corpId, request, expectedVersion);
        Instant now = Instant.now();
        if (before == null) {
            try {
                repository.insertRoom(user.tenantId(), next, user.userId(), now);
            } catch (org.springframework.dao.DuplicateKeyException ex) {
                throw new BusinessException(ErrorCode.CONFLICT, "群聊已创建，请刷新后重试");
            }
        } else if (repository.updateRoom(user.tenantId(), next, user.userId(), now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "群聊配置已变化，请刷新后重试");
        }
        saveParticipants(user.tenantId(), corpId, normalizedRoomId, members);
        if (request.enabled()) {
            WecomRoomAuthorization eligibility = authorize(user.tenantId(), corpId, normalizedRoomId, null);
            if (!eligibility.allowed()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, eligibility.detail());
            }
        }
        WecomRoom after = repository.findRoom(user.tenantId(), corpId, normalizedRoomId).orElseThrow(this::notFound);
        auditService.record(user.tenantId(), user.userId(), before == null ? "WECOM_ROOM_CREATE" : "WECOM_ROOM_UPDATE",
                "WECOM_ROOM", normalizedRoomId, snapshot(before, beforeParticipants),
                snapshot(after, repository.findParticipants(user.tenantId(), corpId, normalizedRoomId)), request.reason().strip());
        idempotencyService.recordSuccess(user.tenantId(), SAVE_SCOPE, idempotencyKey, "WECOM_ROOM",
                normalizedRoomId, payload);
        return view(after);
    }

    public WecomRoomAuthorization authorize(long tenantId, String corpId, String roomId, String senderWecomUserid) {
        WecomRoom room = repository.findRoom(tenantId, corpId, roomId).orElse(null);
        if (room == null || room.membershipConfirmedAt() == null) {
            return WecomRoomAuthorization.reject("ROOM_CONTEXT_UNCONFIRMED", "群聊与完整成员清单尚未确认，未保存消息正文");
        }
        if (!"ACTIVE".equals(room.status())) {
            return WecomRoomAuthorization.reject("ROOM_ARCHIVING_DISABLED", "群聊已停止新归档，历史消息不变");
        }
        List<WecomRoomParticipant> participants = repository.findParticipants(tenantId, corpId, roomId);
        if (participants.stream().noneMatch(item -> item.active() && item.external())) {
            return WecomRoomAuthorization.reject("ROOM_EXTERNAL_PARTICIPANT_REQUIRED", "群聊缺少已确认的外部参与人，不归档内部群或未知群");
        }
        if (repository.countCoveredStaff(tenantId, corpId, roomId) < 1) {
            return WecomRoomAuthorization.reject("MEMBER_NOT_COVERED", "群聊没有当前可采成员，未保存消息正文");
        }
        if (!repository.allExternalConsentGranted(tenantId, corpId, roomId)) {
            return WecomRoomAuthorization.reject("CONSENT_NOT_GRANTED", "群聊外部参与人的当前同意未全部确认，未保存消息正文");
        }
        if (senderWecomUserid == null) {
            return WecomRoomAuthorization.allow(room, null);
        }
        WecomRoomParticipant sender = participants.stream()
                .filter(item -> item.active() && item.wecomUserid().equals(senderWecomUserid)).findFirst().orElse(null);
        if (sender == null) {
            return WecomRoomAuthorization.reject("ROOM_SENDER_NOT_CONFIRMED", "消息发送人不在已确认的当前群成员清单中");
        }
        if (sender.staff() && !repository.senderCoveredStaff(tenantId, corpId, roomId, senderWecomUserid)) {
            return WecomRoomAuthorization.reject("MEMBER_NOT_COVERED", "发送员工的映射、在职状态或归档覆盖无效");
        }
        return WecomRoomAuthorization.allow(room, sender);
    }

    private void saveParticipants(long tenantId, String corpId, String roomId, List<String> members) {
        Instant now = Instant.now();
        List<WecomRoomParticipant> before = repository.findParticipants(tenantId, corpId, roomId);
        for (WecomRoomParticipant participant : before) {
            if (!members.contains(participant.wecomUserid()) && participant.active()) {
                if (repository.updateParticipant(tenantId, participant.withStatus("REMOVED", now), now) != 1) {
                    throw new BusinessException(ErrorCode.CONFLICT, "群成员已变化，请重试");
                }
            }
        }
        for (String userid : members) {
            WecomRoomParticipant participant = before.stream().filter(item -> item.wecomUserid().equals(userid))
                    .findFirst().orElse(null);
            WecomRoomParticipant next = identified(tenantId, corpId, roomId, userid, participant);
            if (participant == null) {
                repository.insertParticipant(tenantId, next, now);
            } else if (repository.updateParticipant(tenantId, next.withStatus("ACTIVE", null), now) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "群成员已变化，请重试");
            }
        }
    }

    private WecomRoomParticipant identified(long tenantId, String corpId, String roomId, String userid,
                                            WecomRoomParticipant previous) {
        Long id = previous == null ? null : previous.id();
        long version = previous == null ? 0 : previous.version();
        var staff = contacts.staff(tenantId, corpId, userid).filter(WecomStaffAccount::enabled)
                .filter(item -> item.staffUserId() != null);
        if (staff.isPresent()) {
            return new WecomRoomParticipant(id, corpId, roomId, userid, "STAFF", staff.orElseThrow().staffUserId(),
                    null, "ACTIVE", null, null, version);
        }
        if (userid.startsWith("wo") || userid.startsWith("wm")) {
            return new WecomRoomParticipant(id, corpId, roomId, userid, "EXTERNAL", null, userid,
                    "ACTIVE", null, null, version);
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "群成员不是已启用的企微员工映射或有效外部身份");
    }

    private WecomRoomView view(WecomRoom room) {
        List<WecomRoomParticipant> participants = repository.findParticipants(room.tenantId(), room.corpId(), room.roomId());
        boolean archivable = "ACTIVE".equals(room.status())
                && repository.countCoveredStaff(room.tenantId(), room.corpId(), room.roomId()) > 0
                && repository.allExternalConsentGranted(room.tenantId(), room.corpId(), room.roomId())
                && participants.stream().anyMatch(item -> item.active() && item.external());
        return new WecomRoomView(room.corpId(), room.roomId(), room.displayName(), "ACTIVE".equals(room.status()),
                archivable, room.membershipConfirmedAt(), room.version(),
                participants.stream().map(WecomRoomParticipantView::from).toList());
    }

    private WecomRoom room(long tenantId, String roomId, String corpId, WecomRoomSaveRequest request, long version) {
        String name = request.displayName() == null || request.displayName().isBlank() ? null : request.displayName().strip();
        return new WecomRoom(null, tenantId, corpId, roomId, name, request.enabled() ? "ACTIVE" : "DISABLED",
                request.sourceEvidenceRef().strip(), null, null, null, null, version);
    }

    private String payload(String roomId, String corpId, List<String> members, WecomRoomSaveRequest request) {
        try {
            return objectMapper.writeValueAsString(Map.of("roomId", roomId, "corpId", corpId, "members", members,
                    "enabled", request.enabled(), "displayName", request.displayName() == null ? "" : request.displayName().strip(),
                    "sourceEvidenceRef", request.sourceEvidenceRef().strip(), "version", request.version()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法计算群聊配置请求哈希", ex);
        }
    }

    private Map<String, Object> snapshot(WecomRoom room, List<WecomRoomParticipant> participants) {
        if (room == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("corpId", room.corpId());
        snapshot.put("roomId", room.roomId());
        snapshot.put("enabled", "ACTIVE".equals(room.status()));
        snapshot.put("version", room.version());
        snapshot.put("memberCount", participants.stream().filter(WecomRoomParticipant::active).count());
        snapshot.put("participants", participants.stream().map(item -> Map.of("wecomUserid", item.wecomUserid(),
                "participantType", item.participantType(), "status", item.status())).toList());
        return snapshot;
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "群聊不存在或不在数据范围内");
    }
}
