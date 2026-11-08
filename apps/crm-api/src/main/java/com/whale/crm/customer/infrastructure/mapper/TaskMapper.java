package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.CrmTask;
import com.whale.crm.customer.domain.TaskActionLog;
import com.whale.crm.customer.domain.TaskDutySummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface TaskMapper {

    List<CrmTask> search(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                         @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                         @Param("assigneeId") Long assigneeId, @Param("status") String status,
                         @Param("offset") int offset, @Param("size") int size);

    long countSearch(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                     @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                     @Param("assigneeId") Long assigneeId, @Param("status") String status);

    TaskDutySummary dutySummary(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                                @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                                @Param("now") Instant now, @Param("dayEndExclusive") Instant dayEndExclusive);

    CrmTask selectById(@Param("tenantId") long tenantId, @Param("id") long id);

    CrmTask selectByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    int insertTask(CrmTask task);

    int updateTask(CrmTask task);

    int applyAction(@Param("task") CrmTask task, @Param("expectedStatus") String expectedStatus);

    int insertAction(TaskActionLog actionLog);

    List<TaskActionLog> selectActions(@Param("tenantId") long tenantId, @Param("taskId") long taskId);

    int reassignCustomer(@Param("tenantId") long tenantId, @Param("fromCustomerId") long fromCustomerId,
                         @Param("toCustomerId") long toCustomerId);

    @org.apache.ibatis.annotations.Update({"<script>UPDATE crm_task SET customer_id=#{toCustomerId} WHERE tenant_id=#{tenantId} AND customer_id=#{fromCustomerId} AND id IN <foreach item='id' collection='ids' open='(' close=')' separator=','>#{id}</foreach></script>"})
    int reassignSpecific(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids,
                         @Param("fromCustomerId") long fromCustomerId, @Param("toCustomerId") long toCustomerId);

    @org.apache.ibatis.annotations.Select("SELECT id FROM crm_task WHERE tenant_id=#{tenantId} AND customer_id=#{customerId} ORDER BY id")
    List<Long> findIdsByCustomerId(@Param("tenantId") long tenantId, @Param("customerId") long customerId);
}
