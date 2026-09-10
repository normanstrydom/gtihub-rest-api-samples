package com.example.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class RestUtils {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestUtils() { }

    public static JsonNode getJson(String url, String token) throws IOException, InterruptedException {
        HttpRequest.Builder reqb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept", "application/vnd.github+json");

        if (token != null && !token.isBlank()) {
            reqb.header("Authorization", "token " + token);
        }

        HttpRequest req = reqb.build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return MAPPER.readTree(resp.body());
        }

        System.out.println(resp.body());

        throw new HttpStatusException(resp.statusCode(), "Request failed: " + resp.statusCode() + " for " + url + " -> " + resp.body());
    }

    // carries the HTTP status code so callers can handle expected errors (e.g. 404) without string parsing
    public static final class HttpStatusException extends IOException {
        private final int statusCode;

        public HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
