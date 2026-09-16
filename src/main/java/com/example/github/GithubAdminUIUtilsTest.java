package com.example.github;

public class GithubAdminUIUtilsTest {

    public static void main(String[] args) {

        String user = "normanstrydom";
        String token = System.getenv("GITHUB_TOKEN");
        System.out.println("User: " + user);

        GithubAdminUIUtils githubAdminUIUtils = new GithubAdminUIUtils(token, 
            false, user, "https://api.github.com");

        System.out.println("GithubAdminUIUtils instance created: " + githubAdminUIUtils);

        int repoIdx = 0;
        for (String repo : githubAdminUIUtils.getRepositoryList()) {
            System.out.println("Repository " + repoIdx + ": " + repo);
            repoIdx++;
            for (String branch : githubAdminUIUtils.getBranchList(repo)) {
                System.out.println("  Branch: " + branch);
            }
        }

    }

}