package com.whale.crm.integration.wecom.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class WecomHttpResponses {
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private WecomHttpResponses() {
    }

    static String body(Response response, ObjectMapper mapper) {
        try {
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_BYTES) {
                throw new BusinessException(ErrorCode.CONFLICT, "企微接口响应无效或过大");
            }
            String value = body.string();
            if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new BusinessException(ErrorCode.CONFLICT, "企微接口响应超过大小限制");
            }
            mapper.readTree(value);
            return value;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微接口返回了无法解析的响应");
        }
    }
}
