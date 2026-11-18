package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.notification.domain.WecomSendResult;
import org.springframework.stereotype.Component;

@Component
public class MockWecomExternalContactSender implements ExternalContactMessageSender {
    @Override
    public WecomSendResult send(ExternalContactMessageCommand command) {
        return new WecomSendResult(WecomSendResult.Status.MOCKED, "MOCK", null,
                "模拟完成，未发送给客户");
    }

    @Override
    public String mode() {
        return "MOCK";
    }
}
