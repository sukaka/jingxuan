package com.whale.crm.product.domain;

public record PackageProject(Long id, Long packageVersionId, Long projectId, String projectCode,
                             String projectName, String categoryCode, Long listPriceFen, String projectStatus,
                             Integer grantCount, Integer selectionWeight, Long allocationPriceFen,
                             String optionGroup, Long version) {
}
