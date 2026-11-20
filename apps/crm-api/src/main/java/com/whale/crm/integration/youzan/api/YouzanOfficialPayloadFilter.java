package com.whale.crm.integration.youzan.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class YouzanOfficialPayloadFilter extends OncePerRequestFilter {
    private static final String OFFICIAL_PATH = "/api/v1/webhooks/youzan/official";
    private static final int MAX_PAYLOAD_BYTES = 1_000_000;
    private static final int READ_BUFFER_SIZE = 8_192;

    private final ObjectMapper objectMapper;

    public YouzanOfficialPayloadFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
        return !OFFICIAL_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_PAYLOAD_BYTES) {
            writePayloadTooLarge(request, response);
            return;
        }
        if (contentLength <= 0 || isChunked(request)) {
            byte[] cachedBody = readBoundedBody(request.getInputStream());
            if (cachedBody == null) {
                writePayloadTooLarge(request, response);
                return;
            }
            chain.doFilter(new CachedBodyHttpServletRequest(request, cachedBody), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isChunked(HttpServletRequest request) {
        String transferEncoding = request.getHeader("Transfer-Encoding");
        return transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked");
    }

    private static byte[] readBoundedBody(InputStream inputStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        int total = 0;
        while (true) {
            int remaining = (MAX_PAYLOAD_BYTES + 1) - total;
            int read = inputStream.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read == -1) {
                return out.toByteArray();
            }
            total += read;
            if (total > MAX_PAYLOAD_BYTES) {
                return null;
            }
            out.write(buffer, 0, read);
        }
    }

    private void writePayloadTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(
                ErrorCode.VALIDATION_FAILED.name(), "有赞消息体超过1MB限制", null,
                requestId instanceof String id ? id : null)));
    }

    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        private CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
            super(request);
            this.cachedBody = cachedBody;
        }

        @Override
        public int getContentLength() {
            return cachedBody.length;
        }

        @Override
        public long getContentLengthLong() {
            return cachedBody.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream delegate = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return delegate.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Async read listener is not supported");
                }

                @Override
                public int read() {
                    return delegate.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return delegate.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
