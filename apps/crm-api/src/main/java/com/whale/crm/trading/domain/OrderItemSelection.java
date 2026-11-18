package com.whale.crm.trading.domain;

public record OrderItemSelection(Long orderItemId, Long packageVersionId, String groupCode, String groupName,
                                 Integer minSelections, Integer maxSelections, Long projectId,
                                 String projectCodeSnapshot, String projectNameSnapshot, Integer grantCount,
                                 Long referencePriceFen, Integer sortOrder) {
    public OrderItemSelection withOrderItemId(long id) {
        return new OrderItemSelection(id, packageVersionId, groupCode, groupName, minSelections, maxSelections,
                projectId, projectCodeSnapshot, projectNameSnapshot, grantCount, referencePriceFen, sortOrder);
    }
}
