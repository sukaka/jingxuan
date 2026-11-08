package com.whale.crm.platform.security;

import java.util.Set;

/**
 * 行级数据范围判定结果。
 * ALL 全租户；TEAM 本组及授权团队；SELF 仅本人；INSTITUTION 机构账号映射机构；NONE 无授权（拒绝）。
 */
public record DataScope(ScopeType type, Set<Long> teamIds, Set<Long> institutionIds) {
    public DataScope {
        teamIds = teamIds == null ? null : Set.copyOf(teamIds);
        institutionIds = institutionIds == null ? null : Set.copyOf(institutionIds);
    }

    public enum ScopeType {
        ALL, TEAM, SELF, INSTITUTION, NONE
    }

    public static DataScope all() {
        return new DataScope(ScopeType.ALL, Set.of(), Set.of());
    }

    public static DataScope team(Set<Long> teamIds) {
        return new DataScope(ScopeType.TEAM, Set.copyOf(teamIds), Set.of());
    }

    public static DataScope self() {
        return new DataScope(ScopeType.SELF, Set.of(), Set.of());
    }

    public static DataScope institution(Set<Long> institutionIds) {
        return new DataScope(ScopeType.INSTITUTION, Set.of(), Set.copyOf(institutionIds));
    }

    public static DataScope none() {
        return new DataScope(ScopeType.NONE, Set.of(), Set.of());
    }
}
