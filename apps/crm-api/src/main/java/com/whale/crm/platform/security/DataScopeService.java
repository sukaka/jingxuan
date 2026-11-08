package com.whale.crm.platform.security;

import com.whale.crm.platform.user.domain.DataScopeRepository;
import com.whale.crm.platform.user.domain.DataScopeRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据范围服务：供各领域 Application Service 在拼 SQL/校验前调用。
 * 语义（docs/01_需求与产品/12 第 2 节）：
 * - INSTITUTION 用户：只看 crm_institution_user 映射的本机构（忽略 sys_data_scope）。
 * - 其余用户：sys_data_scope 有效授权 ALL &gt; TEAM &gt; SELF；TEAM 范围 = 本人团队 + scope_ref_id 授权团队。
 * - 无任何授权返回 NONE，调用方必须按“不可见”处理。
 */
@Service
public class DataScopeService {

    public static final String RESOURCE_CUSTOMER = "customer";
    public static final String RESOURCE_INSTITUTION = "institution";

    private final DataScopeRepository dataScopeRepository;

    public DataScopeService(DataScopeRepository dataScopeRepository) {
        this.dataScopeRepository = dataScopeRepository;
    }

    @Transactional(readOnly = true)
    public DataScope scopeFor(CurrentUser user, String resource) {
        if (user.isInstitutionUser()) {
            return user.institutionIds().isEmpty() ? DataScope.none() : DataScope.institution(user.institutionIds());
        }
        List<DataScopeRow> rows = dataScopeRepository.findActiveScopes(user.tenantId(), user.userId(), resource);
        boolean hasAll = rows.stream().anyMatch(row -> "ALL".equals(row.getScopeType()));
        if (hasAll) {
            return DataScope.all();
        }
        Set<Long> teamIds = new LinkedHashSet<>();
        rows.stream()
                .filter(row -> "TEAM".equals(row.getScopeType()))
                .forEach(row -> {
                    if (row.getScopeRefId() != null) {
                        teamIds.add(row.getScopeRefId());
                    }
                });
        if (!teamIds.isEmpty() || rows.stream().anyMatch(row -> "TEAM".equals(row.getScopeType()))) {
            if (user.teamId() != null) {
                teamIds.add(user.teamId());
            }
            return teamIds.isEmpty() ? DataScope.none() : DataScope.team(teamIds);
        }
        boolean hasSelf = rows.stream().anyMatch(row -> "SELF".equals(row.getScopeType()));
        return hasSelf ? DataScope.self() : DataScope.none();
    }
}
