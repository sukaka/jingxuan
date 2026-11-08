package com.whale.crm.risk.infrastructure.mapper;

import com.whale.crm.risk.domain.RiskCase;
import com.whale.crm.risk.domain.RiskCaseHistory;
import com.whale.crm.risk.domain.RiskCaseQuery;
import com.whale.crm.risk.domain.RiskCaseRepository;
import com.whale.crm.risk.domain.RiskCustomerLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface RiskCaseMapper extends RiskCaseRepository {
    @Override
    Optional<RiskCase> findById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<RiskCase> findByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<RiskCase> findBySource(@Param("tenantId") long tenantId, @Param("sourceType") String sourceType,
                                    @Param("sourceRef") String sourceRef, @Param("riskType") String riskType);

    @Override
    Optional<RiskCase> findBySourceForUpdate(@Param("tenantId") long tenantId,
                                             @Param("sourceType") String sourceType,
                                             @Param("sourceRef") String sourceRef,
                                             @Param("riskType") String riskType);

    @Override
    List<RiskCase> search(@Param("q") RiskCaseQuery query);

    @Override
    long countSearch(@Param("q") RiskCaseQuery query);

    @Override
    List<RiskCustomerLink> findCustomerLinks(@Param("tenantId") long tenantId,
                                             @Param("riskCaseIds") List<Long> riskCaseIds);

    @Override
    void insert(@Param("e") RiskCase riskCase);

    @Override
    int updateStatus(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                     @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                     @Param("ownerUserId") Long ownerUserId, @Param("resolution") String resolution,
                     @Param("resolvedAt") Instant resolvedAt);

    @Override
    void insertHistory(@Param("e") RiskCaseHistory history);

    @Override
    List<RiskCaseHistory> findHistory(@Param("tenantId") long tenantId, @Param("riskCaseId") long riskCaseId);
}
