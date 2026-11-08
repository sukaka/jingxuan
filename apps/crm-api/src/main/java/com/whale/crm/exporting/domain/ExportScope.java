package com.whale.crm.exporting.domain;

import com.whale.crm.platform.security.DataScope;

import java.util.Set;
import java.util.TreeSet;

public record ExportScope(Kind kind, Set<Long> teamIds, long selfId, Set<Long> institutionIds) {
    public ExportScope {
        teamIds = sorted(teamIds);
        institutionIds = sorted(institutionIds);
    }

    @Override
    public Set<Long> teamIds() {
        return Set.copyOf(teamIds);
    }

    @Override
    public Set<Long> institutionIds() {
        return Set.copyOf(institutionIds);
    }

    public static ExportScope from(DataScope scope, long selfId) {
        return new ExportScope(Kind.valueOf(scope.type().name()), scope.teamIds(), selfId, scope.institutionIds());
    }

    public ExportScope intersect(ExportScope other) {
        if (selfId != other.selfId || kind == Kind.NONE || other.kind == Kind.NONE) {
            return none(selfId);
        }
        if (kind == Kind.INSTITUTION || other.kind == Kind.INSTITUTION) {
            if (kind == Kind.INSTITUTION && other.kind == Kind.INSTITUTION) {
                return new ExportScope(Kind.INSTITUTION, Set.of(), selfId, intersection(institutionIds, other.institutionIds));
            }
            if (kind == Kind.INSTITUTION && other.kind == Kind.ALL) {
                return this;
            }
            if (other.kind == Kind.INSTITUTION && kind == Kind.ALL) {
                return other;
            }
            return none(selfId);
        }
        if (kind == Kind.ALL) {
            return other;
        }
        if (other.kind == Kind.ALL) {
            return this;
        }
        if (kind == Kind.SELF || other.kind == Kind.SELF) {
            return new ExportScope(Kind.SELF, Set.of(), selfId, Set.of());
        }
        return new ExportScope(Kind.TEAM, intersection(teamIds, other.teamIds), selfId, Set.of());
    }

    public static ExportScope none(long selfId) {
        return new ExportScope(Kind.NONE, Set.of(), selfId, Set.of());
    }

    private static Set<Long> intersection(Set<Long> left, Set<Long> right) {
        Set<Long> result = new TreeSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static Set<Long> sorted(Set<Long> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(new TreeSet<>(values));
    }

    public enum Kind {
        ALL,
        TEAM,
        SELF,
        INSTITUTION,
        NONE
    }
}
