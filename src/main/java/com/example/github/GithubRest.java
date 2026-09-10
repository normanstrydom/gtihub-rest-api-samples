package com.example.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;

public class GithubRest {
    private final String token;

    public GithubRest(String token) {
        this.token = token;
    }

    public JsonNode listUserRepos(String username) throws IOException, InterruptedException {
        // /users/{username}/repos only ever returns public repos; to include private repos we must
        // query /user/repos as the authenticated user, which only works for that user's own account.
        String url = (token != null && !token.isBlank())
                ? "https://api.github.com/user/repos?per_page=100&page=1&affiliation=owner&visibility=all"
                : "https://api.github.com/users/" + username + "/repos?per_page=100&page=1";
        return RestUtils.getJson(url, token);
    }

    public JsonNode listRepoPackages(String owner, String repo) throws IOException, InterruptedException {
        // there is no /repos/{owner}/{repo}/packages endpoint; packages are only listed per-user/org
        // and filtered by package_type, so fetch each type for the owner and filter by repository below.
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        for (String packageType : PACKAGE_TYPES) {
            for (JsonNode pkg : listUserPackages(owner, packageType)) {
                String pkgRepoName = pkg.path("repository").path("name").asText(null);
                if (repo.equals(pkgRepoName)) {
                    result.add(pkg);
                }
            }
        }
        return result;
    }

    private static final String[] PACKAGE_TYPES = { "npm", "maven", "rubygems", "docker", "nuget", "container" };

    public JsonNode listUserPackages(String username, String packageType) throws IOException, InterruptedException {
        String url = "https://api.github.com/users/" + username + "/packages?package_type=" + packageType + "&per_page=100";
        try {
            return RestUtils.getJson(url, token);
        } catch (RestUtils.HttpStatusException e) {
            // 404: no packages of this type, or 403: token lacks read:packages scope for this user
            if (e.getStatusCode() == 404 || e.getStatusCode() == 403) {
                return JsonNodeFactory.instance.arrayNode();
            }
            throw e;
        }
    }

    public JsonNode listPackageVersions(String owner, String repo, String packageType, String packageName) throws IOException, InterruptedException {
        String url = "https://api.github.com/users/" + owner + "/packages/" + packageType + "/" + packageName + "/versions?per_page=100";
        return RestUtils.getJson(url, token);
    }

}
