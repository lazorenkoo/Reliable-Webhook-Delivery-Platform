package com.lazorenkoo.relayforge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyFilterTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingConfiguredKey(String key) {
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyFilter(key));
    }

    @Test
    void healthWithContextPathRemainsOpenButApiDoesNot() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter("test-key");
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/relay/health");
        health.setContextPath("/relay");
        health.setServletPath("/health");
        MockFilterChain healthChain = new MockFilterChain();
        filter.doFilter(health, new MockHttpServletResponse(), healthChain);
        assertSame(health, healthChain.getRequest());

        MockHttpServletRequest api = new MockHttpServletRequest("GET", "/relay/api/v1/events");
        api.setContextPath("/relay");
        api.setServletPath("/api/v1/events");
        api.addHeader("X-API-Key", "wrong-key");
        MockFilterChain apiChain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(api, response, apiChain);
        assertEquals(401, response.getStatus());
        assertNull(apiChain.getRequest());
        assertFalse(response.getContentAsString().contains("test-key"));
        assertFalse(response.getContentAsString().contains("wrong-key"));
    }
}
