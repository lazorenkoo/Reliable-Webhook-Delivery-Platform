package com.lazorenkoo.relayforge.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.lazorenkoo.relayforge.error.ApiError;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final byte[] apiKey;
    private final JsonMapper jsonMapper;

    public ApiKeyFilter(@Value("${relayforge.api-key:}") String apiKey, JsonMapper jsonMapper) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("RELAYFORGE_API_KEY must be configured");
        }
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/health".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader("X-API-Key");
        if (provided == null || !MessageDigest.isEqual(apiKey, provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            ApiError error = new ApiError(401, "Valid API key is required", request.getRequestURI());
            response.getWriter().write(jsonMapper.writeValueAsString(error));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
