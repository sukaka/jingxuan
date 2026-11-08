package com.whale.crm.integration.youzan.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class YouzanOfficialPayloadFilterTest {
    @Test
    void contentLengthOverLimitIsRejectedBeforeController() throws Exception {
        YouzanOfficialPayloadFilter filter = new YouzanOfficialPayloadFilter(new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/youzan/official");
        request.setContent(new byte[1_000_001]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("1MB");
        verify(chain, never()).doFilter(request, response);
    }
}
