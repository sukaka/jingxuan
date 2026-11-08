package com.whale.crm.platform.user.infrastructure;

import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserListRow;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.user.domain.UserSearchFilter;
import com.whale.crm.platform.user.infrastructure.mapper.UserMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisUserRepository implements UserRepository {

    private final UserMapper userMapper;

    public MybatisUserRepository(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Optional<SysUser> findByUsernameForAuth(String username) {
        List<SysUser> rows = userMapper.selectByUsernameForAuth(username);
        if (rows.size() > 1) {
            throw new IllegalStateException("登录名跨租户重复，违反唯一性配置: " + username);
        }
        return rows.stream().findFirst();
    }

    @Override
    public Optional<SysUser> findByIdForAuth(long id) {
        return Optional.ofNullable(userMapper.selectByIdForAuth(id));
    }

    @Override
    public Optional<SysUser> findById(long tenantId, long id) {
        return Optional.ofNullable(userMapper.selectById(tenantId, id));
    }

    @Override
    public boolean existsByUsername(long tenantId, String username) {
        return userMapper.countByUsername(tenantId, username) > 0;
    }

    @Override
    public List<UserListRow> search(long tenantId, UserSearchFilter filter, int offset, int size) {
        return userMapper.search(tenantId, filter, offset, size);
    }

    @Override
    public long count(long tenantId, UserSearchFilter filter) {
        return userMapper.countSearch(tenantId, filter);
    }

    @Override
    public List<TeamOptionRow> listTeams(long tenantId) {
        return userMapper.selectTeams(tenantId);
    }

    @Override
    public List<StewardOptionRow> findStewardOptions(long tenantId, java.util.Collection<String> roleCodes,
                                                     Long teamIdEq, Long userIdEq) {
        return userMapper.selectStewardOptions(tenantId, roleCodes, teamIdEq, userIdEq);
    }

    @Override
    public Optional<Long> findDefaultOrgId(long tenantId) {
        return Optional.ofNullable(userMapper.selectDefaultOrgId(tenantId));
    }

    @Override
    public boolean teamExists(long tenantId, long teamId) {
        return userMapper.countTeam(tenantId, teamId) > 0;
    }

    @Override
    public void insert(SysUser user) {
        userMapper.insertUser(user);
    }

    @Override
    public int updateProfile(long tenantId, long id, long version, String displayName, Long teamId, String passwordHash) {
        return userMapper.updateProfile(tenantId, id, version, displayName, teamId, passwordHash);
    }

    @Override
    public int updateStatus(long tenantId, long id, String status) {
        return userMapper.updateStatus(tenantId, id, status);
    }

    @Override
    public void touchLastLogin(long id, Instant at) {
        userMapper.touchLastLogin(id, at);
    }
}
