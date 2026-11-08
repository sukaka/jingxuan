package com.whale.crm.exporting.application;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

public class ExportAccessException extends BusinessException {
    private final String denialCode;

    public ExportAccessException(ErrorCode errorCode, String message, String denialCode) {
        super(errorCode, message);
        this.denialCode = denialCode;
    }

    public String denialCode() {
        return denialCode;
    }
}
