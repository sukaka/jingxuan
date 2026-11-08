package com.whale.crm.platform.user.domain;

import java.util.List;
import java.util.Optional;

/**
 * sys_user 存取。除标注“认证引导”的方法外，所有查询必须显式 tenantId 过滤。
 */
public interface UserRepository {

    /**
     * 认证引导（登录）：按登录名查找。用户名在单租户内唯一；跨租户重复视为配置错误由实现抛出。
     * 此例外仅用于认证前身份定位，登录成功后一切数据访问均以用户行的 tenantId 过滤。
     */
    Optional<SysUser> findByUsernameForAuth(String username);

    /**
     * 认证引导（令牌校验）：按全局主键查找。随后所有查询继续使用用户行的 tenantId。
     */
    Optional<SysUser> findByIdForAuth(long id);

    Optional<SysUser> findById(long tenantId, long id);

    boolean existsByUsername(long tenantId, String username);

    Optional<Long> findDefaultOrgId(long tenantId);

    boolean teamExists(long tenantId, long teamId);

    List<UserListRow> search(long tenantId, UserSearchFilter filter, int offset, int size);

    long count(long tenantId, UserSearchFilter filter);

    List<TeamOptionRow> listTeams(long tenantId);

    record TeamOptionRow(Long id, String teamName) {
    }

    /**
     * 可选管家/主管选项（客户归属下拉）：ACTIVE 且 EMPLOYEE 且持有给定角色之一的用户。
     * teamIdEq/userIdEq 为收窄过滤（MANAGER_LEAD 传本团队、MANAGER 传本人、ADMIN 均传 null）。
     */
    List<StewardOptionRow> findStewardOptions(long tenantId, java.util.Collection<String> roleCodes,
                                              Long teamIdEq, Long userIdEq);

    /**
     * 管家选项投影（含团队名）。
     */
    record StewardOptionRow(Long id, String displayName, Long teamId, String teamName) {
    }

    void insert(SysUser user);

    /**
     * 乐观锁更新：WHERE tenant_id/id/version 全匹配才更新；返回受影响行数（0 = 冲突或不存在）。
     */
    int updateProfile(long tenantId, long id, long version, String displayName, Long teamId, String passwordHash);

    int updateStatus(long tenantId, long id, String status);

    void touchLastLogin(long id, java.time.Instant at);
}
