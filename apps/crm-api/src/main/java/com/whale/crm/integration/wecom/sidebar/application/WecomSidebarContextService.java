package com.whale.crm.integration.wecom.sidebar.application;

import com.whale.crm.customer.api.dto.CustomerDetailResponse;
import com.whale.crm.customer.application.CustomerApplicationService;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.integration.wecom.domain.WecomRoom;
import com.whale.crm.integration.wecom.domain.WecomRoomParticipant;
import com.whale.crm.integration.wecom.domain.WecomRoomRepository;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarContextResponse;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarGroupContextResponse;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarLinkContactRequest;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarLinkContactResponse;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.application.EntitlementApplicationService;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.domain.EntitlementSummary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class WecomSidebarContextService {
    private static final String DEFAULT_SIDEBAR_LINK_REASON = "企微侧边栏管家人工绑定客户";

    private final WecomRepository repository;
    private final WecomRoomRepository roomRepository;
    private final WecomOfficialProperties properties;
    private final CustomerApplicationService customerService;
    private final CustomerScopeSupport scopeSupport;
    private final OrderApplicationService orderService;
    private final EntitlementApplicationService entitlementService;
    private final AuditService auditService;

    public WecomSidebarContextService(WecomRepository repository, WecomRoomRepository roomRepository,
                                      WecomOfficialProperties properties,
                                      CustomerApplicationService customerService, CustomerScopeSupport scopeSupport,
                                      OrderApplicationService orderService,
                                      EntitlementApplicationService entitlementService,
                                      AuditService auditService) {
        this.repository = repository;
        this.roomRepository = roomRepository;
        this.properties = properties;
        this.customerService = customerService;
        this.scopeSupport = scopeSupport;
        this.orderService = orderService;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public WecomSidebarContextResponse context(CurrentUser user, String externalUserid) {
        String corpId = requireCorpId(user);
        WecomSidebarContextResponse.Employee employee = new WecomSidebarContextResponse.Employee(
                user.userId(), user.displayName());
        if (externalUserid == null || externalUserid.isBlank()) {
            return new WecomSidebarContextResponse("WAITING_CONTEXT", corpId, null, employee, null,
                    new WecomSidebarContextResponse.Summary(null, null, null, null));
        }
        String normalizedExternalUserid = externalUserid.strip();
        List<WecomContact> contacts = repository.findContactsByExternalUserid(user.tenantId(), corpId,
                normalizedExternalUserid);
        List<Long> customerIds = contacts.stream()
                .filter(contact -> "ACTIVE".equals(contact.followStatus()))
                .map(WecomContact::customerId).filter(Objects::nonNull).distinct().toList();
        if (customerIds.isEmpty()) {
            return new WecomSidebarContextResponse("CUSTOMER_UNLINKED", corpId, normalizedExternalUserid, employee,
                    null, new WecomSidebarContextResponse.Summary(null, null, null, null));
        }
        if (customerIds.size() != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前企微客户关联了多个 CRM 客户，请人工核对");
        }
        long customerId = customerIds.getFirst();
        CustomerDetailResponse customer = customerService.detail(user.tenantId(), user, customerId);
        Long totalOrders = user.permissions().contains("order:read")
                ? orderService.search(user.tenantId(), user, customerId, null, 1, 1).total() : null;
        Long remainingPackages = null;
        Long remainingCount = null;
        if (user.permissions().contains("entitlement:read")) {
            EntitlementSummary entitlements = entitlementService.summary(user.tenantId(), user, customerId);
            remainingCount = entitlements.availableCount();
            remainingPackages = entitlementService.search(user.tenantId(), user, customerId, null,
                    null, true, 1, 1).total();
        }
        return new WecomSidebarContextResponse("READY", corpId, normalizedExternalUserid, employee, customer,
                new WecomSidebarContextResponse.Summary(totalOrders, remainingPackages, remainingCount,
                        customer.lastFollowupAt()));
    }

    @Transactional(readOnly = true)
    public WecomSidebarGroupContextResponse groupContext(CurrentUser user, String chatId) {
        String corpId = requireCorpId(user);
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群聊不存在或不在数据范围内");
        }
        WecomSidebarContextResponse.Employee employee = new WecomSidebarContextResponse.Employee(
                user.userId(), user.displayName());
        if (chatId == null || chatId.isBlank()) {
            return new WecomSidebarGroupContextResponse("WAITING_CONTEXT", corpId, null, employee, null, List.of());
        }
        String normalizedChatId = chatId.strip();
        WecomRoom room = roomRepository.findRoom(user.tenantId(), corpId, normalizedChatId).orElse(null);
        if (room == null) {
            return new WecomSidebarGroupContextResponse("ROOM_UNREGISTERED", corpId, normalizedChatId, employee,
                    null, List.of());
        }
        List<WecomRoomParticipant> activeParticipants = roomRepository
                .findParticipants(user.tenantId(), corpId, normalizedChatId)
                .stream().filter(WecomRoomParticipant::active).toList();
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        boolean staffVisible = scope.type() == DataScope.ScopeType.ALL;
        int staffCount = 0;
        List<String> externalUserids = new ArrayList<>();
        Set<String> seenExternals = new LinkedHashSet<>();
        for (WecomRoomParticipant participant : activeParticipants) {
            if (participant.staff()) {
                staffCount++;
                if (!staffVisible && participant.staffUserId() != null
                        && repository.staffVisible(user.tenantId(), participant.staffUserId(),
                        query.scopeType(), query.teamIds(), query.selfId())) {
                    staffVisible = true;
                }
            } else if (participant.external() && participant.externalUserid() != null
                    && !participant.externalUserid().isBlank()) {
                String extId = participant.externalUserid().strip();
                if (seenExternals.add(extId)) {
                    externalUserids.add(extId);
                }
            }
        }
        List<WecomSidebarGroupContextResponse.LinkedCustomer> linkedCustomers = new ArrayList<>();
        Set<Long> seenCustomerIds = new LinkedHashSet<>();
        for (String extId : externalUserids) {
            List<Long> customerIds = repository.findContactsByExternalUserid(user.tenantId(), corpId, extId).stream()
                    .filter(contact -> "ACTIVE".equals(contact.followStatus()))
                    .map(WecomContact::customerId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (customerIds.size() != 1) {
                continue;
            }
            long customerId = customerIds.getFirst();
            if (!seenCustomerIds.add(customerId)) {
                continue;
            }
            try {
                CustomerDetailResponse detail = customerService.detail(user.tenantId(), user, customerId);
                linkedCustomers.add(new WecomSidebarGroupContextResponse.LinkedCustomer(
                        detail.id(), detail.customerNo(), detail.displayName(), detail.nickname(),
                        extId, detail.tierCode(), detail.ownerName(), detail.mobileMasked()));
            } catch (BusinessException ex) {
                if (ex.errorCode() != ErrorCode.NOT_FOUND) {
                    throw ex;
                }
            }
        }
        if (!staffVisible && linkedCustomers.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群聊不存在或不在数据范围内");
        }
        WecomSidebarGroupContextResponse.RoomSummary roomSummary = new WecomSidebarGroupContextResponse.RoomSummary(
                room.roomId(), room.displayName(), room.status(),
                staffCount, externalUserids.size(), linkedCustomers.size());
        return new WecomSidebarGroupContextResponse("READY", corpId, normalizedChatId, employee,
                roomSummary, linkedCustomers);
    }

    @Transactional
    public WecomSidebarLinkContactResponse linkContact(CurrentUser user, WecomSidebarLinkContactRequest request) {
        long tenantId = user.tenantId();
        String corpId = requireCorpId(user);
        String externalUserid = request.externalUserid().strip();
        long customerId = request.customerId();
        String reason = request.reason() == null || request.reason().isBlank()
                ? DEFAULT_SIDEBAR_LINK_REASON : request.reason().strip();

        // 强制校验租户与客户 DataScope（越权或已删除一律 404）
        scopeSupport.requireVisible(tenantId, customerId, user);

        List<WecomContact> existing = repository.findContactsByExternalUserid(tenantId, corpId, externalUserid);
        for (WecomContact contact : existing) {
            if ("ACTIVE".equals(contact.followStatus())
                    && contact.customerId() != null
                    && !contact.customerId().equals(customerId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "该企微外部联系身份已绑定其他 CRM 客户，不能直接改绑");
            }
        }

        WecomContact own = repository.findContactForUpdate(tenantId, corpId, externalUserid, user.userId()).orElse(null);
        if (own != null) {
            if (own.customerId() != null) {
                if (!own.customerId().equals(customerId)) {
                    throw new BusinessException(ErrorCode.CONFLICT, "该外部身份已确认客户，不能直接改绑");
                }
                if (!"ACTIVE".equals(own.followStatus())) {
                    throw new BusinessException(ErrorCode.CONFLICT, "当前企微跟进关系已失效，无法绑定客户");
                }
                return toLinkResponse(own, true);
            }
            if (!"ACTIVE".equals(own.followStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前企微跟进关系已失效，无法绑定客户");
            }
            return linkExistingContact(tenantId, user, own, customerId, reason);
        }

        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scopeSupport.scope(user), user);
        List<WecomContact> visibleActive = existing.stream()
                .filter(c -> "ACTIVE".equals(c.followStatus()))
                .filter(c -> c.staffUserId() != null && repository.staffVisible(
                        tenantId, c.staffUserId(), query.scopeType(), query.teamIds(), query.selfId()))
                .toList();
        Optional<WecomContact> alreadyLinkedVisible = visibleActive.stream()
                .filter(c -> Objects.equals(c.customerId(), customerId))
                .findFirst();
        if (alreadyLinkedVisible.isPresent()) {
            return toLinkResponse(alreadyLinkedVisible.get(), true);
        }
        Optional<WecomContact> unlinkedVisible = visibleActive.stream()
                .filter(c -> c.customerId() == null)
                .findFirst();
        if (unlinkedVisible.isPresent()) {
            WecomContact candidate = unlinkedVisible.get();
            WecomContact locked = repository.findContactForUpdate(tenantId, corpId, externalUserid, candidate.staffUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "联系身份已变化，请刷新后重试"));
            if (locked.customerId() != null) {
                if (locked.customerId().equals(customerId)) {
                    return toLinkResponse(locked, true);
                }
                throw new BusinessException(ErrorCode.CONFLICT, "该外部身份已确认客户，不能直接改绑");
            }
            return linkExistingContact(tenantId, user, locked, customerId, reason);
        }

        // 未同步到本地的外部联系人：以当前员工身份新建关联，存档同意保持 PENDING（严禁伪造 GRANTED）
        Instant now = Instant.now();
        WecomContact newContact = new WecomContact(null, corpId, externalUserid, user.userId(),
                null, null, null, null, null, null, null, null, null, null, null, "ACTIVE",
                customerId, "PENDING", null, "MANUAL", reason, user.userId(), now, 0L);
        try {
            repository.insertContact(tenantId, newContact);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "该外部身份正在被其他请求绑定，请刷新后重试");
        }
        WecomContact created = repository.findContact(tenantId, corpId, externalUserid, user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "企微外部身份绑定失败，请重试"));
        repository.linkMessages(tenantId, corpId, externalUserid, user.userId(), customerId);
        auditService.record(tenantId, user.userId(), "WECOM_SIDEBAR_CONTACT_LINK", "WECOM_CONTACT",
                Long.toString(created.id()), null, linkSnapshot(created), reason);
        return toLinkResponse(created, false);
    }

    private WecomSidebarLinkContactResponse linkExistingContact(long tenantId, CurrentUser user, WecomContact before,
                                                                long customerId, String reason) {
        if (repository.linkContact(tenantId, before.id(), before.version(), customerId, reason, user.userId()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "联系身份版本已变化，请刷新后重试");
        }
        repository.linkMessages(tenantId, before.corpId(), before.externalUserid(), before.staffUserId(), customerId);
        WecomContact after = repository.findContactById(tenantId, before.id()).orElseThrow();
        auditService.record(tenantId, user.userId(), "WECOM_SIDEBAR_CONTACT_LINK", "WECOM_CONTACT",
                Long.toString(after.id()), linkSnapshot(before), linkSnapshot(after), reason);
        return toLinkResponse(after, false);
    }

    private WecomSidebarLinkContactResponse toLinkResponse(WecomContact contact, boolean alreadyLinked) {
        return new WecomSidebarLinkContactResponse(
                contact.id(), contact.corpId(), contact.externalUserid(), contact.staffUserId(),
                contact.customerId(), contact.linkType(), contact.linkReason(), alreadyLinked);
    }

    private Map<String, Object> linkSnapshot(WecomContact contact) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contactId", contact.id());
        snapshot.put("corpId", contact.corpId());
        snapshot.put("externalUserid", contact.externalUserid());
        snapshot.put("staffUserId", contact.staffUserId());
        snapshot.put("customerId", contact.customerId());
        snapshot.put("linkType", contact.linkType());
        snapshot.put("consentStatus", contact.consentStatus());
        snapshot.put("version", contact.version());
        return snapshot;
    }

    private String requireCorpId(CurrentUser user) {
        if (properties.allowedTenantId() <= 0 || user.tenantId() != properties.allowedTenantId()
                || properties.corpId() == null || properties.corpId().isBlank()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "当前租户未启用企微侧边栏配置");
        }
        return properties.corpId().strip();
    }
}
