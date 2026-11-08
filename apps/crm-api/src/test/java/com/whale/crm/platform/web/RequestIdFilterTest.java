package com.whale.crm.platform.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {
    @Test
    void validUlidPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "01ARZ3NDEKTSV4RRFFQ69G5FAV");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("01ARZ3NDEKTSV4RRFFQ69G5FAV");
        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE)).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void arbitraryIdIsRegeneratedInsteadOfReflected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "customer-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .isNotEqualTo("customer-supplied-id")
                .matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE)).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
