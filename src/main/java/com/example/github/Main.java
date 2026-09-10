package com.example.github;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

public class Main {
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

        GithubRest client = new GithubRest(token);

        try {
            JsonNode repos = client.listUserRepos(username);
            if (repos.isArray()) {
                for (JsonNode repo : repos) {
                    String repoName = repo.path("name").asText();
                    String owner = repo.path("owner").path("login").asText(username);
                    System.out.println("Repository: " + owner + "/" + repoName);

                    // list packages for the repository
                    JsonNode packages = client.listRepoPackages(owner, repoName);
                    if (packages.isArray() && packages.size() > 0) {
                        for (JsonNode pkg : packages) {
                            String packageName = pkg.path("name").asText();
                            String packageType = pkg.path("package_type").asText();
                            System.out.println("  Package: " + packageType + " " + packageName);

                            // list versions
                            JsonNode versions = client.listPackageVersions(owner, repoName, packageType, packageName);
                            if (versions.isArray() && versions.size() > 0) {
                                for (JsonNode ver : versions) {
                                    String versionId = ver.path("id").asText();
                                    String versionName = ver.path("name").asText(ver.path("metadata").path("package_version").asText(""));
                                    System.out.println("    Version: " + versionId + " " + versionName);

                                    JsonNode tags = ver.path("metadata").path("container").path("tags");
                                    if (tags.isArray() && tags.size() > 0) {
                                        for (JsonNode tag : tags) {
                                            System.out.println("      Tag: " + tag.asText());
                                        }
                                    }
                                }
                            } else {
                                System.out.println("    (no versions)");
                            }

                        }
                    } else {
                        System.out.println("  (no packages)");
                    }
                }
            } else {
                System.out.println("No repositories found or unexpected response.");
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
