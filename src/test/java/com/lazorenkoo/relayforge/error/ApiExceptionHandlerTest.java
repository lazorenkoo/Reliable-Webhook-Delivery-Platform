package com.lazorenkoo.relayforge.error;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ApiExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ErrorController())
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 502, 503})
    void preservesStatusAndUsesCommonBody(int status) throws Exception {
        String path = "/test-errors/status/" + status;
        var response = mvc.perform(get(path).queryParam("key", "secret-query"))
                .andReturn().getResponse();

        assertEquals(status, response.getStatus());
        var body = jsonMapper.readTree(response.getContentAsString());
        assertEquals(3, body.size());
        assertEquals(status, body.get("status").asInt());
        assertEquals(path, body.get("path").asText());
        assertEquals(status < 500 ? "Request rejected" : "Webhook delivery failed", body.get("message").asText());
        assertFalse(response.getContentAsString().contains("secret-query"));
    }

    @ParameterizedTest
    @CsvSource({"GET,/test-errors/number/invalid,400", "POST,/test-errors/body,400",
            "POST,/test-errors/status/400,405", "GET,/unknown-route,404"})
    void frameworkErrorsUseCommonBody(String method, String path, int status) throws Exception {
        var response = mvc.perform(request(HttpMethod.valueOf(method), path)
                .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andReturn().getResponse();

        assertEquals(status, response.getStatus());
        var body = jsonMapper.readTree(response.getContentAsString());
        assertEquals(status, body.get("status").asInt());
        assertEquals(path, body.get("path").asText());
        assertTrue(body.get("message").isTextual());
        if (status == 405) {
            assertTrue(response.getHeader("Allow").contains("GET"));
        }
    }

    @Test
    void unexpectedErrorDoesNotExposeExceptionDetails() throws Exception {
        var response = mvc.perform(get("/test-errors/unexpected")).andReturn().getResponse();
        var body = jsonMapper.readTree(response.getContentAsString());

        assertEquals(500, response.getStatus());
        assertEquals("Internal server error", body.get("message").asText());
        assertEquals("/test-errors/unexpected", body.get("path").asText());
        assertFalse(response.getContentAsString().contains("database-password"));
        assertFalse(response.getContentAsString().contains("IllegalStateException"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsMissingBodyOrUnsupportedContentType(boolean missingBody) throws Exception {
        var request = post("/test-errors/body");
        if (!missingBody) {
            request.contentType(MediaType.TEXT_PLAIN).content("secret-request-content");
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(missingBody ? 400 : 415, response.getStatus());
        assertEquals(response.getStatus(), jsonMapper.readTree(response.getContentAsString()).get("status").asInt());
        assertFalse(response.getContentAsString().contains("secret-request-content"));
    }

    @RestController
    static class ErrorController {
        @GetMapping("/test-errors/status/{status}")
        void status(@PathVariable int status) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(status), "Request rejected");
        }

        @GetMapping("/test-errors/number/{id}")
        int number(@PathVariable int id) {
            return id;
        }

        @PostMapping("/test-errors/body")
        Map<String, String> body(@RequestBody Map<String, String> body) {
            return body;
        }

        @GetMapping("/test-errors/unexpected")
        void unexpected() {
            throw new IllegalStateException("database-password");
        }
    }
}
