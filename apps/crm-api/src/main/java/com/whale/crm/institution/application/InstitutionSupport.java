package com.whale.crm.institution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.institution.api.dto.ChangeRequestResponse;
import com.whale.crm.institution.api.dto.InstitutionProfileResponse;
import com.whale.crm.institution.api.dto.QualificationResponse;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionQualification;
import com.whale.crm.institution.domain.InstitutionScope;
import com.whale.crm.institution.domain.ProfileChangeRequest;
import com.whale.crm.institution.domain.ProfileChangeStep;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class InstitutionSupport {
    private final DataScopeService dataScopeService;
    private final InstitutionAccessSupport institutionAccess;
    private final AesGcmCipher cipher;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final ObjectProvider<InstitutionFileOperations> fileOperations;

    public InstitutionSupport(DataScopeService dataScopeService, InstitutionAccessSupport institutionAccess,
                              AesGcmCipher cipher, ObjectMapper objectMapper,
                              IdempotencyService idempotencyService,
                              ObjectProvider<InstitutionFileOperations> fileOperations) {
        this.dataScopeService = dataScopeService;
        this.institutionAccess = institutionAccess;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.idempotencyService = idempotencyService;
        this.fileOperations = fileOperations;
    }

    public InstitutionScope scope(long tenantId, CurrentUser user) {
        if (user.isInstitutionUser()) {
            return InstitutionScope.mapped(institutionAccess.visibleInstitutionIds(tenantId, user));
        }
        DataScope scope = dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION);
        return scope.type() == DataScope.ScopeType.ALL ? InstitutionScope.all() : InstitutionScope.none();
    }

    public void requireVisible(long tenantId, InstitutionProfile profile, CurrentUser user) {
        if (user.isInstitutionUser()) {
            if (!institutionAccess.canAccess(tenantId, user, profile.id())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "机构不存在或不在本机构范围内");
            }
            return;
        }
        if (scope(tenantId, user).scopeType().equals("NONE")) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "机构不存在或不在授权范围内");
        }
    }

    public void requirePermission(CurrentUser user, String permission) {
        if (!user.permissions().contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号没有所需机构权限");
        }
    }

    public void requireEmployeeWrite(long tenantId, CurrentUser user) {
        if (user.isInstitutionUser()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号不能直接维护正式机构资料");
        }
        requirePermission(user, "institution:write");
        if (!"ALL".equals(scope(tenantId, user).scopeType())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "没有机构创建或维护范围");
        }
    }

    public void requireChange(long tenantId, InstitutionProfile profile, CurrentUser user) {
        requireVisible(tenantId, profile, user);
        requirePermission(user, "institution:change_request");
    }

    public InstitutionProfileResponse response(InstitutionProfile profile, CurrentUser user) {
        boolean managerOnly = (user.roles().contains("MANAGER") || user.roles().contains("MANAGER_LEAD"))
                && !user.roles().contains("ADMIN");
        boolean finance = user.permissions().contains("institution:approve_finance");
        return new InstitutionProfileResponse(profile.id(), profile.institutionNo(), profile.fullName(),
                profile.shortName(), profile.cityCode(), profile.businessTypeCode(), profile.ownershipCode(),
                profile.gradeCode(), profile.address(), profile.contactName(), mask(profile.contactPhoneCipher()),
                profile.status(), managerOnly ? null : profile.settlementType(),
                managerOnly ? null : profile.onlineRate(), managerOnly ? null : profile.offlineRate(),
                managerOnly ? null : profile.platformRate(), managerOnly ? null : profile.otherRateRule(),
                managerOnly ? null : profile.chargesFee(), managerOnly ? null : profile.depositFen(),
                managerOnly ? null : profile.guaranteeRoi(),
                managerOnly ? null : profile.roiTerms(), finance ? profile.settlementName() : null,
                finance && profile.bankNameCipher() != null ? "***" : null,
                finance ? mask(profile.bankAccountCipher()) : null,
                finance ? mask(profile.taxIdCipher()) : null,
                profile.introduction(), profile.createdAt(), profile.version());
    }

    public QualificationResponse response(InstitutionQualification qualification) {
        return new QualificationResponse(qualification.id(), qualification.institutionId(),
                qualification.qualificationType(), qualification.title(), mask(qualification.licenseNoCipher()),
                qualification.fileId(), qualification.validFrom(), qualification.validTo(), qualification.status(),
                qualification.createdBy(), qualification.reviewedBy(), qualification.reviewedAt(),
                qualification.createdAt(), qualification.version());
    }

    public ChangeRequestResponse response(ProfileChangeRequest request, List<ProfileChangeStep> steps) {
        return new ChangeRequestResponse(request.id(), request.institutionId(), request.changeType(),
                request.fieldCode(), request.targetId(), request.baseVersion(), request.targetVersion(),
                json(request.beforeMasked()), json(request.afterMasked()), request.reason(), request.evidenceFileId(),
                request.status(), request.currentStep(), request.requestedBy(), request.requestedAt(),
                request.completedAt(), request.version(), steps);
    }

    public Map<String, Object> auditChange(ProfileChangeRequest request) {
        return Map.of("id", request.id(), "changeType", request.changeType(), "status", request.status(),
                "currentStep", request.currentStep(), "beforeMasked", json(request.beforeMasked()),
                "afterMasked", json(request.afterMasked()));
    }

    public String encrypt(String value) {
        return value == null ? null : cipher.encrypt(value);
    }

    public String decrypt(String value) {
        return value == null ? null : cipher.decrypt(value);
    }

    public String mask(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        String plain = decrypt(cipherText);
        if (plain.length() <= 4) {
            return "****";
        }
        return "****" + plain.substring(plain.length() - 4);
    }

    public String maskValue(String fieldCode, Object value) {
        if (value == null) {
            return null;
        }
        if (List.of("bankAccount", "taxId", "bankName", "settlementName").contains(fieldCode)) {
            String text = String.valueOf(value);
            return text.length() <= 4 ? "****" : "****" + text.substring(text.length() - 4);
        }
        return String.valueOf(value);
    }

    public String jsonText(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("机构数据序列化失败", ex);
        }
    }

    public JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new IllegalStateException("机构脱敏数据解析失败", ex);
        }
    }

    public String requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    public IdempotencyRecord lookup(long tenantId, String scope, String key, String payload) {
        return idempotencyService.lookup(tenantId, scope, key, payload).orElse(null);
    }

    public void record(long tenantId, String scope, String key, String type, long id, String payload) {
        idempotencyService.recordSuccess(tenantId, scope, key, type, Long.toString(id), payload);
    }

    public long resourceId(IdempotencyRecord record) {
        return Long.parseLong(record.getResourceId());
    }

    public void requireClean(long tenantId, Long fileId) {
        if (fileId == null) {
            return;
        }
        InstitutionFileOperations operations = fileOperations.getIfAvailable();
        if (operations == null) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "文件扫描服务尚未就绪，不能审核该文件");
        }
        operations.assertClean(tenantId, fileId);
    }

    public void requireMerchantResourceClean(long tenantId, Long fileId, long institutionId) {
        if (fileId == null) {
            return;
        }
        InstitutionFileOperations operations = fileOperations.getIfAvailable();
        if (operations == null) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "文件扫描服务尚未就绪，不能引用该附件");
        }
        operations.assertMerchantResourceClean(tenantId, fileId, institutionId);
    }
}
