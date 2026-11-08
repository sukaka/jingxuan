package com.whale.crm.reporting.infrastructure.mapper;

import com.whale.crm.reporting.domain.ReportFact;
import com.whale.crm.reporting.domain.ReportQuery;
import com.whale.crm.reporting.domain.ReportingRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ReportingMapper extends ReportingRepository {
    @Override
    OperationsFactRow operations(@Param("query") ReportQuery query);

    @Override
    long countNewCustomers(@Param("query") ReportQuery query);

    @Override
    List<OperationsStaffFactRow> operationsStaff(@Param("query") ReportQuery query);

    @Override
    List<OperationsInstitutionFactRow> operationsInstitutions(@Param("query") ReportQuery query);

    @Override
    List<PerformanceFactRow> performance(@Param("query") ReportQuery query,
                                         @Param("performanceRole") String performanceRole);

    @Override
    List<DailyFactRow> dailyFacts(@Param("query") ReportQuery query,
                                  @Param("performanceRole") String performanceRole);

    @Override
    List<ApprovedTargetRow> approvedTargets(@Param("query") ReportQuery query,
                                            @Param("performanceRole") String performanceRole,
                                            @Param("periodKey") String periodKey,
                                            @Param("staffIds") List<Long> staffIds);

    @Override
    List<CampaignFactRow> campaigns(@Param("query") ReportQuery query);

    @Override
    CampaignFactRow campaignTotals(@Param("query") ReportQuery query);

    @Override
    List<SkuQualityFactRow> skuQuality(@Param("query") ReportQuery query);

    @Override
    long countSku(@Param("query") ReportQuery query);

    @Override
    Instant skuSourceWatermark(@Param("query") ReportQuery query);

    @Override
    List<ReportFact> operationsDrillDown(@Param("query") ReportQuery query,
                                         @Param("factType") String factType, @Param("limit") int limit);

    @Override
    List<ReportFact> performanceDrillDown(@Param("query") ReportQuery query,
                                          @Param("performanceRole") String performanceRole,
                                          @Param("factType") String factType, @Param("limit") int limit);

    @Override
    List<ReportFact> campaignDrillDown(@Param("query") ReportQuery query,
                                       @Param("factType") String factType, @Param("limit") int limit);

    @Override
    List<ReportFact> skuDrillDown(@Param("query") ReportQuery query,
                                  @Param("factType") String factType, @Param("limit") int limit);
}
