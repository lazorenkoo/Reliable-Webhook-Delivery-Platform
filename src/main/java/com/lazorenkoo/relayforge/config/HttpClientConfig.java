package com.lazorenkoo.relayforge.config;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient httpClient(
            @Value("${relayforge.webhooks.proxy-host:}") String proxyHost,
            @Value("${relayforge.webhooks.proxy-port:3128}") int proxyPort) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (!proxyHost.isBlank()) {
            if (proxyPort < 1 || proxyPort > 65535) {
                throw new IllegalArgumentException("Webhook proxy port must be between 1 and 65535");
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        return builder.build();
    }
}
