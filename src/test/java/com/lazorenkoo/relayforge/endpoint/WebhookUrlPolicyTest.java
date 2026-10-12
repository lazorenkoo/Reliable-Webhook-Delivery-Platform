package com.lazorenkoo.relayforge.endpoint;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookUrlPolicyTest {

    private final WebhookDnsResolver resolver = mock(WebhookDnsResolver.class);
    private final WebhookUrlPolicy policy = new WebhookUrlPolicy(false, "example.com", resolver);

    @Test
    void acceptsAllowedHostWithOnlyPublicAddresses() throws Exception {
        when(resolver.resolve("example.com")).thenReturn(new InetAddress[] {
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("2606:4700:4700::1111")});

        assertEquals("example.com", policy.validate("https://example.com/webhook").getHost());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "::1", "::ffff:169.254.169.254"})
    void rejectsMixedPublicAndPrivateDnsAnswers(String privateAddress) throws Exception {
        when(resolver.resolve("example.com")).thenReturn(new InetAddress[] {
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName(privateAddress)});

        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> policy.validate("https://example.com")).getStatusCode().value());
    }

    @Test
    void rejectsEmptyOrFailedDnsResolution() throws Exception {
        when(resolver.resolve("example.com")).thenReturn(new InetAddress[0]);
        assertThrows(ResponseStatusException.class, () -> policy.validate("https://example.com"));
        when(resolver.resolve("example.com")).thenThrow(new UnknownHostException("Unknown host"));
        assertThrows(ResponseStatusException.class, () -> policy.validate("https://example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://other.example", "https://example.com.evil.example",
            "https://user:pass@example.com", "https://example.com/#fragment", "ftp://example.com",
            "https://example.com:8443", "http://example.com:443", "https://example.com:0"})
    void rejectsUnsafeUrlBeforeDns(String url) {
        assertThrows(ResponseStatusException.class, () -> policy.validate(url));
        verifyNoInteractions(resolver);
    }

    @Test
    void emptyAllowlistRejectsEveryHost() {
        WebhookUrlPolicy closed = new WebhookUrlPolicy(false, "", resolver);
        assertThrows(ResponseStatusException.class, () -> closed.validate("https://example.com"));
        verifyNoInteractions(resolver);
    }
}
