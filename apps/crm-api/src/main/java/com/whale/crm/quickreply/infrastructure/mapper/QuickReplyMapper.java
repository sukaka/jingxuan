package com.whale.crm.quickreply.infrastructure.mapper;

import com.whale.crm.quickreply.domain.QuickReplyRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuickReplyMapper extends QuickReplyRepository {
    @Override
    java.util.List<com.whale.crm.quickreply.domain.QuickReplyCategory> findCategories(@Param("tenantId") long tenantId);
    @Override
    java.util.Optional<com.whale.crm.quickreply.domain.QuickReplyCategory> findCategoryById(@Param("tenantId") long tenantId, @Param("id") long id);
    @Override
    java.util.Optional<com.whale.crm.quickreply.domain.QuickReplyCategory> findCategoryByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    @Override
    java.util.Optional<com.whale.crm.quickreply.domain.QuickReplyCategory> findCategoryByCode(@Param("tenantId") long tenantId, @Param("code") String code);
    @Override
    void insertCategory(@Param("e") com.whale.crm.quickreply.domain.QuickReplyCategory category);
    @Override
    int updateCategory(@Param("e") com.whale.crm.quickreply.domain.QuickReplyCategory category);
    @Override
    int disableCategory(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version);
    @Override
    java.util.List<com.whale.crm.quickreply.domain.QuickReplyTemplate> findTemplates(@Param("tenantId") long tenantId);
    @Override
    java.util.Optional<com.whale.crm.quickreply.domain.QuickReplyTemplate> findTemplateById(@Param("tenantId") long tenantId, @Param("id") long id);
    @Override
    java.util.Optional<com.whale.crm.quickreply.domain.QuickReplyTemplate> findTemplateByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    @Override
    java.util.Optional<com.whale.crm.quickreply.domain.QuickReplyTemplate> findTemplateByCode(@Param("tenantId") long tenantId, @Param("code") String code);
    @Override
    void insertTemplate(@Param("e") com.whale.crm.quickreply.domain.QuickReplyTemplate template);
    @Override
    int updateTemplate(@Param("e") com.whale.crm.quickreply.domain.QuickReplyTemplate template);
    @Override
    int disableTemplate(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version);
    @Override
    java.util.List<com.whale.crm.quickreply.domain.QuickReplyVariable> findVariables();
    @Override
    void insertUsage(@Param("e") com.whale.crm.quickreply.domain.QuickReplyUsage usage);
    @Override
    java.util.Optional<com.whale.crm.quickreply.domain.QuickReplyUsage> findUsageByKey(@Param("tenantId") long tenantId,
            @Param("idempotencyKey") String idempotencyKey);
    @Override
    java.util.List<com.whale.crm.quickreply.domain.QuickReplyUsage> findUsages(@Param("tenantId") long tenantId,
            @Param("templateId") Long templateId, @Param("employeeId") Long employeeId,
            @Param("offset") int offset, @Param("size") int size);
    @Override
    long countUsages(@Param("tenantId") long tenantId, @Param("templateId") Long templateId,
                     @Param("employeeId") Long employeeId);
    @Override
    java.util.List<Long> findFavoriteTemplateIds(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId);
    @Override
    void insertFavorite(@Param("tenantId") long tenantId, @Param("templateId") long templateId,
                        @Param("employeeId") long employeeId);
    @Override
    int deleteFavorite(@Param("tenantId") long tenantId, @Param("templateId") long templateId,
                       @Param("employeeId") long employeeId);
    @Override
    java.util.List<com.whale.crm.quickreply.domain.QuickReplyUsageStat> aggregateUsageStats(
            @Param("tenantId") long tenantId, @Param("employeeId") Long employeeId,
            @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);
}
