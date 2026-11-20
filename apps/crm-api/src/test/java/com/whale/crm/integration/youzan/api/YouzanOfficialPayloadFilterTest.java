package com.whale.crm.integration.youzan.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class YouzanOfficialPayloadFilterTest {
    private static final String OFFICIAL_PATH = "/api/v1/webhooks/youzan/official";

    @Test
    void contentLengthOverLimitIsRejectedBeforeController() throws Exception {
        YouzanOfficialPayloadFilter filter = new YouzanOfficialPayloadFilter(new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", OFFICIAL_PATH);
        request.setContent(new byte[1_000_001]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("1MB");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void chunkedPayloadOverLimitIsRejectedBeforeController() throws Exception {
        YouzanOfficialPayloadFilter filter = new YouzanOfficialPayloadFilter(new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = chunkedRequest(OFFICIAL_PATH, new byte[1_000_001]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("1MB");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void chunkedSmallPayloadPassesThroughIntactToDownstream() throws Exception {
        YouzanOfficialPayloadFilter filter = new YouzanOfficialPayloadFilter(new ObjectMapper().findAndRegisterModules());
        String payload = "{\"msgId\":\"m-chunked\",\"kdtId\":\"123\",\"msg\":{\"tid\":\"E-1\",\"note\":\"中文内容\"}}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = chunkedRequest(OFFICIAL_PATH, payloadBytes);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<byte[]> capturedBytes = new AtomicReference<>();
        AtomicReference<String> capturedReaderText = new AtomicReference<>();
        AtomicLong capturedLength = new AtomicLong(-99);
        FilterChain chain = (req, res) -> {
            HttpServletRequest httpReq = (HttpServletRequest) req;
            capturedLength.set(httpReq.getContentLengthLong());
            capturedBytes.set(httpReq.getInputStream().readAllBytes());
            capturedReaderText.set(httpReq.getReader().readLine());
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLength.get()).isEqualTo(payloadBytes.length);
        assertThat(capturedBytes.get()).isEqualTo(payloadBytes);
        assertThat(capturedReaderText.get()).isEqualTo(payload);
    }

    @Test
    void contextPathIsStrippedWhenMatchingOfficialWebhookPath() throws Exception {
        YouzanOfficialPayloadFilter filter = new YouzanOfficialPayloadFilter(new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/crm" + OFFICIAL_PATH);
        request.setContextPath("/crm");
        request.setContent(new byte[1_000_001]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static MockHttpServletRequest chunkedRequest(String path, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.addHeader("Transfer-Encoding", "chunked");
        request.setContent(body);
        return request;
    }
}
