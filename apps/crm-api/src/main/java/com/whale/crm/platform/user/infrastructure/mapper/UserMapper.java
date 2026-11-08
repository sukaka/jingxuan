package com.whale.crm.platform.user.infrastructure.mapper;

import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserListRow;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.user.domain.UserSearchFilter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface UserMapper {

    List<SysUser> selectByUsernameForAuth(@Param("username") String username);

    SysUser selectByIdForAuth(@Param("id") long id);

    SysUser selectById(@Param("tenantId") long tenantId, @Param("id") long id);

    int countByUsername(@Param("tenantId") long tenantId, @Param("username") String username);

    List<UserListRow> search(@Param("tenantId") long tenantId, @Param("filter") UserSearchFilter filter,
                             @Param("offset") int offset, @Param("size") int size);

    List<UserRepository.StewardOptionRow> selectStewardOptions(@Param("tenantId") long tenantId,
                                                               @Param("roleCodes") java.util.Collection<String> roleCodes,
                                                               @Param("teamIdEq") Long teamIdEq,
                                                               @Param("userIdEq") Long userIdEq);

    long countSearch(@Param("tenantId") long tenantId, @Param("filter") UserSearchFilter filter);

    List<UserRepository.TeamOptionRow> selectTeams(@Param("tenantId") long tenantId);

    Long selectDefaultOrgId(@Param("tenantId") long tenantId);

    int countTeam(@Param("tenantId") long tenantId, @Param("teamId") long teamId);

    int insertUser(SysUser user);

    int updateProfile(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                      @Param("displayName") String displayName, @Param("teamId") Long teamId,
                      @Param("passwordHash") String passwordHash);

    int updateStatus(@Param("tenantId") long tenantId, @Param("id") long id, @Param("status") String status);

    int touchLastLogin(@Param("id") long id, @Param("at") Instant at);
}
