package com.enjoy.agent.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void doFilter_shouldReuseSafeIncomingRequestIdAndClearMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "jmeter-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY)).isEqualTo("jmeter-42"));

        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo("jmeter-42");
        assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void doFilter_shouldReplaceUnsafeRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "bad request id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER))
                .matches("[0-9a-f-]{36}");
    }
}
