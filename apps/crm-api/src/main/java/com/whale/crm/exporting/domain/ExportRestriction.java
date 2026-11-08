package com.whale.crm.exporting.domain;

import java.util.List;
import java.util.TreeSet;

public record ExportRestriction(List<Long> teamIds, Long ownerId, List<Long> institutionIds) {
    public ExportRestriction {
        teamIds = sorted(teamIds);
        institutionIds = sorted(institutionIds);
    }

    @Override
    public List<Long> teamIds() {
        return teamIds == null ? null : List.copyOf(teamIds);
    }

    @Override
    public List<Long> institutionIds() {
        return institutionIds == null ? null : List.copyOf(institutionIds);
    }

    public static ExportRestriction none() {
        return new ExportRestriction(null, null, null);
    }

    private static List<Long> sorted(List<Long> values) {
        if (values == null) {
            return null;
        }
        return List.copyOf(new TreeSet<>(values));
    }
}
