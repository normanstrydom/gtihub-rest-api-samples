package com.example.github;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

public class MainGraphQL {
    private static final String QUERY = """
            query($login: String!, $repoFirst: Int!, $packageFirst: Int!,
                  $versionFirst: Int!, $fileFirst: Int!) {
              repositoryOwner(login: $login) {
                repositories(first: $repoFirst) {
                  nodes {
                    name
                    owner {
                      login
                    }
                    packages(first: $packageFirst) {
                      nodes {
                        name
                        packageType
                        versions(first: $versionFirst) {
                          nodes {
                            id
                            version
                            files(first: $fileFirst) {
                              nodes {
                                name
                                size
                                url
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    private static final int REPOSITORY_PAGE_SIZE = 100;
    private static final int NESTED_PAGE_SIZE = 10;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar github-rest-samples.jar <github-username>");
            System.exit(1);
        }

        String username = args[0];
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            System.out.println("Warning: GITHUB_TOKEN not set; unauthenticated rate limits apply.");
        }

        Map<String, Object> variables = Map.of(
                "login", username,
                "repoFirst", REPOSITORY_PAGE_SIZE,
                "packageFirst", NESTED_PAGE_SIZE,
                "versionFirst", NESTED_PAGE_SIZE,
                "fileFirst", NESTED_PAGE_SIZE);

        try {
            JsonNode data = RestUtils.postGraphQL(QUERY, variables, token);
            JsonNode repositories = data.path("repositoryOwner").path("repositories").path("nodes");
            if (!repositories.isArray() || repositories.isEmpty()) {
                System.out.println("No repositories found or unexpected response.");
                return;
            }

            for (JsonNode repository : repositories) {
                String owner = repository.path("owner").path("login").asText(username);
                String repositoryName = repository.path("name").asText();
                System.out.println("Repository: " + owner + "/" + repositoryName);

                JsonNode packages = repository.path("packages").path("nodes");
                if (!packages.isArray() || packages.isEmpty()) {
                    System.out.println("  (no packages)");
                    continue;
                }

                for (JsonNode packageNode : packages) {
                    String packageName = packageNode.path("name").asText();
                    String packageType = packageNode.path("packageType").asText();
                    System.out.println("  Package: " + packageType + " " + packageName);

                    JsonNode versions = packageNode.path("versions").path("nodes");
                    if (!versions.isArray() || versions.isEmpty()) {
                        System.out.println("    (no versions)");
                        continue;
                    }

                    for (JsonNode version : versions) {
                        String versionId = version.path("id").asText();
                        String versionName = version.path("version").asText();
                        System.out.println("    Version: " + versionId + " " + versionName);

                        JsonNode files = version.path("files").path("nodes");
                        if (files.isArray() && !files.isEmpty()) {
                            for (JsonNode file : files) {
                                System.out.println("      File: " + file.path("name").asText()
                                        + " (" + file.path("size").asText() + " bytes) "
                                        + file.path("url").asText());
                            }
                        } else {
                            System.out.println("      (no files)");
                        }
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}