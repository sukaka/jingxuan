package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DisabledWecomAdapter implements WecomAdapter {
    @Override
    public String name() {
        return "REAL_WECOM_DISABLED";
    }

    @Override
    public List<WecomContactInput> pullContacts(String corpId) {
        throw disabled();
    }

    @Override
    public WecomArchivePage pullArchive(String corpId, long afterSeq, int limit) {
        throw disabled();
    }

    private BusinessException disabled() {
        return new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                "企微会话归档未启用，或当前租户未获官方采集授权");
    }
}
