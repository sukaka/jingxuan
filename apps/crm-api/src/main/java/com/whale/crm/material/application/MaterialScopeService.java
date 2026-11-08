package com.whale.crm.material.application;

import com.whale.crm.material.domain.MaterialRevision;
import com.whale.crm.material.domain.MaterialScopeQuery;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MaterialScopeService {
    private final DataScopeService dataScopeService;

    public MaterialScopeService(DataScopeService dataScopeService) {
        this.dataScopeService = dataScopeService;
    }

    public MaterialScopeQuery query(CurrentUser user) {
        DataScope scope = dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION);
        String scopeType = switch (scope.type()) {
            case ALL -> "ALL";
            case INSTITUTION -> "INSTITUTION";
            default -> "NONE";
        };
        List<Long> institutionIds = "INSTITUTION".equals(scopeType)
                ? List.copyOf(scope.institutionIds()) : List.of();
        return new MaterialScopeQuery(scopeType, institutionIds, user.userId(),
                user.permissions().contains("material:read"),
                user.permissions().contains("material:write"),
                user.permissions().contains("material:review"));
    }

    public void requireRead(CurrentUser user) {
        if (!user.permissions().contains("material:read")
                && !user.permissions().contains("material:write")
                && !user.permissions().contains("material:review")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有素材查看权限");
        }
    }

    public void requireWrite(CurrentUser user, Long institutionId) {
        if (!user.permissions().contains("material:write")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有素材维护权限");
        }
        MaterialScopeQuery scope = query(user);
        if (institutionId == null) {
            if (!scope.isAll()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "只有全机构数据范围可维护全局素材");
            }
            return;
        }
        if (!scope.isAll() && !scope.institutionIds().contains(institutionId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "机构不在素材维护数据范围内");
        }
    }

    public void requireReview(CurrentUser user, Long institutionId) {
        if (!user.permissions().contains("material:review")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有素材审核权限");
        }
        MaterialScopeQuery scope = query(user);
        if (institutionId == null && !scope.isAll()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有全机构数据范围可审核全局素材");
        }
    }

    public void requireEditable(MaterialScopeQuery scope, MaterialRevision revision) {
        if (!scope.canWrite() || (!scope.isAll() && revision.createdBy() != scope.selfId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能维护本人素材修订");
        }
    }

    public void requireDownload(MaterialScopeQuery scope, MaterialRevision revision) {
        boolean globalAllowed = revision.institutionId() != null || scope.isAll();
        boolean allowed = switch (revision.status()) {
            case "APPROVED" -> globalAllowed && (scope.canRead() || scope.canWrite() || scope.canReview());
            case "PENDING" -> scope.canReview() && globalAllowed;
            case "DRAFT", "REJECTED" -> scope.canWrite() && globalAllowed
                    && (scope.isAll() || revision.createdBy() == scope.selfId());
            default -> false;
        };
        if (!allowed) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "素材修订不存在或不可见");
        }
    }
}
