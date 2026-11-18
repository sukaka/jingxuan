package com.whale.crm.complaint.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.complaint.api.dto.ComplaintDetectionResponse;
import com.whale.crm.complaint.api.dto.ComplaintHitResponse;
import com.whale.crm.complaint.domain.ComplaintHit;
import com.whale.crm.complaint.domain.ComplaintRepository;
import com.whale.crm.customer.domain.Followup;
import com.whale.crm.customer.domain.FollowupRepository;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ComplaintDetectionApplicationService {
    private final ComplaintDetectionService detectionService;
    private final ComplaintRepository repository;
    private final FollowupRepository followupRepository;
    private final WecomRepository wecomRepository;
    private final ComplaintAccessService accessService;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ComplaintDetectionApplicationService(ComplaintDetectionService detectionService,
                                                ComplaintRepository repository,
                                                FollowupRepository followupRepository,
                                                WecomRepository wecomRepository,
                                                ComplaintAccessService accessService,
                                                IdempotencyService idempotencyService,
                                                AuditService auditService, ObjectMapper objectMapper) {
        this.detectionService = detectionService;
        this.repository = repository;
        this.followupRepository = followupRepository;
        this.wecomRepository = wecomRepository;
        this.accessService = accessService;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ComplaintDetectionResponse detectFollowup(CurrentUser user, long followupId, long version,
                                                      String idempotencyKey) {
        accessService.require(user, "complaint:write");
        Followup followup = followupRepository.findById(user.tenantId(), followupId)
                .filter(value -> !value.isDeleted())
                .orElseThrow(() -> accessService.notFound("跟进记录不存在或不可见"));
        accessService.requireCustomerVisible(user, followup.getCustomerId());
        String scope = "complaint.detect.followup";
        String payload = payload(followupId, version);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), scope, idempotencyKey, payload);
        if (replay.isEmpty()) {
            detectionService.detectFollowup(user.tenantId(), followupId, version);
            List<Long> hitIds = repository.findHitsBySource(user.tenantId(), ComplaintDetectionService.SOURCE_FOLLOWUP,
                    Long.toString(followupId)).stream().map(ComplaintHit::id).toList();
            auditService.record(user.tenantId(), user.userId(), "COMPLAINT_DETECT_MANUAL", "FOLLOWUP",
                    Long.toString(followupId), null, Map.of("sourceVersion", version, "hitIds", hitIds), null);
            idempotencyService.recordSuccess(user.tenantId(), scope, idempotencyKey, "FOLLOWUP",
                    Long.toString(followupId), payload);
        } else {
            requireSource(replay.orElseThrow(), followupId);
        }
        return response(user.tenantId(), ComplaintDetectionService.SOURCE_FOLLOWUP, followupId);
    }

    @Transactional
    public ComplaintDetectionResponse detectWecom(CurrentUser user, long messageId, long version,
                                                   String idempotencyKey) {
        accessService.require(user, "complaint:write");
        WecomMessage message = wecomRepository.findMessageById(user.tenantId(), messageId)
                .orElseThrow(() -> accessService.notFound("企微消息不存在或不可见"));
        if (message.customerId() == null) {
            throw accessService.notFound("企微消息不存在或不可见");
        }
        accessService.requireCustomerVisible(user, message.customerId());
        String scope = "complaint.detect.wecom";
        String payload = payload(messageId, version);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(
                user.tenantId(), scope, idempotencyKey, payload);
        if (replay.isEmpty()) {
            detectionService.detectWecom(user.tenantId(), messageId, version);
            List<Long> hitIds = repository.findHitsBySource(user.tenantId(), ComplaintDetectionService.SOURCE_WECOM,
                    Long.toString(messageId)).stream().map(ComplaintHit::id).toList();
            auditService.record(user.tenantId(), user.userId(), "COMPLAINT_DETECT_MANUAL", "WECOM_MESSAGE",
                    Long.toString(messageId), null, Map.of("sourceVersion", version, "hitIds", hitIds), null);
            idempotencyService.recordSuccess(user.tenantId(), scope, idempotencyKey, "WECOM_MESSAGE",
                    Long.toString(messageId), payload);
        } else {
            requireSource(replay.orElseThrow(), messageId);
        }
        return response(user.tenantId(), ComplaintDetectionService.SOURCE_WECOM, messageId);
    }

    private ComplaintDetectionResponse response(long tenantId, String sourceType, long sourceId) {
        List<ComplaintHitResponse> hits = repository.findHitsBySource(tenantId, sourceType, Long.toString(sourceId))
                .stream().map(ComplaintHitResponse::from).toList();
        return new ComplaintDetectionResponse(sourceType, Long.toString(sourceId), hits);
    }

    private void requireSource(IdempotencyRecord record, long sourceId) {
        if (!Long.toString(sourceId).equals(record.getResourceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果与来源记录不一致");
        }
    }

    private String payload(Object... values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等请求序列化失败", ex);
        }
    }
}
