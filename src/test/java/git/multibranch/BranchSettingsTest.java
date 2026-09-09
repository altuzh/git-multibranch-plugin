package git.multibranch;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BranchSettingsTest {

    @Test
    public void testBranchMappingBasic() {
        BranchMapping bm = new BranchMapping("origin/deploy/custom", "deploy/custom", "-custom", true);
        assertEquals("origin/deploy/custom", bm.getSourceOriginBranch());
        assertEquals("deploy/custom", bm.getTargetOriginBranchName());
        assertEquals("-custom", bm.getBranchSuffix());
        assertTrue(bm.isEnabled());

        assertEquals("TASK-42-custom", bm.getLocalBranchName("TASK-42"));

        bm.setEnabled(false);
        assertFalse(bm.isEnabled());

        bm.setSourceOriginBranch("origin/main");
        bm.setTargetOriginBranchName("main");
        bm.setBranchSuffix("-main");
        assertEquals("origin/main", bm.getSourceOriginBranch());
        assertEquals("main", bm.getTargetOriginBranchName());
        assertEquals("-main", bm.getBranchSuffix());
    }

    @Test
    public void testBranchMappingCopyAndEquals() {
        BranchMapping orig = new BranchMapping("origin/deploy/dev", "deploy/dev", "-dev", true);
        BranchMapping copy = orig.copy();

        assertEquals(orig, copy);
        assertEquals(orig.hashCode(), copy.hashCode());

        copy.setEnabled(false);
        assertNotEquals(orig, copy);

        BranchMapping copy2 = new BranchMapping(orig);
        assertEquals(orig, copy2);
    }

    @Test
    public void testStateDefaultInitialization() {
        MultiBranchSettings.State state = new MultiBranchSettings.State();
        assertNotNull(state.branchMappings);
        assertEquals(4, state.branchMappings.size());

        assertEquals("origin/deploy/dev", state.branchMappings.get(0).getSourceOriginBranch());
        assertEquals("deploy/dev", state.branchMappings.get(0).getTargetOriginBranchName());
        assertEquals("-dev", state.branchMappings.get(0).getBranchSuffix());

        assertEquals("origin/deploy/test", state.branchMappings.get(1).getSourceOriginBranch());
        assertEquals("origin/merge_to_uat", state.branchMappings.get(2).getSourceOriginBranch());
        assertEquals("origin/merge_to_prod", state.branchMappings.get(3).getSourceOriginBranch());

        assertEquals("deploy/test", state.checkoutBranch);
        assertTrue(state.fetchOriginFirst);
        assertTrue(state.pushAfterCommit);
        assertTrue(state.generateMrLinks);
        assertTrue(state.openMrLinksInBrowser);
        assertTrue(state.stashOtherChanges);
        assertTrue(state.checkoutTestAfter);
        assertTrue(state.prefixMessageWithTask);
    }

    @Test
    public void testStateCopyIsDeep() {
        MultiBranchSettings.State orig = new MultiBranchSettings.State();
        MultiBranchSettings.State copy = orig.copy();

        assertEquals(orig, copy);

        copy.branchMappings.get(0).setBranchSuffix("-modified");
        assertNotEquals(orig.branchMappings.get(0).getBranchSuffix(), copy.branchMappings.get(0).getBranchSuffix());

        copy.checkoutBranch = "develop";
        assertNotEquals(orig.checkoutBranch, copy.checkoutBranch);
    }

    @Test
    public void testConfigFromSettings() {
        MultiBranchSettings.State state = new MultiBranchSettings.State();
        state.checkoutBranch = "release/1.0";
        state.branchMappings.clear();
        state.branchMappings.add(new BranchMapping("origin/release/1.0", "release/1.0", "-rel", true));

        MultiBranchSettings settings = new MultiBranchSettings();
        settings.loadState(state);

        MultiBranchConfig config = MultiBranchConfig.fromSettings(settings);
        assertEquals("release/1.0", config.getCheckoutBranch());
        assertEquals(1, config.getBranchMappings().size());
        assertEquals("origin/release/1.0", config.getBranchMappings().get(0).getSourceOriginBranch());
        assertEquals("release/1.0", config.getBranchMappings().get(0).getTargetOriginBranchName());
        assertEquals("-rel", config.getBranchMappings().get(0).getBranchSuffix());
    }

    @Test
    public void testFormattedCommitMessage() {
        MultiBranchConfig config = new MultiBranchConfig();
        config.setTaskPrefix("PROJ-99");
        config.setCommitMessage("Fix critical null pointer");
        config.setPrefixMessageWithTask(true);

        assertEquals("[PROJ-99] Fix critical null pointer", config.getFormattedCommitMessage());

        // Avoid double prefixing when exact tag present
        config.setCommitMessage("[PROJ-99] Fix critical null pointer");
        assertEquals("[PROJ-99] Fix critical null pointer", config.getFormattedCommitMessage());

        // Case-insensitive bracketed tag
        config.setCommitMessage("[proj-99] Fix critical null pointer");
        assertEquals("[proj-99] Fix critical null pointer", config.getFormattedCommitMessage());

        // Prefix without brackets: colon separated
        config.setCommitMessage("PROJ-99: Fix critical null pointer");
        assertEquals("PROJ-99: Fix critical null pointer", config.getFormattedCommitMessage());

        // Prefix without brackets: dash separated
        config.setCommitMessage("PROJ-99 - Fix critical null pointer");
        assertEquals("PROJ-99 - Fix critical null pointer", config.getFormattedCommitMessage());

        // Prefix without brackets: space separated
        config.setCommitMessage("PROJ-99 Fix critical null pointer");
        assertEquals("PROJ-99 Fix critical null pointer", config.getFormattedCommitMessage());

        // Prefix within message text
        config.setCommitMessage("Fix critical null pointer for PROJ-99");
        assertEquals("Fix critical null pointer for PROJ-99", config.getFormattedCommitMessage());

        // Prefix in parentheses
        config.setCommitMessage("Fix critical null pointer (PROJ-99)");
        assertEquals("Fix critical null pointer (PROJ-99)", config.getFormattedCommitMessage());

        // Substring prefix with different issue number should NOT match
        config.setCommitMessage("PROJ-999 Fix critical null pointer");
        assertEquals("[PROJ-99] PROJ-999 Fix critical null pointer", config.getFormattedCommitMessage());

        // Prefix specified with brackets in config
        config.setTaskPrefix("[PROJ-99]");
        config.setCommitMessage("Fix critical null pointer");
        assertEquals("[PROJ-99] Fix critical null pointer", config.getFormattedCommitMessage());

        config.setCommitMessage("[PROJ-99] Fix critical null pointer");
        assertEquals("[PROJ-99] Fix critical null pointer", config.getFormattedCommitMessage());

        // Disabled prefixing
        config.setPrefixMessageWithTask(false);
        config.setCommitMessage("Direct message");
        assertEquals("Direct message", config.getFormattedCommitMessage());
    }

    @Test
    public void testConfigCopy() {
        MultiBranchConfig orig = new MultiBranchConfig();
        orig.setTaskPrefix("DEMO-123");
        orig.setCheckoutBranch("deploy/custom");
        orig.setPushAfterCommit(false);
        orig.setGitLabCreateMr(true);
        orig.setGitLabAssignToMe(true);
        orig.setGitLabDeleteSourceBranch(true);
        orig.setGitLabSquashCommits(true);

        MultiBranchConfig copy = orig.copy();
        assertEquals("DEMO-123", copy.getTaskPrefix());
        assertEquals("deploy/custom", copy.getCheckoutBranch());
        assertTrue(copy.isGitLabCreateMr());
        assertTrue(copy.isGitLabAssignToMe());
        assertTrue(copy.isGitLabDeleteSourceBranch());
        assertTrue(copy.isGitLabSquashCommits());

        copy.setGitLabHost("https://custom.gitlab.local");
        copy.setGitLabApiToken("glpat-secret-token");
        assertEquals("https://custom.gitlab.local", copy.getGitLabHost());
        assertEquals("glpat-secret-token", copy.getGitLabApiToken());
        assertNotEquals(orig.getGitLabHost(), copy.getGitLabHost());

        copy.getBranchMappings().get(0).setBranchSuffix("-changed");
        assertNotEquals(orig.getBranchMappings().get(0).getBranchSuffix(), copy.getBranchMappings().get(0).getBranchSuffix());
    }

    @Test
    public void testGitLabApiConfigAndProjectInfo() {
        MultiBranchSettings.State state = new MultiBranchSettings.State();
        assertTrue(state.gitLabCreateMr);
        assertTrue(state.gitLabAssignToMe);
        assertTrue(state.gitLabDeleteSourceBranch);
        assertTrue(state.gitLabSquashCommits);
        assertEquals("", state.gitLabHost);
        assertEquals("", state.gitLabApiToken);

        state.gitLabHost = "https://gitlab.example.com";
        state.gitLabApiToken = "glpat-123456789";
        MultiBranchSettings.State copy = state.copy();
        assertEquals(state, copy);
        assertEquals("https://gitlab.example.com", copy.gitLabHost);
        assertEquals("glpat-123456789", copy.gitLabApiToken);

        // Test GitLabProjectInfo parsing: SSH URL
        GitLabApiService.GitLabProjectInfo info1 = GitLabApiService.parseProjectInfo(
                "git@gitlab.example.com:mygroup/subgroup/project.git", null);
        assertNotNull(info1);
        assertEquals("https://gitlab.example.com", info1.getHostUrl());
        assertEquals("mygroup/subgroup/project", info1.getProjectPath());
        assertEquals("mygroup%2Fsubgroup%2Fproject", info1.getEncodedPath());
        assertEquals("https://gitlab.example.com/api/v4", info1.getApiUrl());
        assertEquals("https://gitlab.example.com/mygroup/subgroup/project", info1.getWebProjectUrl());

        // Test GitLabProjectInfo parsing: HTTPS URL
        GitLabApiService.GitLabProjectInfo info2 = GitLabApiService.parseProjectInfo(
                "https://gitlab.com/company/repo.git", null);
        assertNotNull(info2);
        assertEquals("https://gitlab.com", info2.getHostUrl());
        assertEquals("company/repo", info2.getProjectPath());
        assertEquals("company%2Frepo", info2.getEncodedPath());
        assertEquals("https://gitlab.com/api/v4", info2.getApiUrl());

        // Test GitLabProjectInfo parsing with host override
        GitLabApiService.GitLabProjectInfo info3 = GitLabApiService.parseProjectInfo(
                "git@gitlab.example.com:project.git", "https://custom.gitlab.internal");
        assertNotNull(info3);
        assertEquals("https://custom.gitlab.internal", info3.getHostUrl());
        assertEquals("https://custom.gitlab.internal/api/v4", info3.getApiUrl());

        // Test JSON helpers
        String escaped = GitLabApiService.escapeJson("Hello \"world\"\nLine 2");
        assertTrue(escaped.contains("\\\"world\\\""));
        assertTrue(escaped.contains("\\nLine 2"));

        int extractedId = GitLabApiService.extractIntJson("{\"id\": 1234, \"username\": \"altuzh\"}", "id");
        assertEquals(1234, extractedId);

        String extractedUser = GitLabApiService.extractStringJson("{\"id\": 1234, \"username\": \"altuzh\"}", "username");
        assertEquals("altuzh", extractedUser);

        String extractedUrl = GitLabApiService.extractStringJson("{\"web_url\": \"https://gitlab.example.com/mr/1\"}", "web_url");
        assertEquals("https://gitlab.example.com/mr/1", extractedUrl);

        // Test GitLab MR URL generation
        String glUrl = MultiBranchService.generateReviewUrl("https://gitlab.example.com/project", "TASK-101-dev", "deploy/dev");
        assertEquals("https://gitlab.example.com/project/-/merge_requests/new?merge_request[source_branch]=TASK-101-dev&merge_request[target_branch]=deploy%2Fdev", glUrl);
    }

    @Test
    public void testDetectBranchContext() {
        MultiBranchSettings.State state = new MultiBranchSettings.State();
        List<BranchMapping> mappings = state.branchMappings;

        // 1. On designated dev branch
        MultiBranchCommitAction.BranchDetectionResult resDev = MultiBranchCommitAction.detectBranchContext("TASK-101-dev", mappings);
        assertTrue(resDev.isOnDesignatedBranch());
        assertEquals("TASK-101", resDev.getDetectedPrefix());
        assertNotNull(resDev.getMatchedMapping());
        assertEquals("-dev", resDev.getMatchedMapping().getBranchSuffix());
        assertEquals("deploy/dev", resDev.getMatchedMapping().getTargetOriginBranchName());

        // 2. On designated test branch
        MultiBranchCommitAction.BranchDetectionResult resTest = MultiBranchCommitAction.detectBranchContext("PROJ-777-test", mappings);
        assertTrue(resTest.isOnDesignatedBranch());
        assertEquals("PROJ-777", resTest.getDetectedPrefix());
        assertNotNull(resTest.getMatchedMapping());
        assertEquals("-test", resTest.getMatchedMapping().getBranchSuffix());

        // 3. With refs/heads/ prefix
        MultiBranchCommitAction.BranchDetectionResult resHeads = MultiBranchCommitAction.detectBranchContext("refs/heads/TASK-101-uat", mappings);
        assertTrue(resHeads.isOnDesignatedBranch());
        assertEquals("TASK-101", resHeads.getDetectedPrefix());
        assertEquals("TASK-101-uat", resHeads.getCurrentBranch());

        // 4. Custom prefix (non-standard Jira key) on designated branch
        MultiBranchCommitAction.BranchDetectionResult resCustom = MultiBranchCommitAction.detectBranchContext("my_custom_feat-dev", mappings);
        assertTrue(resCustom.isOnDesignatedBranch());
        assertEquals("my_custom_feat", resCustom.getDetectedPrefix());

        // 5. Non-designated branch with Jira issue key
        MultiBranchCommitAction.BranchDetectionResult resFeature = MultiBranchCommitAction.detectBranchContext("feature/TASK-101", mappings);
        assertFalse(resFeature.isOnDesignatedBranch());
        assertEquals("TASK-101", resFeature.getDetectedPrefix());
        assertNull(resFeature.getMatchedMapping());

        // 6. Base / target branch (e.g. deploy/test or main)
        MultiBranchCommitAction.BranchDetectionResult resBase = MultiBranchCommitAction.detectBranchContext("deploy/test", mappings);
        assertFalse(resBase.isOnDesignatedBranch());
        assertEquals("", resBase.getDetectedPrefix());
        assertNull(resBase.getMatchedMapping());

        MultiBranchCommitAction.BranchDetectionResult resMain = MultiBranchCommitAction.detectBranchContext("main", mappings);
        assertFalse(resMain.isOnDesignatedBranch());
        assertEquals("", resMain.getDetectedPrefix());

        // 7. Longest suffix match prioritization
        List<BranchMapping> multiMappings = new ArrayList<>(List.of(
                new BranchMapping("origin/dev", "dev", "-dev", true),
                new BranchMapping("origin/sub-dev", "sub-dev", "-sub-dev", true)
        ));
        MultiBranchCommitAction.BranchDetectionResult resLongest = MultiBranchCommitAction.detectBranchContext("TASK-5-sub-dev", multiMappings);
        assertTrue(resLongest.isOnDesignatedBranch());
        assertEquals("TASK-5", resLongest.getDetectedPrefix());
        assertEquals("-sub-dev", resLongest.getMatchedMapping().getBranchSuffix());

        // 8. Null and empty branch handling
        MultiBranchCommitAction.BranchDetectionResult resNull = MultiBranchCommitAction.detectBranchContext(null, mappings);
        assertFalse(resNull.isOnDesignatedBranch());
        assertEquals("", resNull.getDetectedPrefix());

        MultiBranchCommitAction.BranchDetectionResult resEmpty = MultiBranchCommitAction.detectBranchContext("   ", mappings);
        assertFalse(resEmpty.isOnDesignatedBranch());
        assertEquals("", resEmpty.getDetectedPrefix());
    }

    @Test
    public void testBranchBehindStatusAndResultItem() {
        // 1. Remote doesn't exist
        MultiBranchService.BranchBehindStatus noRemote = new MultiBranchService.BranchBehindStatus(false, 0, 0);
        assertFalse(noRemote.isRemoteExists());
        assertFalse(noRemote.isBehind());
        assertFalse(noRemote.isAhead());
        assertFalse(noRemote.isUpToDate());
        assertFalse(noRemote.isDiverged());

        // 2. Behind remote
        MultiBranchService.BranchBehindStatus behind = new MultiBranchService.BranchBehindStatus(true, 2, 0);
        assertTrue(behind.isRemoteExists());
        assertTrue(behind.isBehind());
        assertFalse(behind.isAhead());
        assertFalse(behind.isUpToDate());
        assertFalse(behind.isDiverged());
        assertEquals(2, behind.getBehindCount());
        assertEquals(0, behind.getAheadCount());

        // 3. Diverged (both ahead and behind)
        MultiBranchService.BranchBehindStatus diverged = new MultiBranchService.BranchBehindStatus(true, 3, 1);
        assertTrue(diverged.isRemoteExists());
        assertTrue(diverged.isBehind());
        assertFalse(diverged.isAhead());
        assertTrue(diverged.isDiverged());
        assertEquals(3, diverged.getBehindCount());
        assertEquals(1, diverged.getAheadCount());

        // 4. Ahead of remote (fast-forward)
        MultiBranchService.BranchBehindStatus ahead = new MultiBranchService.BranchBehindStatus(true, 0, 4);
        assertTrue(ahead.isRemoteExists());
        assertFalse(ahead.isBehind());
        assertTrue(ahead.isAhead());
        assertFalse(ahead.isDiverged());

        // 5. Up to date
        MultiBranchService.BranchBehindStatus upToDate = new MultiBranchService.BranchBehindStatus(true, 0, 0);
        assertTrue(upToDate.isRemoteExists());
        assertFalse(upToDate.isBehind());
        assertFalse(upToDate.isAhead());
        assertTrue(upToDate.isUpToDate());

        // 6. Test MultiBranchResultItem push details
        MultiBranchResultItem item1 = new MultiBranchResultItem("TASK-1-dev", "deploy/dev", "abc1234", true, "https://url", null);
        assertEquals("Pushed to origin", item1.getPushDetails());

        MultiBranchResultItem item2 = new MultiBranchResultItem("TASK-1-dev", "deploy/dev", "abc1234", false, "https://url", null);
        assertEquals("Local only", item2.getPushDetails());

        MultiBranchResultItem item3 = new MultiBranchResultItem("TASK-1-dev", "deploy/dev", "abc1234", true, "Pushed with --force-with-lease", "https://url", null);
        assertEquals("Pushed with --force-with-lease", item3.getPushDetails());

        MultiBranchResultItem item4 = new MultiBranchResultItem("TASK-1-dev", "deploy/dev", "abc1234", false, "Skipped (branch is behind remote by 2 commit(s))", null, null);
        assertEquals("Skipped (branch is behind remote by 2 commit(s))", item4.getPushDetails());

        // 7. Live repo check
        MultiBranchService.BranchBehindStatus liveMain = MultiBranchService.checkBranchBehindStatus(new java.io.File("."), "main");
        assertTrue(liveMain.isRemoteExists());

        MultiBranchService.BranchBehindStatus liveNonExistent = MultiBranchService.checkBranchBehindStatus(new java.io.File("."), "non_existent_branch_99999");
        assertFalse(liveNonExistent.isRemoteExists());
        assertFalse(liveNonExistent.isBehind());
    }

    @Test
    public void testStateAndConfigCopyAndEquality() {
        MultiBranchSettings.State state = new MultiBranchSettings.State();
        assertEquals(4, state.branchMappings.size());

        state.checkoutBranch = "deploy/prod";
        MultiBranchSettings.State stateCopy = state.copy();
        assertEquals(state, stateCopy);
        assertEquals(state.hashCode(), stateCopy.hashCode());

        stateCopy.checkoutBranch = "deploy/staging";
        assertNotEquals(state, stateCopy);

        // toConfig
        MultiBranchSettings settings = new MultiBranchSettings();
        settings.loadState(state);
        MultiBranchConfig config = MultiBranchConfig.fromSettings(settings);
        assertEquals("deploy/prod", config.getCheckoutBranch());
        assertEquals(4, config.getBranchMappings().size());

        // config copy
        MultiBranchConfig configCopy = config.copy();
        assertEquals(config.getCheckoutBranch(), configCopy.getCheckoutBranch());
        configCopy.setCheckoutBranch("deploy/dev");
        assertEquals("deploy/dev", configCopy.getCheckoutBranch());
        assertEquals("deploy/prod", config.getCheckoutBranch());
    }

    @Test
    public void testResultItemFields() {
        MultiBranchResultItem itemSuccess = new MultiBranchResultItem(
                "TASK-101-dev", "deploy/dev", "abc1234", true, "Pushed to origin",
                "https://gitlab.example.com/group/repo/-/merge_requests/42", null
        );
        assertTrue(itemSuccess.isSuccess());
        assertFalse(itemSuccess.hasErrors());
        assertEquals("TASK-101-dev", itemSuccess.getBranchName());
        assertEquals("deploy/dev", itemSuccess.getTargetBranch());
        assertEquals("abc1234", itemSuccess.getCommitHash());
        assertTrue(itemSuccess.isPushed());
        assertEquals("Pushed to origin", itemSuccess.getPushDetails());
        assertEquals("https://gitlab.example.com/group/repo/-/merge_requests/42", itemSuccess.getMrUrl());
        assertNull(itemSuccess.getErrorMessage());

        MultiBranchResultItem itemFailed = new MultiBranchResultItem(
                "TASK-101-test", "deploy/test", "def5678", false, "Push rejected",
                "https://gitlab.example.com/group/repo/-/merge_requests/43", "Remote rejected push"
        );
        assertFalse(itemFailed.isSuccess());
        assertTrue(itemFailed.hasErrors());
        assertEquals("Remote rejected push", itemFailed.getErrorMessage());
    }

    @Test
    public void testGitLabHostNormalization() {
        assertEquals("", GitLabTokenManager.normalizeHost(null));
        assertEquals("", GitLabTokenManager.normalizeHost("   "));
        assertEquals("gitlab.com", GitLabTokenManager.normalizeHost("gitlab.com"));
        assertEquals("gitlab.com", GitLabTokenManager.normalizeHost("https://gitlab.com"));
        assertEquals("gitlab.com", GitLabTokenManager.normalizeHost("https://gitlab.com/"));
        assertEquals("gitlab.com", GitLabTokenManager.normalizeHost("HTTPS://GITLAB.COM/API/V4"));
        assertEquals("gitlab.corp.local:8080", GitLabTokenManager.normalizeHost("http://gitlab.corp.local:8080/api/v4"));
        assertEquals("gitlab.example.com", GitLabTokenManager.normalizeHost("git@gitlab.example.com:group/project.git"));
        assertEquals("gitlab.example.com", GitLabTokenManager.normalizeHost("ssh://git@gitlab.example.com:2222/group/project.git"));
    }

    @Test
    public void testGitLabTokenManagerAccountName() {
        assertEquals("GitLabToken", GitLabTokenManager.getAccountName(null));
        assertEquals("GitLabToken", GitLabTokenManager.getAccountName(""));
        assertEquals("GitLabToken_gitlab.com", GitLabTokenManager.getAccountName("https://gitlab.com"));
        assertEquals("GitLabToken_gitlab.corp.local", GitLabTokenManager.getAccountName("gitlab.corp.local"));
        assertEquals("GitLabToken_gitlab.corp.local:8080", GitLabTokenManager.getAccountName("http://gitlab.corp.local:8080/"));
    }

    @Test
    public void testGitLabTokenManagerPerHostStorage() {
        GitLabTokenManager.testFallbackStorage.clear();

        // Initially empty
        assertNull(GitLabTokenManager.getToken("https://gitlab.company-a.com"));
        assertNull(GitLabTokenManager.getToken("https://gitlab.company-b.com"));

        // Save token for Company A
        GitLabTokenManager.setToken("https://gitlab.company-a.com", "token-aaa-123");
        assertEquals("token-aaa-123", GitLabTokenManager.getToken("https://gitlab.company-a.com"));
        assertEquals("token-aaa-123", GitLabTokenManager.getToken("gitlab.company-a.com"));
        assertNull(GitLabTokenManager.getToken("https://gitlab.company-b.com"));

        // Save token for Company B
        GitLabTokenManager.setToken("http://gitlab.company-b.com:8443", "token-bbb-456");
        assertEquals("token-aaa-123", GitLabTokenManager.getToken("https://gitlab.company-a.com"));
        assertEquals("token-bbb-456", GitLabTokenManager.getToken("http://gitlab.company-b.com:8443/api/v4"));
        assertNull(GitLabTokenManager.getToken("https://gitlab.com"));

        // Save token for gitlab.com
        GitLabTokenManager.setToken("gitlab.com", "token-cloud-789");
        assertEquals("token-aaa-123", GitLabTokenManager.getToken("gitlab.company-a.com"));
        assertEquals("token-bbb-456", GitLabTokenManager.getToken("gitlab.company-b.com:8443"));
        assertEquals("token-cloud-789", GitLabTokenManager.getToken("https://gitlab.com"));

        // Clearing token for Company A does not affect Company B or gitlab.com
        GitLabTokenManager.setToken("gitlab.company-a.com", "");
        assertNull(GitLabTokenManager.getToken("gitlab.company-a.com"));
        assertEquals("token-bbb-456", GitLabTokenManager.getToken("gitlab.company-b.com:8443"));
        assertEquals("token-cloud-789", GitLabTokenManager.getToken("https://gitlab.com"));
    }

    @Test
    public void testGitLabTokenManagerLegacyFallbackAndMigration() {
        GitLabTokenManager.testFallbackStorage.clear();

        // Simulate legacy token in store
        GitLabTokenManager.testFallbackStorage.put(GitLabTokenManager.LEGACY_ACCOUNT_NAME, "legacy-token-xyz");

        // Requesting token for any host falls back to legacy token
        assertEquals("legacy-token-xyz", GitLabTokenManager.getToken("gitlab.migration-test.com"));

        // Saving for specific host migrates it and clears legacy to prevent leakage to other hosts
        GitLabTokenManager.setToken("gitlab.migration-test.com", "legacy-token-xyz");
        assertEquals("legacy-token-xyz", GitLabTokenManager.getToken("gitlab.migration-test.com"));

        // Other host no longer sees the migrated legacy token
        assertNull(GitLabTokenManager.getToken("gitlab.another-host.com"));
    }

    @Test
    public void testGitLabTokenManagerHostDetection() {
        // Override takes precedence
        String host = GitLabTokenManager.detectHost((java.io.File) null, "https://override.gitlab.local");
        assertEquals("https://override.gitlab.local", host);

        // Repo dir detection
        String detectedFromRepo = GitLabTokenManager.detectHost(new java.io.File("."), null);
        assertNotNull(detectedFromRepo);
    }

    @Test
    public void testGitLabMrWebUrlExtractionVsAuthorUrl() {
        // Real-world GitLab API response for Merge Request where author has web_url before the MR web_url
        String gitLabMrResponse = "{\n" +
                "  \"id\": 9876,\n" +
                "  \"iid\": 42,\n" +
                "  \"project_id\": 10,\n" +
                "  \"title\": \"[TASK-101] Feature commit\",\n" +
                "  \"description\": \"Auto-created by Multi-Branch Plugin\",\n" +
                "  \"state\": \"opened\",\n" +
                "  \"author\": {\n" +
                "    \"id\": 5,\n" +
                "    \"username\": \"altuzh\",\n" +
                "    \"name\": \"Alex\",\n" +
                "    \"state\": \"active\",\n" +
                "    \"web_url\": \"https://gitlab.bft.local/altuzh\"\n" +
                "  },\n" +
                "  \"assignees\": [\n" +
                "    {\n" +
                "      \"id\": 5,\n" +
                "      \"username\": \"altuzh\",\n" +
                "      \"web_url\": \"https://gitlab.bft.local/altuzh\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"source_branch\": \"TASK-101-dev\",\n" +
                "  \"target_branch\": \"deploy/dev\",\n" +
                "  \"web_url\": \"https://gitlab.bft.local/group/project/-/merge_requests/42\",\n" +
                "  \"merge_status\": \"can_be_merged\"\n" +
                "}";

        // extractMrWebUrl must extract the MR web_url, NOT the author's user profile URL!
        String mrUrl = GitLabApiService.extractMrWebUrl(gitLabMrResponse);
        assertNotNull(mrUrl);
        assertEquals("https://gitlab.bft.local/group/project/-/merge_requests/42", mrUrl);
        assertNotEquals("https://gitlab.bft.local/altuzh", mrUrl);

        // Top level extraction should also get the MR web_url
        String topLevelUrl = GitLabApiService.extractTopLevelString(gitLabMrResponse, "web_url");
        assertEquals("https://gitlab.bft.local/group/project/-/merge_requests/42", topLevelUrl);

        // Top level iid extraction should get 42
        int topLevelIid = GitLabApiService.extractTopLevelInt(gitLabMrResponse, "iid");
        assertEquals(42, topLevelIid);
    }

    @Test
    public void testGitLabMrListResponseUrlExtraction() {
        // When checking existing MR, response is an array of objects
        String listResponse = "[\n" +
                "  {\n" +
                "    \"id\": 9876,\n" +
                "    \"iid\": 15,\n" +
                "    \"author\": {\n" +
                "      \"web_url\": \"https://gitlab.corp.local/someuser\"\n" +
                "    },\n" +
                "    \"web_url\": \"https://gitlab.corp.local/team/repo/-/merge_requests/15\"\n" +
                "  }\n" +
                "]";

        String mrUrl = GitLabApiService.extractMrWebUrl(listResponse);
        assertEquals("https://gitlab.corp.local/team/repo/-/merge_requests/15", mrUrl);

        int iid = GitLabApiService.extractTopLevelInt(listResponse, "iid");
        assertEquals(15, iid);
    }

    @Test
    public void testGitLabConflict409IidExtraction() {
        String conflictBody = "{\"message\":[\"Another open merge request already exists for this source branch: !88\"]}";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("!(\\d+)").matcher(conflictBody);
        assertTrue(m.find());
        assertEquals(88, Integer.parseInt(m.group(1)));

        GitLabApiService.GitLabProjectInfo info = GitLabApiService.parseProjectInfo("git@gitlab.bft.local:group/proj.git", null);
        assertNotNull(info);
        assertEquals("https://gitlab.bft.local/group/proj/-/merge_requests/88", info.getWebProjectUrl() + "/-/merge_requests/88");
    }

    @Test
    public void testResultItemFailureStates() {
        // Success case
        MultiBranchResultItem itemOk = new MultiBranchResultItem("TASK-1-dev", "deploy/dev", "sha123", true, "Pushed to origin", "https://mr/1", null);
        assertTrue(itemOk.isSuccess());
        assertFalse(itemOk.hasErrors());

        // Error message present -> failure
        MultiBranchResultItem itemErr = new MultiBranchResultItem("TASK-1-test", "deploy/test", null, false, "Not pushed", null, "Failed to apply changes: conflict");
        assertFalse(itemErr.isSuccess());
        assertTrue(itemErr.hasErrors());
        assertEquals("Failed to apply changes: conflict", itemErr.getErrorMessage());

        // Push failed details -> failure
        MultiBranchResultItem itemPushFail = new MultiBranchResultItem("TASK-1-prod", "deploy/prod", "sha456", false, "Push failed: remote rejected", null, null);
        assertFalse(itemPushFail.isSuccess());
        assertTrue(itemPushFail.hasErrors());
    }

    @Test
    public void testSettingsStateLastTaskPrefix() {
        MultiBranchSettings.State state = new MultiBranchSettings.State();
        assertEquals("", state.lastTaskPrefix);

        state.lastTaskPrefix = "FEATURE-42";
        MultiBranchSettings.State copy = state.copy();
        assertEquals("FEATURE-42", copy.lastTaskPrefix);
        assertEquals(state, copy);
        assertEquals(state.hashCode(), copy.hashCode());

        MultiBranchSettings settings = new MultiBranchSettings(null);
        settings.loadState(state);
        MultiBranchConfig config = settings.toConfig(null);
        assertEquals("FEATURE-42", config.getTaskPrefix());
    }

    @Test
    public void testSyncDocumentsToDiskDoesNotCrash() {
        // Calling with null project should safely no-op without NPE
        MultiBranchService.syncDocumentsToDisk(null);
    }

    @Test
    public void testVersionComparison() {
        assertTrue(MultiBranchReloadAction.compareVersions("1.1.4", "1.1.3") > 0);
        assertTrue(MultiBranchReloadAction.compareVersions("1.2.0", "1.1.9") > 0);
        assertTrue(MultiBranchReloadAction.compareVersions("2.0.0", "1.9.9") > 0);
        assertEquals(0, MultiBranchReloadAction.compareVersions("1.1.3", "1.1.3"));
        assertTrue(MultiBranchReloadAction.compareVersions("1.1.2", "1.1.3") < 0);
        assertTrue(MultiBranchReloadAction.compareVersions("1.1.3-beta", "1.1.3") == 0);
    }

    @Test
    public void testGetVersionFromZip() {
        assertEquals("1.1.3", MultiBranchReloadAction.getVersionFromZip(new File("git-multibranch-plugin-1.1.3.zip")));
        assertEquals("1.2.0", MultiBranchReloadAction.getVersionFromZip(new File("/path/to/git-multibranch-plugin-1.2.0.zip")));
        assertNull(MultiBranchReloadAction.getVersionFromZip(new File("unknown-file.zip")));
    }
}
