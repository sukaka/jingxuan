package com.whale.crm.customer.batchtag.api.dto;

import com.whale.crm.platform.web.PageResponse;

public record BatchTagJobDetailResponse(BatchTagJobResponse job,
                                        PageResponse<BatchTagJobItemResponse> items) {
}
