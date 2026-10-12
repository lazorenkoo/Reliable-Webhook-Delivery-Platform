package com.lazorenkoo.relayforge.endpoint;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WebhookUrlPolicy {

    private final boolean allowLocal;
    private final Set<String> allowedHosts;
    private final WebhookDnsResolver dnsResolver;

    public WebhookUrlPolicy(@Value("${relayforge.webhooks.allow-local:false}") boolean allowLocal,
                            @Value("${relayforge.webhooks.allowed-hosts:}") String allowedHosts,
                            WebhookDnsResolver dnsResolver) {
        this.allowLocal = allowLocal;
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
                .map(String::trim).map(host -> host.toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty()).collect(Collectors.toSet());
        this.dnsResolver = dnsResolver;
    }

    public URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw invalid("URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid URL");
        }
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if ((!http && !https) || uri.getHost() == null) {
            throw invalid("URL must use HTTP or HTTPS and contain a host");
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw invalid("Webhook URL must not contain credentials or a fragment");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535 || (!allowLocal && port != -1 && port != (https ? 443 : 80))) {
            throw invalid("Webhook URL port is not allowed");
        }
        if (uri.getHost().contains("%")) {
            throw invalid("Scoped webhook addresses are not allowed");
        }
        if (!allowLocal) {
            validateHost(uri.getHost());
        }
        return uri;
    }

    private void validateHost(String host) {
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw invalid("Webhook host is not configured as allowed");
        }
        InetAddress[] addresses;
        try {
            addresses = dnsResolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw invalid("Webhook host could not be resolved");
        }
        if (addresses.length == 0) {
            throw invalid("Webhook host has no addresses");
        }
        for (InetAddress address : addresses) {
            if (!PublicAddressPolicy.isPublic(address)) {
                throw invalid("Webhook host must resolve only to public addresses");
            }
        }
    }

    private BlockedWebhookUrlException invalid(String message) {
        return new BlockedWebhookUrlException(message);
    }
}
