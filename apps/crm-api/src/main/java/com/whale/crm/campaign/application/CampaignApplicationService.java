package com.whale.crm.campaign.application;

import com.github.f4b6a3.ulid.UlidCreator;
import com.whale.crm.campaign.api.dto.CreateCampaignBatchRequest;
import com.whale.crm.campaign.api.dto.CreateCampaignRequest;
import com.whale.crm.campaign.api.dto.CreateChannelRequest;
import com.whale.crm.campaign.api.dto.UpdateCampaignBatchRequest;
import com.whale.crm.campaign.api.dto.UpdateCampaignRequest;
import com.whale.crm.campaign.api.dto.UpdateChannelRequest;
import com.whale.crm.campaign.domain.Campaign;
import com.whale.crm.campaign.domain.CampaignBatch;
import com.whale.crm.campaign.domain.CampaignRepository;
import com.whale.crm.campaign.domain.CampaignView;
import com.whale.crm.campaign.domain.ChannelOwnerOption;
import com.whale.crm.campaign.domain.OrderAttributionSnapshot;
import com.whale.crm.campaign.domain.PartnerChannel;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CampaignApplicationService {
    private static final int[] ID_CARD_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CARD_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private final CampaignRepository repository;
    private final AuditService auditService;
    private final AesGcmCipher cipher;

    public CampaignApplicationService(CampaignRepository repository, AuditService auditService, AesGcmCipher cipher) {
        this.repository = repository;
        this.auditService = auditService;
        this.cipher = cipher;
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
    public PageResponse<PartnerChannel> channels(long tenantId, CurrentUser user, String keyword, String status,
                                                 String channelType, Long businessOwnerUserId, int page, int size) {
        requireAccess(tenantId, user, "channel:read");
        String normalizedKeyword = blankToNull(keyword);
        String normalizedStatus = blankToNull(status);
        String normalizedType = blankToNull(channelType);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchChannels(tenantId, normalizedKeyword, normalizedStatus,
                        normalizedType, businessOwnerUserId, offset, size).stream().map(this::decryptSensitive).toList(),
                repository.countChannels(tenantId, normalizedKeyword, normalizedStatus, normalizedType,
                        businessOwnerUserId), page, size);
    }

    @Transactional(readOnly = true)
    public java.util.List<ChannelOwnerOption> channelOwnerOptions(long tenantId, CurrentUser user) {
        requireAccess(tenantId, user, "channel:read");
        return repository.listActiveChannelOwners(tenantId);
    }

    @Transactional
    public PartnerChannel createChannel(long tenantId, CurrentUser user, CreateChannelRequest request) {
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
        validateChannelProfile(request.contactPhone(), request.idCardNumber(), request.commissionPercent(),
                request.cooperationStartDate(), request.cooperationEndDate());
        validateChannelSettlement(channelType, request.settlementMethod());
        String status = request.status() == null ? "ACTIVE" : request.status();
        PartnerChannel input = new PartnerChannel(null, channelNo, request.name().strip(), channelType,
                blankToNull(request.platformCode()), request.commissionPercent(), request.businessOwnerUserId(), null,
                blankToNull(request.realName()), blankToNull(request.contactPhone()), normalizeIdCard(request.idCardNumber()),
                blankToNull(request.settlementMethod()), request.cooperationStartDate(), request.cooperationEndDate(),
                blankToNull(request.paymentAccountName()), blankToNull(request.paymentAccountNo()),
                blankToNull(request.paymentBankName()), status, blankToNull(request.remarks()), null, null);
        try {
            repository.insertChannel(tenantId, encryptSensitive(input));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "渠道编号已存在");
        }
        PartnerChannel created = decryptSensitive(repository.findChannelByNo(tenantId, channelNo).orElseThrow());
        auditService.record(tenantId, user.userId(), "CHANNEL_CREATE", "PARTNER_CHANNEL", created.id().toString(),
                null, channelAuditSnapshot(created), null);
        return created;
    }

    @Transactional
    public PartnerChannel updateChannel(long tenantId, CurrentUser user, long id, UpdateChannelRequest request) {
        requireAccess(tenantId, user, "channel:write");
        PartnerChannel before = requireChannel(tenantId, id);
        requireMutable(before.status(), "渠道");
        requireCodeUnchanged(request.channelNo(), before.channelNo(), "渠道编号不可修改");
        requireRealOwner(tenantId, request.businessOwnerUserId());
        validateStatusTransition(before.status(), request.status());
        validateChannelProfile(request.contactPhone(), request.idCardNumber(), request.commissionPercent(),
                request.cooperationStartDate(), request.cooperationEndDate());
        String channelType = blankToNull(request.channelType()) == null ? before.channelType() : request.channelType();
        validateChannelSettlement(channelType, request.settlementMethod());
        PartnerChannel input = new PartnerChannel(id, before.channelNo(), request.name().strip(), channelType,
                blankToNull(request.platformCode()), request.commissionPercent(), request.businessOwnerUserId(), null,
                blankToNull(request.realName()), blankToNull(request.contactPhone()), normalizeIdCard(request.idCardNumber()),
                blankToNull(request.settlementMethod()), request.cooperationStartDate(), request.cooperationEndDate(),
                blankToNull(request.paymentAccountName()), blankToNull(request.paymentAccountNo()),
                blankToNull(request.paymentBankName()), request.status(), blankToNull(request.remarks()),
                before.createdAt(), request.version());
        if (repository.updateChannel(tenantId, encryptSensitive(input)) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "渠道已被他人修改，请刷新后重试");
        }
        PartnerChannel after = decryptSensitive(requireChannel(tenantId, id));
        auditService.record(tenantId, user.userId(), "CHANNEL_UPDATE", "PARTNER_CHANNEL", Long.toString(id),
                channelAuditSnapshot(before), channelAuditSnapshot(after), null);
        return after;
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

    private void requireAccess(long tenantId, CurrentUser user, String permission) {
        if (user == null || user.tenantId() != tenantId || user.isInstitutionUser()
                || !user.permissions().contains(permission)) {
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
            case "DISTRIBUTION" -> "MONTHLY".equals(settlement) || "TWICE_MONTHLY".equals(settlement);
            case "REFERRAL" -> false;
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

    private PartnerChannel encryptSensitive(PartnerChannel channel) {
        return new PartnerChannel(channel.id(), channel.channelNo(), channel.name(), channel.channelType(), channel.platformCode(),
                channel.commissionPercent(), channel.businessOwnerUserId(), channel.businessOwnerName(), channel.realName(),
                cipher.encrypt(channel.contactPhone()), cipher.encrypt(channel.idCardNumber()), channel.settlementMethod(),
                channel.cooperationStartDate(), channel.cooperationEndDate(), cipher.encrypt(channel.paymentAccountName()),
                cipher.encrypt(channel.paymentAccountNo()), channel.paymentBankName(), channel.status(), channel.remarks(),
                channel.createdAt(), channel.version());
    }

    private PartnerChannel decryptSensitive(PartnerChannel channel) {
        return new PartnerChannel(channel.id(), channel.channelNo(), channel.name(), channel.channelType(), channel.platformCode(),
                channel.commissionPercent(), channel.businessOwnerUserId(), channel.businessOwnerName(), channel.realName(),
                cipher.decrypt(channel.contactPhone()), cipher.decrypt(channel.idCardNumber()), channel.settlementMethod(),
                channel.cooperationStartDate(), channel.cooperationEndDate(), cipher.decrypt(channel.paymentAccountName()),
                cipher.decrypt(channel.paymentAccountNo()), channel.paymentBankName(), channel.status(), channel.remarks(),
                channel.createdAt(), channel.version());
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
        snapshot.put("paymentAccountConfigured", channel.paymentAccountNo() != null);
        return snapshot;
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
}
