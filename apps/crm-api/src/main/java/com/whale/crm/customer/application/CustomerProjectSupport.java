package com.whale.crm.customer.application;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.Project;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Component
public class CustomerProjectSupport {
    private final CatalogApplicationService catalogService;

    public CustomerProjectSupport(CatalogApplicationService catalogService) {
        this.catalogService = catalogService;
    }

    public void requireExisting(long tenantId, Collection<Long> projectIds) {
        Set<Long> required = new HashSet<>(projectIds);
        if (required.isEmpty()) {
            return;
        }
        Set<Long> found = new HashSet<>();
        int page = 1;
        while (found.size() < required.size()) {
            var result = catalogService.projects(tenantId, null, null, page, 100);
            for (Project project : result.items()) {
                if (required.contains(project.id())) {
                    found.add(project.id());
                }
            }
            if (result.items().isEmpty() || (long) page * result.size() >= result.total()) {
                break;
            }
            page++;
        }
        required.removeAll(found);
        if (!required.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "子项目不存在或不属于当前租户: " + required);
        }
    }
}
