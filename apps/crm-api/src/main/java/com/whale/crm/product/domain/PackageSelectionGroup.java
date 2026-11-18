package com.whale.crm.product.domain;

public record PackageSelectionGroup(Long id, Long packageVersionId, String groupCode, String groupName,
                                    Integer minSelections, Integer maxSelections, Integer sortOrder, Long version) {
}
