package com.whale.crm.customer.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * crm_task 存取。列表范围：ALL 全部；TEAM 任务团队在可见团队或本人；SELF 本人或本组未分配任务池。
 */
public interface TaskRepository {

    List<CrmTask> search(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                         Long assigneeId, String status, int offset, int size);

    long countSearch(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                     Long assigneeId, String status);

    TaskDutySummary dutySummary(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                Instant now, Instant dayEndExclusive);

    Optional<CrmTask> findById(long tenantId, long id);

    Optional<CrmTask> findByIdForUpdate(long tenantId, long id);

    void insert(CrmTask task);

    Optional<CrmTask> findBySource(long tenantId, String sourceType, String sourceRef);

    int insertAutomatic(CrmTask task);

    int updateAutomaticDueAt(long tenantId, long taskId, Instant dueAt);

    /**
     * 通用字段乐观锁更新；不更新状态、负责人或动作时间。
     */
    int update(CrmTask task);

    int applyAction(CrmTask task, String expectedStatus);

    void insertAction(TaskActionLog actionLog);

    List<TaskActionLog> findActions(long tenantId, long taskId);

    int reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId);

    int reassignSpecific(long tenantId, List<Long> taskIds, long fromCustomerId, long toCustomerId);

    List<Long> findIdsByCustomerId(long tenantId, long customerId);
}
