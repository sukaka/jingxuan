package com.whale.crm.institution.domain;

import java.util.List;

public record InstitutionScope(String scopeType, List<Long> institutionIds) {
    public InstitutionScope {
        institutionIds = institutionIds == null ? null : List.copyOf(institutionIds);
    }

    public static InstitutionScope all() {
        return new InstitutionScope("ALL", null);
    }

    public static InstitutionScope none() {
        return new InstitutionScope("NONE", List.of());
    }

    public static InstitutionScope mapped(List<Long> ids) {
        return new InstitutionScope("INSTITUTION", List.copyOf(ids));
    }
}
