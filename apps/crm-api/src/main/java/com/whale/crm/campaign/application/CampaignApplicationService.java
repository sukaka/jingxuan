package com.whale.crm.campaign.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.f4b6a3.ulid.UlidCreator;
import com.whale.crm.campaign.api.dto.ChannelFinancialChangeRequestResponse;
import com.whale.crm.campaign.api.dto.CreateCampaignBatchRequest;
import com.whale.crm.campaign.api.dto.CreateCampaignRequest;
import com.whale.crm.campaign.api.dto.CreateChannelFinancialChangeRequest;
import com.whale.crm.campaign.api.dto.CreateChannelRequest;
import com.whale.crm.campaign.api.dto.PartnerChannelResponse;
import com.whale.crm.campaign.api.dto.ReviewChannelFinancialChangeRequest;
import com.whale.crm.campaign.api.dto.ToggleChannelStatusRequest;
import com.whale.crm.campaign.api.dto.UpdateCampaignBatchRequest;
import com.whale.crm.campaign.api.dto.UpdateCampaignRequest;
import com.whale.crm.campaign.api.dto.UpdateChannelRequest;
import com.whale.crm.campaign.domain.Campaign;
import com.whale.crm.campaign.domain.CampaignBatch;
import com.whale.crm.campaign.domain.CampaignRepository;
import com.whale.crm.campaign.domain.CampaignView;
import com.whale.crm.campaign.domain.ChannelChangeRequest;
import com.whale.crm.campaign.domain.ChannelOwnerOption;
import com.whale.crm.campaign.domain.OrderAttributionSnapshot;
import com.whale.crm.campaign.domain.PartnerChannel;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CampaignApplicationService {
    private static final int[] ID_CARD_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CARD_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
    private static final String CHANNEL_CHANGE_CREATE_SCOPE = "channel.financial-change.create";
    private static final String CHANNEL_CHANGE_REVIEW_SCOPE = "channel.financial-change.review";

    private final CampaignRepository repository;
    private final AuditService auditService;
    private final AesGcmCipher cipher;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public CampaignApplicationService(CampaignRepository repository, AuditService auditService, AesGcmCipher cipher,
                                      IdempotencyService idempotencyService, ObjectMapper objectMapper,
                                      PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.auditService = auditService;
        this.cipher = cipher;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<Campaign> campaigns(long tenantId, CurrentUser user, String keyword, String status,
                                            String campaignType, int page, int size) {
        requireAccess(tenantId, user, "campaign:read");
        String normalizedKeyword = blankToNull(keyword);
        String normalizedStatus = blankToNull(status);
        String normalizedType = blankToNull(campaignType);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchCampaigns(tenantId, normalizedKeyword, normalizedStatus,
                        normalizedType, offset, size),
                repository.countCampaigns(tenantId, normalizedKeyword, normalizedStatus, normalizedType), page, size);
    }

    @Transactional(readOnly = true)
    public CampaignView campaign(long tenantId, CurrentUser user, long id) {
        requireAccess(tenantId, user, "campaign:read");
        Campaign campaign = requireCampaign(tenantId, id);
        return new CampaignView(campaign, repository.findBatches(tenantId, id));
    }

    @Transactional
    public Campaign createCampaign(long tenantId, CurrentUser user, CreateCampaignRequest request) {
        requireAccess(tenantId, user, "campaign:write");
        String code = request.campaignCode().strip();
        if (repository.findCampaignByCode(tenantId, code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "活动编码已存在");
        }
        validateTimeRange(request.startsAt(), request.endsAt());
        String status = request.status() == null ? "ACTIVE" : request.status();
        if ("ACTIVE".equals(status)) {
            requireAccess(tenantId, user, "campaign:review");
        }
        Campaign input = new Campaign(null, code, request.campaignName().strip(), request.campaignType(),
                request.startsAt(), request.endsAt(), status, null, null);
        try {
            repository.insertCampaign(tenantId, input);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "活动编码已存在");
        }
        Campaign created = requireCampaignByCode(tenantId, code);
        audit(tenantId, user, "CAMPAIGN_CREATE", "CAMPAIGN", created.id(), null, created);
        return created;
    }

    @Transactional
    public Campaign updateCampaign(long tenantId, CurrentUser user, long id, UpdateCampaignRequest request) {
        requireAccess(tenantId, user, "campaign:write");
        Campaign before = requireCampaignForUpdate(tenantId, id);
        requireMutable(before.status(), "活动");
        requireCodeUnchanged(request.campaignCode(), before.campaignCode(), "活动编码不可修改");
        validateTimeRange(request.startsAt(), request.endsAt());
        validateStatusTransition(before.status(), request.status());
        if ("ACTIVE".equals(request.status()) && !"ACTIVE".equals(before.status())) {
            requireAccess(tenantId, user, "campaign:review");
        }
        for (CampaignBatch batch : repository.findBatches(tenantId, id)) {
            validateBatchWindow(request.startsAt(), request.endsAt(), batch.startsAt(), batch.endsAt());
        }
        Campaign input = new Campaign(id, before.campaignCode(), request.campaignName().strip(), request.campaignType(),
                request.startsAt(), request.endsAt(), request.status(), before.createdAt(), request.version());
        if (repository.updateCampaign(tenantId, input) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "活动已被他人修改，请刷新后重试");
        }
        Campaign after = requireCampaign(tenantId, id);
        audit(tenantId, user, "CAMPAIGN_UPDATE", "CAMPAIGN", id, before, after);
        return after;
    }

    @Transactional
    public CampaignBatch createBatch(long tenantId, CurrentUser user, long campaignId,
                                     CreateCampaignBatchRequest request) {
        requireAccess(tenantId, user, "campaign:write");
        Campaign campaign = requireCampaignForUpdate(tenantId, campaignId);
        requireMutable(campaign.status(), "活动");
        String code = request.batchCode().strip();
        if (repository.findBatchByCode(tenantId, campaignId, code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "活动批次编码已存在");
        }
        validateTimeRange(request.startsAt(), request.endsAt());
        validateBatchWindow(campaign.startsAt(), campaign.endsAt(), request.startsAt(), request.endsAt());
        String status = request.status() == null ? "ACTIVE" : request.status();
        CampaignBatch input = new CampaignBatch(null, campaignId, code, request.batchName().strip(),
                request.startsAt(), request.endsAt(), status, null, null);
        try {
            repository.insertBatch(tenantId, input);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "活动批次编码已存在");
        }
        CampaignBatch created = repository.findBatchByCode(tenantId, campaignId, code).orElseThrow();
        audit(tenantId, user, "CAMPAIGN_BATCH_CREATE", "CAMPAIGN_BATCH", created.id(), null, created);
        return created;
    }

    @Transactional
    public CampaignBatch updateBatch(long tenantId, CurrentUser user, long campaignId, long batchId,
                                     UpdateCampaignBatchRequest request) {
        requireAccess(tenantId, user, "campaign:write");
        Campaign campaign = requireCampaignForUpdate(tenantId, campaignId);
        requireMutable(campaign.status(), "活动");
        CampaignBatch before = requireBatch(tenantId, campaignId, batchId);
        requireMutable(before.status(), "批次");
        requireCodeUnchanged(request.batchCode(), before.batchCode(), "批次编码不可修改");
        validateTimeRange(request.startsAt(), request.endsAt());
        validateBatchWindow(campaign.startsAt(), campaign.endsAt(), request.startsAt(), request.endsAt());
        validateStatusTransition(before.status(), request.status());
        CampaignBatch input = new CampaignBatch(batchId, campaignId, before.batchCode(), request.batchName().strip(),
                request.startsAt(), request.endsAt(), request.status(), before.createdAt(), request.version());
        if (repository.updateBatch(tenantId, input) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "活动批次已被他人修改，请刷新后重试");
        }
        CampaignBatch after = requireBatch(tenantId, campaignId, batchId);
        audit(tenantId, user, "CAMPAIGN_BATCH_UPDATE", "CAMPAIGN_BATCH", batchId, before, after);
        return after;
    }

    @Transactional(readOnly = true)
    public PageResponse<PartnerChannelResponse> channels(long tenantId, CurrentUser user, String keyword, String status,
                                                         String channelType, Long businessOwnerUserId,
                                                         int page, int size) {
        requireAccess(tenantId, user, "channel:read");
        String normalizedKeyword = blankToNull(keyword);
        String normalizedStatus = blankToNull(status);
        String normalizedType = blankToNull(channelType);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchChannels(tenantId, normalizedKeyword, normalizedStatus,
                        normalizedType, businessOwnerUserId, offset, size).stream()
                .map(this::toChannelResponse).toList(),
                repository.countChannels(tenantId, normalizedKeyword, normalizedStatus, normalizedType,
                        businessOwnerUserId), page, size);
    }

    @Transactional(readOnly = true)
    public List<ChannelOwnerOption> channelOwnerOptions(long tenantId, CurrentUser user) {
        requireAccess(tenantId, user, "channel:read");
        return repository.listActiveChannelOwners(tenantId);
    }

    @Transactional
    public PartnerChannelResponse createChannel(long tenantId, CurrentUser user, CreateChannelRequest request) {
        requireAccess(tenantId, user, "channel:write");
        String channelNo = blankToNull(request.channelNo());
        if (channelNo == null) {
            channelNo = "CH" + UlidCreator.getUlid();
        }
        if (repository.findChannelByNo(tenantId, channelNo).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "渠道编号已存在");
        }
        requireRealOwner(tenantId, request.businessOwnerUserId());
        String channelType = blankToNull(request.channelType()) == null ? "BLOGGER" : request.channelType();
        String accountName = blankToNull(request.paymentAccountName());
        String accountNo = blankToNull(request.paymentAccountNo());
        ChannelMasker.rejectMaskedWildcard(accountName, "收款账户名称");
        ChannelMasker.rejectMaskedWildcard(accountNo, "收款账号");
        validateChannelProfile(request.contactPhone(), request.idCardNumber(), request.commissionPercent(),
                request.cooperationStartDate(), request.cooperationEndDate());
        validateChannelSettlement(channelType, request.settlementMethod());
        String status = request.status() == null ? "ACTIVE" : request.status();
        PartnerChannel input = new PartnerChannel(null, channelNo, request.name().strip(), channelType,
                blankToNull(request.platformCode()), request.commissionPercent(), request.businessOwnerUserId(), null,
                blankToNull(request.realName()), blankToNull(request.contactPhone()), normalizeIdCard(request.idCardNumber()),
                blankToNull(request.settlementMethod()), request.cooperationStartDate(), request.cooperationEndDate(),
                accountName, accountNo, blankToNull(request.paymentBankName()), status,
                blankToNull(request.remarks()), null, null);
        try {
            repository.insertChannel(tenantId, encryptSensitive(input));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "渠道编号已存在");
        }
        PartnerChannel created = repository.findChannelByNo(tenantId, channelNo).orElseThrow();
        auditService.record(tenantId, user.userId(), "CHANNEL_CREATE", "PARTNER_CHANNEL", created.id().toString(),
                null, channelAuditSnapshot(created), null);
        return toChannelResponse(created);
    }

    @Transactional
    public PartnerChannelResponse updateChannel(long tenantId, CurrentUser user, long id, UpdateChannelRequest request) {
        requireAccess(tenantId, user, "channel:write");
        PartnerChannel before = requireChannelForUpdate(tenantId, id);
        requireMutable(before.status(), "渠道");
        requireCodeUnchanged(request.channelNo(), before.channelNo(), "渠道编号不可修改");
        requireRealOwner(tenantId, request.businessOwnerUserId());
        validateStatusTransition(before.status(), request.status());

        boolean financialLocked = isFinancialLocked(before);
        BigDecimal requestedCommission = request.commissionPercent();
        String requestedSettlement = blankToNull(request.settlementMethod());
        String requestedAccountName = blankToNull(request.paymentAccountName());
        String requestedAccountNo = blankToNull(request.paymentAccountNo());
        String requestedBankName = blankToNull(request.paymentBankName());
        if (financialLocked && (requestedCommission != null || requestedSettlement != null
                || requestedAccountName != null || requestedAccountNo != null || requestedBankName != null)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "合作中或已配置财务资料的渠道禁止普通编辑修改财务字段，请发起财务资料变更申请");
        }

        validateChannelProfile(request.contactPhone(), request.idCardNumber(),
                financialLocked ? before.commissionPercent() : requestedCommission,
                request.cooperationStartDate(), request.cooperationEndDate());
        String channelType = blankToNull(request.channelType()) == null ? before.channelType() : request.channelType();

        String contactPhoneCipher = blankToNull(request.contactPhone()) == null
                ? before.contactPhone() : cipher.encrypt(blankToNull(request.contactPhone()));
        String idCardCipher = blankToNull(request.idCardNumber()) == null
                ? before.idCardNumber() : cipher.encrypt(normalizeIdCard(request.idCardNumber()));

        BigDecimal commissionPercent;
        String settlementMethod;
        String accountNameCipher;
        String accountNoCipher;
        String bankName;
        if (financialLocked) {
            commissionPercent = before.commissionPercent();
            settlementMethod = before.settlementMethod();
            accountNameCipher = before.paymentAccountName();
            accountNoCipher = before.paymentAccountNo();
            bankName = before.paymentBankName();
            validateChannelSettlement(channelType, settlementMethod);
        } else {
            ChannelMasker.rejectMaskedWildcard(requestedAccountName, "收款账户名称");
            ChannelMasker.rejectMaskedWildcard(requestedAccountNo, "收款账号");
            commissionPercent = requestedCommission;
            settlementMethod = requestedSettlement;
            validateChannelSettlement(channelType, settlementMethod);
            accountNameCipher = cipher.encrypt(requestedAccountName);
            accountNoCipher = cipher.encrypt(requestedAccountNo);
            bankName = requestedBankName;
        }

        PartnerChannel encryptedInput = new PartnerChannel(id, before.channelNo(), request.name().strip(), channelType,
                blankToNull(request.platformCode()), commissionPercent, request.businessOwnerUserId(), null,
                blankToNull(request.realName()), contactPhoneCipher, idCardCipher, settlementMethod,
                request.cooperationStartDate(), request.cooperationEndDate(), accountNameCipher, accountNoCipher,
                bankName, request.status(), blankToNull(request.remarks()), before.createdAt(), request.version());
        if (repository.updateChannel(tenantId, encryptedInput) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "渠道已被他人修改，请刷新后重试");
        }
        PartnerChannel after = requireChannel(tenantId, id);
        auditService.record(tenantId, user.userId(), "CHANNEL_UPDATE", "PARTNER_CHANNEL", Long.toString(id),
                channelAuditSnapshot(before), channelAuditSnapshot(after), null);
        return toChannelResponse(after);
    }

    @Transactional
    public PartnerChannelResponse toggleChannelStatus(long tenantId, CurrentUser user, long id,
                                                      ToggleChannelStatusRequest request) {
        requireAccess(tenantId, user, "channel:write");
        PartnerChannel before = requireChannelForUpdate(tenantId, id);
        requireMutable(before.status(), "渠道");
        if (!before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "渠道版本已变化，请刷新后重试");
        }
        validateStatusTransition(before.status(), request.status());
        if (repository.updateChannelStatus(tenantId, id, request.version(), request.status()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "渠道已被他人修改，请刷新后重试");
        }
        PartnerChannel after = requireChannel(tenantId, id);
        auditService.record(tenantId, user.userId(), "CHANNEL_STATUS_CHANGE", "PARTNER_CHANNEL", Long.toString(id),
                channelAuditSnapshot(before), channelAuditSnapshot(after), blankToNull(request.reason()));
        return toChannelResponse(after);
    }

    public ChannelFinancialChangeRequestResponse createFinancialChangeRequest(long tenantId, CurrentUser user,
                                                                              long channelId,
                                                                              CreateChannelFinancialChangeRequest request,
                                                                              String idempotencyKey) {
        requireAccess(tenantId, user, "channel:write");
        String key = requireIdempotencyKey(idempotencyKey);
        BigDecimal newCommission = request.commissionPercent();
        String newSettlement = blankToNull(request.settlementMethod());
        String newAccountName = blankToNull(request.paymentAccountName());
        String newAccountNo = blankToNull(request.paymentAccountNo());
        String newBankName = blankToNull(request.paymentBankName());
        if (newCommission == null && newSettlement == null && newAccountName == null
                && newAccountNo == null && newBankName == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请至少修改一项财务或收款资料");
        }
        ChannelMasker.rejectMaskedWildcard(newAccountName, "收款账户名称");
        ChannelMasker.rejectMaskedWildcard(newAccountNo, "收款账号");
        if (newCommission != null && (newCommission.compareTo(BigDecimal.ZERO) < 0
                || newCommission.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分佣比例必须在0%到100%之间");
        }

        String payload = jsonText(new CreateFinancialChangeCanonical(channelId, newCommission, newSettlement,
                newAccountName, newAccountNo, newBankName, request.baseVersion(), request.reason().strip()));
        IdempotencyRecord replay = idempotencyService.lookup(tenantId, CHANNEL_CHANGE_CREATE_SCOPE, key, payload)
                .orElse(null);
        if (replay != null) {
            ChannelChangeRequest existing = requireChannelChangeRequest(tenantId,
                    Long.parseLong(replay.getResourceId()));
            return toChangeRequestResponse(existing, user);
        }
        try {
            return transactionTemplate.execute(status -> doCreateFinancialChange(tenantId, user, channelId,
                    newCommission, newSettlement, newAccountName, newAccountNo, newBankName, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayCreateFinancialChange(tenantId, user, key, payload));
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<ChannelFinancialChangeRequestResponse> channelFinancialChangeRequests(
            long tenantId, CurrentUser user, long channelId, String status, int page, int size) {
        requireAnyAccess(tenantId, user, "channel:read", "channel:approve_finance");
        requireChannel(tenantId, channelId);
        String normalizedStatus = blankToNull(status);
        int offset = (page - 1) * size;
        List<ChannelFinancialChangeRequestResponse> items = repository.searchChannelChangeRequests(
                        tenantId, channelId, normalizedStatus, offset, size).stream()
                .map(req -> toChangeRequestResponse(req, user)).toList();
        return new PageResponse<>(items, repository.countChannelChangeRequests(tenantId, channelId, normalizedStatus),
                page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<ChannelFinancialChangeRequestResponse> allChannelFinancialChangeRequests(
            long tenantId, CurrentUser user, Long channelId, String status, int page, int size) {
        requireAnyAccess(tenantId, user, "channel:read", "channel:approve_finance");
        String normalizedStatus = blankToNull(status);
        int offset = (page - 1) * size;
        List<ChannelFinancialChangeRequestResponse> items = repository.searchChannelChangeRequests(
                        tenantId, channelId, normalizedStatus, offset, size).stream()
                .map(req -> toChangeRequestResponse(req, user)).toList();
        return new PageResponse<>(items, repository.countChannelChangeRequests(tenantId, channelId, normalizedStatus),
                page, size);
    }

    public ChannelFinancialChangeRequestResponse reviewFinancialChangeRequest(
            long tenantId, CurrentUser user, long reqId, ReviewChannelFinancialChangeRequest request,
            String idempotencyKey) {
        requireAccess(tenantId, user, "channel:approve_finance");
        String key = blankToNull(idempotencyKey);
        if (key != null && key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "幂等键长度不能超过128字符");
        }
        String comment = blankToNull(request.comment());
        String payload = jsonText(new ReviewFinancialChangeCanonical(reqId, request.decision(),
                request.version(), comment));
        if (key != null) {
            IdempotencyRecord replay = idempotencyService.lookup(tenantId, CHANNEL_CHANGE_REVIEW_SCOPE, key, payload)
                    .orElse(null);
            if (replay != null) {
                return toChangeRequestResponse(requireChannelChangeRequest(tenantId, reqId), user);
            }
        }
        try {
            return transactionTemplate.execute(status -> doReviewFinancialChange(
                    tenantId, user, reqId, request, comment, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayReviewFinancialChange(
                    tenantId, user, reqId, key, payload));
        }
    }

    private ChannelFinancialChangeRequestResponse doCreateFinancialChange(
            long tenantId, CurrentUser user, long channelId, BigDecimal newCommission, String newSettlement,
            String newAccountName, String newAccountNo, String newBankName,
            CreateChannelFinancialChangeRequest request, String key, String payload) {
        PartnerChannel channel = requireChannelForUpdate(tenantId, channelId);
        IdempotencyRecord replay = idempotencyService.lookup(tenantId, CHANNEL_CHANGE_CREATE_SCOPE, key, payload)
                .orElse(null);
        if (replay != null) {
            ChannelChangeRequest existing = requireChannelChangeRequest(tenantId,
                    Long.parseLong(replay.getResourceId()));
            return toChangeRequestResponse(existing, user);
        }
        requireMutable(channel.status(), "渠道");
        if (!channel.version().equals(request.baseVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "baseVersion与渠道当前版本不一致，请刷新后重试");
        }
        if (newSettlement != null) {
            validateChannelSettlement(channel.channelType(), newSettlement);
        }
        if (repository.findActiveChannelChangeRequest(tenantId, channelId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该渠道已有待财务审批的变更申请");
        }

        String oldAccountNamePlain = cipher.decrypt(channel.paymentAccountName());
        String oldAccountNoPlain = cipher.decrypt(channel.paymentAccountNo());
        Map<String, Object> beforeCipherMap = new LinkedHashMap<>();
        Map<String, Object> afterCipherMap = new LinkedHashMap<>();
        Map<String, Object> beforeMaskedMap = new LinkedHashMap<>();
        Map<String, Object> afterMaskedMap = new LinkedHashMap<>();

        if (newCommission != null) {
            beforeCipherMap.put("commissionPercent", channel.commissionPercent());
            afterCipherMap.put("commissionPercent", newCommission);
            beforeMaskedMap.put("commissionPercent", channel.commissionPercent());
            afterMaskedMap.put("commissionPercent", newCommission);
        }
        if (newSettlement != null) {
            beforeCipherMap.put("settlementMethod", channel.settlementMethod());
            afterCipherMap.put("settlementMethod", newSettlement);
            beforeMaskedMap.put("settlementMethod", channel.settlementMethod());
            afterMaskedMap.put("settlementMethod", newSettlement);
        }
        if (newAccountName != null) {
            beforeCipherMap.put("paymentAccountName", oldAccountNamePlain);
            afterCipherMap.put("paymentAccountName", newAccountName);
            beforeMaskedMap.put("paymentAccountName", ChannelMasker.maskAccountName(oldAccountNamePlain));
            afterMaskedMap.put("paymentAccountName", ChannelMasker.maskAccountName(newAccountName));
        }
        if (newAccountNo != null) {
            beforeCipherMap.put("paymentAccountNo", oldAccountNoPlain);
            afterCipherMap.put("paymentAccountNo", newAccountNo);
            beforeMaskedMap.put("paymentAccountNo", ChannelMasker.maskAccountNo(oldAccountNoPlain));
            afterMaskedMap.put("paymentAccountNo", ChannelMasker.maskAccountNo(newAccountNo));
        }
        if (newBankName != null) {
            beforeCipherMap.put("paymentBankName", channel.paymentBankName());
            afterCipherMap.put("paymentBankName", newBankName);
            beforeMaskedMap.put("paymentBankName", channel.paymentBankName());
            afterMaskedMap.put("paymentBankName", newBankName);
        }

        ChannelChangeRequest draft = new ChannelChangeRequest(
                null, channelId, channel.channelNo(), channel.name(), request.baseVersion(),
                cipher.encrypt(jsonText(beforeCipherMap)), cipher.encrypt(jsonText(afterCipherMap)),
                jsonText(beforeMaskedMap), jsonText(afterMaskedMap), request.reason().strip(),
                "PENDING_REVIEW", user.userId(), user.displayName(), Instant.now(),
                null, null, null, null, null);
        repository.insertChannelChangeRequest(tenantId, draft);
        ChannelChangeRequest created = repository.findActiveChannelChangeRequest(tenantId, channelId).orElseThrow();
        auditService.record(tenantId, user.userId(), "CHANNEL_FINANCE_CHANGE_REQUEST", "PARTNER_CHANNEL",
                Long.toString(channelId), null, changeAuditSnapshot(created), created.reason());
        idempotencyService.recordSuccess(tenantId, CHANNEL_CHANGE_CREATE_SCOPE, key,
                "CHANNEL_CHANGE_REQUEST", Long.toString(created.id()), payload);
        return toChangeRequestResponse(created, user);
    }

    private ChannelFinancialChangeRequestResponse doReviewFinancialChange(
            long tenantId, CurrentUser user, long reqId, ReviewChannelFinancialChangeRequest request,
            String comment, String key, String payload) {
        ChannelChangeRequest before = repository.findChannelChangeRequestForUpdate(tenantId, reqId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "渠道财务变更申请不存在"));
        PartnerChannel channel = requireChannelForUpdate(tenantId, before.channelId());
        if (key != null) {
            IdempotencyRecord replay = idempotencyService.lookup(tenantId, CHANNEL_CHANGE_REVIEW_SCOPE, key, payload)
                    .orElse(null);
            if (replay != null) {
                return toChangeRequestResponse(requireChannelChangeRequest(tenantId, reqId), user);
            }
        }
        if (!"PENDING_REVIEW".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "财务变更申请已处理，不能重复审批");
        }
        if (!before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "财务变更申请版本已变化，请刷新后重试");
        }
        if (before.requestedBy().equals(user.userId())) {
            auditService.recordIndependent(tenantId, user.userId(), "CHANNEL_SELF_REVIEW_DENIED",
                    "PARTNER_CHANNEL", Long.toString(before.channelId()), null,
                    changeAuditSnapshot(before), "申请人不能审批本人提交的渠道财务变更");
            throw new BusinessException(ErrorCode.FORBIDDEN, "申请人不能审批本人提交的渠道财务变更");
        }

        Instant now = Instant.now();
        if ("APPROVE".equals(request.decision())) {
            if ("ARCHIVED".equals(channel.status()) || !channel.version().equals(before.baseVersion())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "渠道状态或版本已变更，请刷新后重新发起财务变更申请");
            }
            JsonNode afterNode = parseJson(cipher.decrypt(before.afterCipher()));
            BigDecimal resolvedCommission = afterNode.has("commissionPercent")
                    ? (afterNode.get("commissionPercent").isNull()
                    ? null : afterNode.get("commissionPercent").decimalValue())
                    : channel.commissionPercent();
            String resolvedSettlement = afterNode.has("settlementMethod")
                    ? textOrNull(afterNode, "settlementMethod")
                    : channel.settlementMethod();
            validateChannelSettlement(channel.channelType(), resolvedSettlement);
            String resolvedAccountNameCipher = afterNode.has("paymentAccountName")
                    ? cipher.encrypt(textOrNull(afterNode, "paymentAccountName"))
                    : channel.paymentAccountName();
            String resolvedAccountNoCipher = afterNode.has("paymentAccountNo")
                    ? cipher.encrypt(textOrNull(afterNode, "paymentAccountNo"))
                    : channel.paymentAccountNo();
            String resolvedBankName = afterNode.has("paymentBankName")
                    ? textOrNull(afterNode, "paymentBankName")
                    : channel.paymentBankName();

            if (repository.updateChannelFinancials(tenantId, channel.id(), before.baseVersion(),
                    resolvedCommission, resolvedSettlement, resolvedAccountNameCipher,
                    resolvedAccountNoCipher, resolvedBankName) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "渠道版本冲突，财务变更未能生效");
            }
            if (repository.updateChannelChangeRequestStatus(tenantId, reqId, before.version(),
                    "PENDING_REVIEW", "APPROVED", user.userId(), now, comment) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "财务变更申请已被其他审批人处理");
            }
            ChannelChangeRequest after = requireChannelChangeRequest(tenantId, reqId);
            auditService.record(tenantId, user.userId(), "CHANNEL_FINANCE_CHANGE_APPROVE", "PARTNER_CHANNEL",
                    Long.toString(channel.id()), changeAuditSnapshot(before), changeAuditSnapshot(after), comment);
            if (key != null) {
                idempotencyService.recordSuccess(tenantId, CHANNEL_CHANGE_REVIEW_SCOPE, key,
                        "CHANNEL_CHANGE_REQUEST", Long.toString(reqId), payload);
            }
            return toChangeRequestResponse(after, user);
        }

        if (repository.updateChannelChangeRequestStatus(tenantId, reqId, before.version(),
                "PENDING_REVIEW", "REJECTED", user.userId(), now, comment) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "财务变更申请已被其他审批人处理");
        }
        ChannelChangeRequest after = requireChannelChangeRequest(tenantId, reqId);
        auditService.record(tenantId, user.userId(), "CHANNEL_FINANCE_CHANGE_REJECT", "PARTNER_CHANNEL",
                Long.toString(channel.id()), changeAuditSnapshot(before), changeAuditSnapshot(after), comment);
        if (key != null) {
            idempotencyService.recordSuccess(tenantId, CHANNEL_CHANGE_REVIEW_SCOPE, key,
                    "CHANNEL_CHANGE_REQUEST", Long.toString(reqId), payload);
        }
        return toChangeRequestResponse(after, user);
    }

    private ChannelFinancialChangeRequestResponse replayCreateFinancialChange(
            long tenantId, CurrentUser user, String key, String payload) {
        IdempotencyRecord replay = idempotencyService.lookup(tenantId, CHANNEL_CHANGE_CREATE_SCOPE, key, payload)
                .orElse(null);
        if (replay == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "该渠道已有待财务审批的变更申请");
        }
        return toChangeRequestResponse(requireChannelChangeRequest(tenantId, Long.parseLong(replay.getResourceId())), user);
    }

    private ChannelFinancialChangeRequestResponse replayReviewFinancialChange(
            long tenantId, CurrentUser user, long reqId, String key, String payload) {
        if (key != null) {
            IdempotencyRecord replay = idempotencyService.lookup(tenantId, CHANNEL_CHANGE_REVIEW_SCOPE, key, payload)
                    .orElse(null);
            if (replay != null) {
                return toChangeRequestResponse(requireChannelChangeRequest(tenantId, reqId), user);
            }
        }
        throw new BusinessException(ErrorCode.CONFLICT, "财务变更申请审批冲突，请刷新后重试");
    }

    @Transactional
    public OrderAttributionSnapshot resolveOrderAttribution(long tenantId, Long campaignId, Long batchId,
                                                            Long channelId, Instant orderedAt) {
        Long resolvedCampaignId = null;
        Long resolvedBatchId = null;
        String campaignCode = null;
        String campaignName = null;
        String batchCode = null;
        String batchName = null;
        if (campaignId != null || batchId != null) {
            if (campaignId == null || batchId == null) {
                throw invalidAttribution("活动与批次必须同时选择");
            }
            Campaign campaign = repository.findCampaignForUpdate(tenantId, campaignId)
                    .orElseThrow(() -> invalidAttribution("活动不存在或未启用"));
            if (!"ACTIVE".equals(campaign.status())) {
                throw invalidAttribution("活动不存在或未启用");
            }
            CampaignBatch batch = repository.findBatch(tenantId, campaignId, batchId)
                    .orElseThrow(() -> invalidAttribution("批次不存在或不属于所选活动"));
            if (!"ACTIVE".equals(batch.status())) {
                throw invalidAttribution("批次不存在或未启用");
            }
            validateEffectiveAt(campaign.startsAt(), campaign.endsAt(), orderedAt, "活动不在有效期内");
            validateEffectiveAt(batch.startsAt(), batch.endsAt(), orderedAt, "活动批次不在有效期内");
            resolvedCampaignId = campaign.id();
            resolvedBatchId = batch.id();
            campaignCode = campaign.campaignCode();
            campaignName = campaign.campaignName();
            batchCode = batch.batchCode();
            batchName = batch.batchName();
        }

        Long businessOwnerId = null;
        if (channelId != null) {
            PartnerChannel channel = repository.findChannel(tenantId, channelId)
                    .orElseThrow(() -> invalidAttribution("渠道不存在或未启用"));
            if (!"ACTIVE".equals(channel.status())) {
                throw invalidAttribution("渠道不存在或未启用");
            }
            requireRealOwnerForOrder(tenantId, channel.businessOwnerUserId());
            businessOwnerId = channel.businessOwnerUserId();
        }
        return new OrderAttributionSnapshot(resolvedCampaignId, resolvedBatchId,
                campaignCode, campaignName, batchCode, batchName, channelId, businessOwnerId);
    }

    private Campaign requireCampaign(long tenantId, long id) {
        return repository.findCampaign(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "活动不存在或不在数据范围内"));
    }

    private Campaign requireCampaignForUpdate(long tenantId, long id) {
        return repository.findCampaignForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "活动不存在或不在数据范围内"));
    }

    private Campaign requireCampaignByCode(long tenantId, String code) {
        return repository.findCampaignByCode(tenantId, code)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "活动不存在"));
    }

    private CampaignBatch requireBatch(long tenantId, long campaignId, long batchId) {
        return repository.findBatch(tenantId, campaignId, batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "活动批次不存在或归属不匹配"));
    }

    private PartnerChannel requireChannel(long tenantId, long id) {
        return repository.findChannel(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "渠道不存在或不在数据范围内"));
    }

    private PartnerChannel requireChannelForUpdate(long tenantId, long id) {
        return repository.findChannelForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "渠道不存在或不在数据范围内"));
    }

    private ChannelChangeRequest requireChannelChangeRequest(long tenantId, long id) {
        return repository.findChannelChangeRequest(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "渠道财务变更申请不存在"));
    }

    private void requireAccess(long tenantId, CurrentUser user, String permission) {
        if (user == null || user.tenantId() != tenantId || user.isInstitutionUser()
                || !user.permissions().contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该租户的活动或渠道数据");
        }
    }

    private void requireAnyAccess(long tenantId, CurrentUser user, String perm1, String perm2) {
        if (user == null || user.tenantId() != tenantId || user.isInstitutionUser()
                || (!user.permissions().contains(perm1) && !user.permissions().contains(perm2))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该租户的活动或渠道数据");
        }
    }

    private void requireRealOwner(long tenantId, Long ownerId) {
        if (ownerId != null && repository.countActiveEmployee(tenantId, ownerId) != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "渠道负责人必须是同租户启用中的内部员工");
        }
    }

    private void requireRealOwnerForOrder(long tenantId, Long ownerId) {
        if (ownerId != null && repository.countActiveEmployee(tenantId, ownerId) != 1) {
            throw invalidAttribution("渠道负责人不存在、已停用或不属于当前租户");
        }
    }

    private void validateTimeRange(Instant startsAt, Instant endsAt) {
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结束时间必须晚于开始时间");
        }
    }

    private void validateBatchWindow(Instant campaignStartsAt, Instant campaignEndsAt,
                                     Instant batchStartsAt, Instant batchEndsAt) {
        if (campaignStartsAt != null && batchStartsAt != null && batchStartsAt.isBefore(campaignStartsAt)
                || campaignEndsAt != null && batchEndsAt != null && batchEndsAt.isAfter(campaignEndsAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "批次时间必须位于活动时间范围内");
        }
    }

    private void validateEffectiveAt(Instant startsAt, Instant endsAt, Instant at, String message) {
        if (startsAt != null && at.isBefore(startsAt) || endsAt != null && !at.isBefore(endsAt)) {
            throw invalidAttribution(message);
        }
    }

    private void validateStatusTransition(String from, String to) {
        if (from.equals(to)) {
            return;
        }
        boolean allowed = switch (from) {
            case "DRAFT" -> "ACTIVE".equals(to) || "ARCHIVED".equals(to);
            case "ACTIVE" -> "INACTIVE".equals(to) || "ARCHIVED".equals(to);
            case "INACTIVE" -> "ACTIVE".equals(to) || "ARCHIVED".equals(to);
            default -> false;
        };
        if (!allowed) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前状态不允许该状态转换");
        }
    }

    private void requireMutable(String status, String label) {
        if ("ARCHIVED".equals(status)) {
            throw new BusinessException(ErrorCode.CONFLICT, label + "已归档，只允许查看");
        }
    }

    private void requireCodeUnchanged(String requested, String current, String message) {
        if (requested != null && !requested.strip().equals(current)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message);
        }
    }

    private BusinessException invalidAttribution(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private void validateChannelSettlement(String channelType, String settlementMethod) {
        String settlement = blankToNull(settlementMethod);
        if (settlement == null) {
            return;
        }
        boolean valid = switch (channelType) {
            case "BLOGGER" -> "MONTHLY".equals(settlement);
            case "DISTRIBUTION", "REFERRAL" ->
                    "MONTHLY".equals(settlement) || "TWICE_MONTHLY".equals(settlement);
            default -> false;
        };
        if (!valid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结算方式与当前合作类型不匹配，请重新选择");
        }
    }

    private void validateChannelProfile(String contactPhone, String idCardNumber, BigDecimal commissionPercent,
                                        LocalDate cooperationStartDate, LocalDate cooperationEndDate) {
        String phone = blankToNull(contactPhone);
        if (phone != null && !phone.matches("1[3-9]\\d{9}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "联系方式须为有效的11位中国大陆手机号");
        }
        if (commissionPercent != null && (commissionPercent.compareTo(BigDecimal.ZERO) < 0
                || commissionPercent.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分佣比例必须在0%到100%之间");
        }
        if (cooperationStartDate != null && cooperationEndDate != null
                && cooperationEndDate.isBefore(cooperationStartDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "合作结束时间不得早于合作开始时间");
        }
        validateIdCard(idCardNumber);
    }

    private void validateIdCard(String idCardNumber) {
        String value = blankToNull(idCardNumber);
        if (value == null) {
            return;
        }
        value = value.toUpperCase();
        if (!value.matches("\\d{17}[0-9X]")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "身份证号须为18位，末位可以是X");
        }
        try {
            LocalDate birthDate = LocalDate.parse(value.substring(6, 14), DateTimeFormatter.BASIC_ISO_DATE);
            if (birthDate.isAfter(LocalDate.now())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "身份证出生日期不得晚于当前日期");
            }
        } catch (java.time.DateTimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "身份证出生日期无效");
        }
        int sum = 0;
        for (int index = 0; index < ID_CARD_WEIGHTS.length; index++) {
            sum += Character.getNumericValue(value.charAt(index)) * ID_CARD_WEIGHTS[index];
        }
        if (ID_CARD_CHECK_CODES[sum % 11] != value.charAt(17)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "身份证校验码不正确，请核对后重新输入");
        }
    }

    private String normalizeIdCard(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private boolean isFinancialLocked(PartnerChannel channel) {
        return "ACTIVE".equals(channel.status())
                || channel.commissionPercent() != null
                || channel.settlementMethod() != null
                || channel.paymentAccountName() != null
                || channel.paymentAccountNo() != null
                || channel.paymentBankName() != null;
    }

    private PartnerChannel encryptSensitive(PartnerChannel channel) {
        return new PartnerChannel(channel.id(), channel.channelNo(), channel.name(), channel.channelType(),
                channel.platformCode(), channel.commissionPercent(), channel.businessOwnerUserId(),
                channel.businessOwnerName(), channel.realName(), cipher.encrypt(channel.contactPhone()),
                cipher.encrypt(channel.idCardNumber()), channel.settlementMethod(), channel.cooperationStartDate(),
                channel.cooperationEndDate(), cipher.encrypt(channel.paymentAccountName()),
                cipher.encrypt(channel.paymentAccountNo()), channel.paymentBankName(), channel.status(),
                channel.remarks(), channel.createdAt(), channel.version());
    }

    private PartnerChannelResponse toChannelResponse(PartnerChannel channel) {
        String phonePlain = cipher.decrypt(channel.contactPhone());
        String idCardPlain = cipher.decrypt(channel.idCardNumber());
        String accountNamePlain = cipher.decrypt(channel.paymentAccountName());
        String accountNoPlain = cipher.decrypt(channel.paymentAccountNo());
        boolean phoneConfigured = channel.contactPhone() != null;
        boolean idCardConfigured = channel.idCardNumber() != null;
        boolean paymentAccountConfigured = channel.paymentAccountName() != null || channel.paymentAccountNo() != null;
        return new PartnerChannelResponse(
                channel.id(), channel.channelNo(), channel.name(), channel.channelType(), channel.platformCode(),
                channel.commissionPercent(), channel.businessOwnerUserId(), channel.businessOwnerName(),
                channel.realName(), ChannelMasker.maskPhone(phonePlain), phoneConfigured,
                ChannelMasker.maskIdCard(idCardPlain), idCardConfigured, channel.settlementMethod(),
                channel.cooperationStartDate(), channel.cooperationEndDate(),
                ChannelMasker.maskAccountName(accountNamePlain), ChannelMasker.maskAccountNo(accountNoPlain),
                paymentAccountConfigured, channel.paymentBankName(), channel.status(), channel.remarks(),
                channel.createdAt(), channel.version());
    }

    private ChannelFinancialChangeRequestResponse toChangeRequestResponse(ChannelChangeRequest req, CurrentUser user) {
        boolean canApproveFinance = user.permissions().contains("channel:approve_finance");
        JsonNode beforeNode = parseJson(req.beforeMasked());
        JsonNode afterNode = parseJson(req.afterMasked());
        if (!canApproveFinance) {
            beforeNode = redactBankFields(beforeNode);
            afterNode = redactBankFields(afterNode);
        }
        BigDecimal commissionPercent = decimalOrNull(afterNode, "commissionPercent");
        String settlementMethod = textOrNull(afterNode, "settlementMethod");
        String paymentAccountName = canApproveFinance ? textOrNull(afterNode, "paymentAccountName") : null;
        String paymentAccountNo = canApproveFinance ? textOrNull(afterNode, "paymentAccountNo") : null;
        String paymentBankName = canApproveFinance ? textOrNull(afterNode, "paymentBankName") : null;
        return new ChannelFinancialChangeRequestResponse(
                req.id(), req.channelId(), req.channelNo(), req.channelName(), req.status(),
                req.requestedBy(), req.requestedByName(), req.requestedAt(), req.reason(),
                req.baseVersion(), req.version(), beforeNode, afterNode,
                commissionPercent, settlementMethod, paymentAccountName, paymentAccountNo, paymentBankName,
                req.reviewerUserId(), req.reviewerName(), req.reviewedAt(), req.reviewComment());
    }

    private JsonNode redactBankFields(JsonNode node) {
        if (!(node instanceof ObjectNode obj)) {
            return node;
        }
        ObjectNode copy = obj.deepCopy();
        if (copy.hasNonNull("paymentAccountName")) {
            copy.put("paymentAccountName", "***");
        }
        if (copy.hasNonNull("paymentBankName")) {
            copy.put("paymentBankName", "***");
        }
        if (copy.hasNonNull("paymentAccountNo")) {
            copy.put("paymentAccountNo", "****");
        }
        return copy;
    }

    private Map<String, Object> channelAuditSnapshot(PartnerChannel channel) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("channelNo", channel.channelNo());
        snapshot.put("name", channel.name());
        snapshot.put("channelType", channel.channelType());
        snapshot.put("platformCode", channel.platformCode());
        snapshot.put("commissionPercent", channel.commissionPercent());
        snapshot.put("businessOwnerUserId", channel.businessOwnerUserId());
        snapshot.put("settlementMethod", channel.settlementMethod());
        snapshot.put("cooperationStartDate", channel.cooperationStartDate());
        snapshot.put("cooperationEndDate", channel.cooperationEndDate());
        snapshot.put("status", channel.status());
        snapshot.put("remarks", channel.remarks());
        snapshot.put("version", channel.version());
        snapshot.put("contactPhoneConfigured", channel.contactPhone() != null);
        snapshot.put("idCardConfigured", channel.idCardNumber() != null);
        snapshot.put("paymentAccountConfigured",
                channel.paymentAccountNo() != null || channel.paymentAccountName() != null);
        return snapshot;
    }

    private Map<String, Object> changeAuditSnapshot(ChannelChangeRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", request.id());
        snapshot.put("channelId", request.channelId());
        snapshot.put("status", request.status());
        snapshot.put("baseVersion", request.baseVersion());
        snapshot.put("version", request.version());
        snapshot.put("beforeMasked", parseJson(request.beforeMasked()));
        snapshot.put("afterMasked", parseJson(request.afterMasked()));
        return snapshot;
    }

    private String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    private String jsonText(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("渠道数据序列化失败", ex);
        }
    }

    private JsonNode parseJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new IllegalStateException("渠道脱敏数据解析失败", ex);
        }
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).decimalValue();
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private void audit(long tenantId, CurrentUser user, String action, String targetType, long targetId,
                       Object before, Object after) {
        auditService.record(tenantId, user.userId(), action, targetType, Long.toString(targetId), before, after, null);
    }

    private record CreateFinancialChangeCanonical(long channelId, BigDecimal commissionPercent,
                                                  String settlementMethod, String paymentAccountName,
                                                  String paymentAccountNo, String paymentBankName,
                                                  long baseVersion, String reason) {
    }

    private record ReviewFinancialChangeCanonical(long reqId, String decision, long version, String comment) {
    }
}
