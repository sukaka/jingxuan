package com.whale.crm.customer.batchtag.domain;

public record BatchTagJobCounts(int succeededCount, int noChangeCount, int skippedCount, int failedCount) {
}
