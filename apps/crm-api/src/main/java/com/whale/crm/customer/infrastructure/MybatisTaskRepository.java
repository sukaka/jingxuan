package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.CrmTask;
import com.whale.crm.customer.domain.TaskActionLog;
import com.whale.crm.customer.domain.TaskDutySummary;
import com.whale.crm.customer.domain.TaskRepository;
import com.whale.crm.customer.infrastructure.mapper.TaskMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisTaskRepository implements TaskRepository {

    private final TaskMapper taskMapper;

    public MybatisTaskRepository(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public List<CrmTask> search(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                Long assigneeId, String status, int offset, int size) {
        return taskMapper.search(tenantId, scopeType, teamIds, selfId, assigneeId, status, offset, size);
    }

    @Override
    public long countSearch(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                            Long assigneeId, String status) {
        return taskMapper.countSearch(tenantId, scopeType, teamIds, selfId, assigneeId, status);
    }

    @Override
    public TaskDutySummary dutySummary(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                       Instant now, Instant dayEndExclusive) {
        return taskMapper.dutySummary(tenantId, scopeType, teamIds, selfId, now, dayEndExclusive);
    }

    @Override
    public Optional<CrmTask> findById(long tenantId, long id) {
        return Optional.ofNullable(taskMapper.selectById(tenantId, id));
    }

    @Override
    public Optional<CrmTask> findByIdForUpdate(long tenantId, long id) {
        return Optional.ofNullable(taskMapper.selectByIdForUpdate(tenantId, id));
    }

    @Override
    public void insert(CrmTask task) {
        taskMapper.insertTask(task);
    }

    @Override
    public int update(CrmTask task) {
        return taskMapper.updateTask(task);
    }

    @Override
    public int applyAction(CrmTask task, String expectedStatus) {
        return taskMapper.applyAction(task, expectedStatus);
    }

    @Override
    public void insertAction(TaskActionLog actionLog) {
        taskMapper.insertAction(actionLog);
    }

    @Override
    public List<TaskActionLog> findActions(long tenantId, long taskId) {
        return taskMapper.selectActions(tenantId, taskId);
    }

    @Override
    public int reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId) {
        return taskMapper.reassignCustomer(tenantId, fromCustomerId, toCustomerId);
    }

    @Override
    public int reassignSpecific(long tenantId, List<Long> taskIds, long fromCustomerId, long toCustomerId) {
        return taskMapper.reassignSpecific(tenantId, taskIds, fromCustomerId, toCustomerId);
    }

    @Override
    public List<Long> findIdsByCustomerId(long tenantId, long customerId) {
        return taskMapper.findIdsByCustomerId(tenantId, customerId);
    }
}
