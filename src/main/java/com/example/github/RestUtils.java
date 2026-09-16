package com.example.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

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

    public static JsonNode postJson(String url, JsonNode body, String token) throws IOException, InterruptedException {
        HttpRequest.Builder reqb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString()))
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json");

        if (token != null && !token.isBlank()) {
            reqb.header("Authorization", "token " + token);
        }

        HttpResponse<String> resp = CLIENT.send(reqb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return resp.body().isBlank() ? JsonNodeFactory.instance.objectNode() : MAPPER.readTree(resp.body());
        }

        throw new HttpStatusException(resp.statusCode(), "Request failed: " + resp.statusCode() + " for " + url + " -> " + resp.body());
    }

    // package version files are only exposed via the GraphQL API, there is no REST equivalent
    public static JsonNode postGraphQL(String query, Map<String, Object> variables, String token) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        body.set("variables", MAPPER.valueToTree(variables));

        HttpRequest.Builder reqb = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/graphql"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json");

        if (token != null && !token.isBlank()) {
            reqb.header("Authorization", "bearer " + token);
        }

        HttpRequest req = reqb.build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new HttpStatusException(resp.statusCode(), "GraphQL request failed: " + resp.statusCode() + " -> " + resp.body());
        }

        JsonNode result = MAPPER.readTree(resp.body());
        if (result.has("errors")) {
            throw new IOException("GraphQL errors: " + result.path("errors").toString());
        }
        return result.path("data");
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

