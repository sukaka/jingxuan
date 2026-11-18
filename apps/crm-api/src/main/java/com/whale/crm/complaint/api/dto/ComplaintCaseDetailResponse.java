package com.whale.crm.complaint.api.dto;

import java.util.List;

public record ComplaintCaseDetailResponse(ComplaintCaseResponse complaintCase,
                                          List<ComplaintCaseHistoryResponse> history,
                                          String empathyScript,
                                          List<ComplaintEmpathyScriptResponse> empathyScripts) {
    public ComplaintCaseDetailResponse {
        history = history == null ? List.of() : List.copyOf(history);
        empathyScripts = empathyScripts == null ? List.of() : List.copyOf(empathyScripts);
    }
}
