package com.whale.crm.quickreply.domain;

import java.util.List;
import java.util.Optional;

public interface QuickReplyRepository {
    List<QuickReplyCategory> findCategories(long tenantId);
    Optional<QuickReplyCategory> findCategoryById(long tenantId, long id);
    Optional<QuickReplyCategory> findCategoryByIdForUpdate(long tenantId, long id);
    Optional<QuickReplyCategory> findCategoryByCode(long tenantId, String code);
    void insertCategory(QuickReplyCategory category);
    int updateCategory(QuickReplyCategory category);
    int disableCategory(long tenantId, long id, long version);

    List<QuickReplyTemplate> findTemplates(long tenantId);
    Optional<QuickReplyTemplate> findTemplateById(long tenantId, long id);
    Optional<QuickReplyTemplate> findTemplateByIdForUpdate(long tenantId, long id);
    Optional<QuickReplyTemplate> findTemplateByCode(long tenantId, String code);
    void insertTemplate(QuickReplyTemplate template);
    int updateTemplate(QuickReplyTemplate template);
    int disableTemplate(long tenantId, long id, long version);

    List<QuickReplyVariable> findVariables();

    void insertUsage(QuickReplyUsage usage);
    Optional<QuickReplyUsage> findUsageByKey(long tenantId, String idempotencyKey);
    List<QuickReplyUsage> findUsages(long tenantId, Long templateId, Long employeeId, int offset, int size);
    long countUsages(long tenantId, Long templateId, Long employeeId);

    List<Long> findFavoriteTemplateIds(long tenantId, long employeeId);
    void insertFavorite(long tenantId, long templateId, long employeeId);
    int deleteFavorite(long tenantId, long templateId, long employeeId);
    List<QuickReplyUsageStat> aggregateUsageStats(long tenantId, Long employeeId,
                                                  java.time.Instant from, java.time.Instant to);
}
