package com.whale.crm.reporting.infrastructure.mapper;

import com.whale.crm.reporting.domain.WecomAnalyticsRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Set;

@Mapper
public interface WecomAnalyticsMapper extends WecomAnalyticsRepository {
    @Override
    QuickReplyFacts quickReplyFacts(@Param("tenantId") long tenantId, @Param("fromUtc") Instant fromUtc, @Param("toUtcExclusive") Instant toUtcExclusive,
                                    @Param("scopeType") String scopeType, @Param("teamIds") Set<Long> teamIds,
                                    @Param("selfId") long selfId, @Param("employeeId") Long employeeId);

    @Override
    AiSuggestionFacts aiSuggestionFacts(@Param("tenantId") long tenantId, @Param("fromUtc") Instant fromUtc, @Param("toUtcExclusive") Instant toUtcExclusive,
                                       @Param("scopeType") String scopeType, @Param("teamIds") Set<Long> teamIds,
                                       @Param("selfId") long selfId, @Param("employeeId") Long employeeId);

    @Override
    AiUsageFacts aiUsageFacts(@Param("tenantId") long tenantId, @Param("fromUtc") Instant fromUtc, @Param("toUtcExclusive") Instant toUtcExclusive,
                              @Param("scopeType") String scopeType, @Param("teamIds") Set<Long> teamIds,
                              @Param("selfId") long selfId, @Param("employeeId") Long employeeId);

    @Override
    AppointmentFacts appointmentFacts(@Param("tenantId") long tenantId, @Param("fromUtc") Instant fromUtc, @Param("toUtcExclusive") Instant toUtcExclusive,
                                      @Param("scopeType") String scopeType, @Param("teamIds") Set<Long> teamIds,
                                      @Param("selfId") long selfId, @Param("employeeId") Long employeeId);

    @Override
    PaymentFacts paymentFacts(@Param("tenantId") long tenantId, @Param("fromUtc") Instant fromUtc, @Param("toUtcExclusive") Instant toUtcExclusive,
                              @Param("scopeType") String scopeType, @Param("teamIds") Set<Long> teamIds,
                              @Param("selfId") long selfId, @Param("employeeId") Long employeeId);
}
