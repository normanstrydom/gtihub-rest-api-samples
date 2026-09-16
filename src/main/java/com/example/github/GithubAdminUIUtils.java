package com.example.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * GithubAdminUIUtils
 * 
 * Utility class for managing GitHub administration tasks through the UI.
 * 
 * Provides utility methods for interacting with GitHub repositories, branches, workflows, and packages.
 * 
 * Cache management for GitHub data to improve performance and reduce API calls.
 * 
 * Call minimul number of GitHub Graphql and API requests retrieving cached data as far as possible.
 * 
 * Use graphql API for efficient data retrieval and caching.
 * 
 * Add caching for workflow statuses and package information to minimize API calls.
 * 
 * Add method to invalidate cached data to force reloading when necessary.
 * 
 */

public class GithubAdminUIUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_API_URL = "https://api.github.com";
    private static final String[] PACKAGE_TYPES = { "npm", "maven", "rubygems", "docker", "nuget", "container" };

    private final String githubToken;
    private final boolean isOrganization;
    private final String githubUserOrOrganizationName;
    private final String githubApiUrl;
    private final Map<String, List<String>> cache = new LinkedHashMap<>();
    private final Map<String, List<WorkflowStatus>> workflowStatusCache = new LinkedHashMap<>();
    private final Map<String, String> packageTypes = new LinkedHashMap<>();
    private final Map<String, String> repoOwners = new LinkedHashMap<>();
    private final Map<String, List<String>> pendingBranchCache = new LinkedHashMap<>();

    public GithubAdminUIUtils(String githubToken, boolean isOrganization, String githubUserOrOrganizationName,
            String githubApiUrl) {
        this.githubToken = githubToken;
        this.isOrganization = isOrganization;
        this.githubUserOrOrganizationName = githubUserOrOrganizationName;
        this.githubApiUrl = githubApiUrl == null || githubApiUrl.isBlank()
                ? DEFAULT_API_URL
                : githubApiUrl.replaceAll("/+$", "");
    }

    // nests the first page of branches per repository to avoid a separate branches query for most repos
    private static final String REPO_BRANCHES_FRAGMENT =
            "        refs(refPrefix: \"refs/heads/\", first: 100) {\n" +
            "          nodes { name }\n" +
            "          pageInfo { hasNextPage endCursor }\n" +
            "        }\n";

    private static final String VIEWER_REPOS_QUERY =
            "query($cursor:String) {\n" +
            "  viewer {\n" +
            "    repositories(first: 100, after: $cursor,\n" +
            "        affiliations: [OWNER, COLLABORATOR, ORGANIZATION_MEMBER]) {\n" +
            "      nodes {\n" +
            "        name\n" +
            "        nameWithOwner\n" +
            REPO_BRANCHES_FRAGMENT +
            "      }\n" +
            "      pageInfo { hasNextPage endCursor }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    private static final String OWNER_REPOS_QUERY =
            "query($login:String!,$cursor:String) {\n" +
            "  repositoryOwner(login: $login) {\n" +
            "    repositories(first: 100, after: $cursor) {\n" +
            "      nodes {\n" +
            "        name\n" +
            "        nameWithOwner\n" +
            REPO_BRANCHES_FRAGMENT +
            "      }\n" +
            "      pageInfo { hasNextPage endCursor }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    private static final String BRANCHES_QUERY =
            "query($owner:String!,$repo:String!,$cursor:String) {\n" +
            "  repository(owner: $owner, name: $repo) {\n" +
            "    refs(refPrefix: \"refs/heads/\", first: 100, after: $cursor) {\n" +
            "      nodes { name }\n" +
            "      pageInfo { hasNextPage endCursor }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    public List<String> getRepositoryList() {
        List<String> repositories = getCached("repos", this::loadRepositoryNames);
        // merge branch pages fetched alongside repos only after the "repos" load itself has completed,
        // otherwise this would structurally modify cache while its own computeIfAbsent is still running
        if (!pendingBranchCache.isEmpty()) {
            cache.putAll(pendingBranchCache);
            pendingBranchCache.clear();
        }
        return repositories;
    }

    private List<String> loadRepositoryNames() {
        // viewer{repositories} exposes private repos for the token owner; repositoryOwner{} is used otherwise
        boolean useViewer = !isOrganization && githubToken != null && !githubToken.isBlank();
        String query = useViewer ? VIEWER_REPOS_QUERY : OWNER_REPOS_QUERY;
        List<String> repositories = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> variables = new HashMap<>();
            variables.put("login", githubUserOrOrganizationName);
            variables.put("cursor", cursor);
            JsonNode data = graphQL(query, variables);
            JsonNode repositoryConnection = useViewer
                    ? data.path("viewer").path("repositories")
                    : data.path("repositoryOwner").path("repositories");
            for (JsonNode node : repositoryConnection.path("nodes")) {
                String repoName = node.path("name").asText();
                String nameWithOwner = node.path("nameWithOwner").asText();
                int slash = nameWithOwner.lastIndexOf('/');
                if (slash > 0) {
                    repoOwners.put(repoName, nameWithOwner.substring(0, slash));
                }
                repositories.add(repoName);
                cacheBranchesIfComplete(repoName, node.path("refs"));
            }
            cursor = nextCursor(repositoryConnection.path("pageInfo"));
        } while (cursor != null);
        return repositories;
    }

    public List<String> getBranchList(String repositoryName) {
        return getCached("branches/" + repositoryName, () -> {
            List<String> branches = new ArrayList<>();
            String cursor = null;
            do {
                Map<String, Object> variables = new HashMap<>();
                variables.put("owner", ownerFor(repositoryName));
                variables.put("repo", repositoryName);
                variables.put("cursor", cursor);
                JsonNode refs = graphQL(BRANCHES_QUERY, variables).path("repository").path("refs");
                branches.addAll(names(refs.path("nodes"), "name"));
                cursor = nextCursor(refs.path("pageInfo"));
            } while (cursor != null);
            return branches;
        });
    }

    public List<String> getWorkflowList(String repositoryName) {
        return getCached("workflows/" + repositoryName,
                () -> names(get(repoUrl(repositoryName, "actions/workflows?per_page=100"), "workflows"), "name"));
    }

    public boolean workflowExists(String repositoryName, String workflowName) {
        return getWorkflowList(repositoryName).contains(workflowName);
    }

    public boolean runWorkflow(String repositoryName, String workflowName, String branchName) {
        try {
            ObjectNode body = MAPPER.createObjectNode().put("ref", branchName);
            RestUtils.postJson(repoUrl(repositoryName, "actions/workflows/" + encode(workflowName) + "/dispatches"),
                    body, githubToken);
            invalidateWorkflowCache(repositoryName);
            return true;
        } catch (IOException | InterruptedException e) {
            throw failure("run workflow", e);
        }
    }

    public List<WorkflowStatus> getWorkflowStatusList(String repositoryName, String workflowName) {
        String key = repositoryName + "/" + workflowName;
        return workflowStatusCache.computeIfAbsent(key, ignored -> {
            JsonNode runs = get(repoUrl(repositoryName,
                    "actions/workflows/" + encode(workflowName) + "/runs?per_page=100"), "workflow_runs");
            List<WorkflowStatus> result = new ArrayList<>();
            for (JsonNode run : runs) {
                Instant created = instant(run.path("created_at").asText(null));
                Instant updated = instant(run.path("updated_at").asText(null));
                long duration = created == null || updated == null ? 0 : Math.max(0, Duration.between(created, updated).toMillis());
                result.add(new WorkflowStatus(workflowName, "in_progress".equals(run.path("status").asText()),
                        run.path("status").asText(null), run.path("conclusion").asText(null),
                        run.path("id").asText(null), run.path("created_at").asText(null),
                        run.path("updated_at").asText(null), duration));
            }
            return result;
        });
    }

    public List<String> getPackageList(String repositoryName) {
        return getCached("packages/" + repositoryName, () -> {
            Set<String> names = new java.util.LinkedHashSet<>();
            for (String type : PACKAGE_TYPES) {
                for (JsonNode pkg : packageResponse(type)) {
                    if (repositoryName.equals(pkg.path("repository").path("name").asText(null))) {
                        String name = pkg.path("name").asText(null);
                        if (name != null) {
                            names.add(name);
                            packageTypes.put(repositoryName + "/" + name, type);
                        }
                    }
                }
            }
            return new ArrayList<>(names);
        });
    }

    public List<String> getPackageVersionList(String repositoryName, String packageName) {
        getPackageList(repositoryName);
        String type = packageTypes.get(repositoryName + "/" + packageName);
        if (type == null) return Collections.emptyList();
        return getCached("package-versions/" + repositoryName + "/" + packageName,
                () -> names(get(ownerUrl("packages/" + type + "/" + encode(packageName) + "/versions?per_page=100"), null), "name"));
    }

    public List<String> getPackageArtifactsList(String repositoryName, String packageName,
            String versionString) {
        getPackageList(repositoryName);
        try {
            Map<String, JsonNode> files = new GithubRest(githubToken)
                    .listPackageFilesByVersion(ownerFor(repositoryName), repositoryName, packageName);
            JsonNode versionFiles = files.get(versionString);
            return versionFiles == null ? Collections.emptyList() : names(versionFiles, "name");
        } catch (IOException | InterruptedException e) {
            throw failure("list package artifacts", e);
        }
    }

    public void invalidateCache() {
        cache.clear();
        workflowStatusCache.clear();
        packageTypes.clear();
        repoOwners.clear();
        pendingBranchCache.clear();
    }

    private void invalidateWorkflowCache(String repositoryName) {
        cache.keySet().removeIf(key -> key.contains("workflows/" + repositoryName)
                || key.contains("workflow-runs/" + repositoryName));
        workflowStatusCache.keySet().removeIf(key -> key.startsWith(repositoryName + "/"));
    }

    private JsonNode get(String url, String child) {
        try {
            JsonNode value = RestUtils.getJson(url, githubToken);
            return child == null ? value : value.path(child);
        } catch (IOException | InterruptedException e) {
            throw failure("call GitHub API", e);
        }
    }

    private JsonNode graphQL(String query, Map<String, Object> variables) {
        try {
            return RestUtils.postGraphQL(query, variables, githubToken);
        } catch (IOException | InterruptedException e) {
            throw failure("call GitHub GraphQL API", e);
        }
    }

    private static String nextCursor(JsonNode pageInfo) {
        return pageInfo.path("hasNextPage").asBoolean(false) ? pageInfo.path("endCursor").asText() : null;
    }

    // only cache when the nested first page already contains every branch; otherwise getBranchList paginates fully
    private void cacheBranchesIfComplete(String repoName, JsonNode refsConnection) {
        if (!refsConnection.path("pageInfo").path("hasNextPage").asBoolean(false)) {
            pendingBranchCache.put("branches/" + repoName, names(refsConnection.path("nodes"), "name"));
        }
    }

    private List<String> names(JsonNode nodes, String field) {
        List<String> result = new ArrayList<>();
        if (nodes != null && nodes.isArray()) {
            for (JsonNode node : nodes) result.add(node.path(field).asText());
        }
        return result;
    }

    private List<String> getCached(String key, java.util.function.Supplier<List<String>> loader) {
        return cache.computeIfAbsent(key, ignored -> loader.get());
    }

    private JsonNode packageResponse(String packageType) {
        return get(ownerUrl("packages?package_type=" + packageType + "&per_page=100"), null);
    }

    private String ownerUrl(String path) {
        String ownerPath = isOrganization ? "orgs/" : "users/";
        return githubApiUrl + "/" + ownerPath + encode(githubUserOrOrganizationName) + "/" + path;
    }

    private String repoUrl(String repositoryName, String path) {
        return githubApiUrl + "/repos/" + encode(ownerFor(repositoryName)) + "/"
                + encode(repositoryName) + "/" + path;
    }

    private String ownerFor(String repositoryName) {
        return repoOwners.getOrDefault(repositoryName, githubUserOrOrganizationName);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Instant instant(String value) {
        try { return value == null ? null : Instant.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static IllegalStateException failure(String action, Exception cause) {
        return new IllegalStateException("Unable to " + action + ": " + cause.getMessage(), cause);
    }

    public static class WorkflowStatus implements Serializable {
        
        private String workflowName;
        private boolean isRunning;
        private String status;
        private String conclusion;
        private String runId;
        private String createdAt;
        private String updatedAt;
        private long duration;

        public WorkflowStatus(String workflowName, boolean isRunning, String status, String conclusion,
                String runId, String createdAt, String updatedAt, long duration) {
            this.workflowName = workflowName;
            this.isRunning = isRunning;
            this.status = status;
            this.conclusion = conclusion;
            this.runId = runId;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.duration = duration;
        }

        public String getWorkflowName() {
            return workflowName;
        }

        public boolean isRunning() {
            return isRunning;
        }

        public String getStatus() {
            return status;
        }

        public String getConclusion() {
            return conclusion;
        }

        public String getRunId() {
            return runId;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public long getDuration() {
            return duration;
        }


    }

}
