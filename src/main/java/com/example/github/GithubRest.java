package com.example.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class GithubRest {
    private final String token;
    // avoids re-fetching the same owner+packageType list for every repo the owner has
    private final Map<String, JsonNode> userPackagesCache = new HashMap<>();

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
        String cacheKey = username + "/" + packageType;
        JsonNode cached = userPackagesCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String url = "https://api.github.com/users/" + username + "/packages?package_type=" + packageType + "&per_page=100";
        JsonNode result;
        try {
            result = RestUtils.getJson(url, token);
        } catch (RestUtils.HttpStatusException e) {
            // 404: no packages of this type, or 403: token lacks read:packages scope for this user
            if (e.getStatusCode() == 404 || e.getStatusCode() == 403) {
                result = JsonNodeFactory.instance.arrayNode();
            } else {
                throw e;
            }
        }
        userPackagesCache.put(cacheKey, result);
        return result;
    }

    public JsonNode listPackageVersions(String owner, String repo, String packageType, String packageName) throws IOException, InterruptedException {
        String url = "https://api.github.com/users/" + owner + "/packages/" + packageType + "/" + packageName + "/versions?per_page=100";
        return RestUtils.getJson(url, token);
    }

    private static final String FILES_QUERY =
            "query($owner:String!,$repo:String!,$packageName:String!) {\n" +
            "  repository(owner:$owner, name:$repo) {\n" +
            "    packages(first: 1, names: [$packageName]) {\n" +
            "      nodes {\n" +
            "        versions(first: 50) {\n" +
            "          nodes {\n" +
            "            version\n" +
            "            files(first: 50) {\n" +
            "              nodes { name size updatedAt url }\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    // REST has no endpoint for package version files; only the GraphQL API exposes PackageFile.
    // Fetches all versions' files in a single call instead of one call per version.
    public Map<String, JsonNode> listPackageFilesByVersion(String owner, String repo, String packageName) throws IOException, InterruptedException {
        Map<String, Object> variables = Map.of("owner", owner, "repo", repo, "packageName", packageName);
        JsonNode data = RestUtils.postGraphQL(FILES_QUERY, variables, token);
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode pkg : data.path("repository").path("packages").path("nodes")) {
            for (JsonNode ver : pkg.path("versions").path("nodes")) {
                result.put(ver.path("version").asText(), ver.path("files").path("nodes"));
            }
        }
        return result;
    }

}
