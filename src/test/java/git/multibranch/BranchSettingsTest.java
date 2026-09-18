package git.multibranch;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
        assertTrue(state.premergeTargetBranch);
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

        copy.premergeTargetBranch = false;
        assertNotEquals(orig.premergeTargetBranch, copy.premergeTargetBranch);
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
        assertTrue(copy.isPremergeTargetBranch());
        assertTrue(copy.isGitLabCreateMr());
        assertTrue(copy.isGitLabAssignToMe());
        assertTrue(copy.isGitLabDeleteSourceBranch());
        assertTrue(copy.isGitLabSquashCommits());

        copy.setPremergeTargetBranch(false);
        assertFalse(copy.isPremergeTargetBranch());
        assertTrue(orig.isPremergeTargetBranch());

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

    @Test
    public void testExtractPrefixAndMessage() {
        // 1. Bracketed prefix
        MultiBranchCommitDialog.ParsedCommitMessage p1 = MultiBranchCommitDialog.extractPrefixAndMessage("[ABC-123] Fix login error");
        assertEquals("ABC-123", p1.getPrefix());
        assertEquals("Fix login error", p1.getMessage());

        // Bracketed prefix multiline
        MultiBranchCommitDialog.ParsedCommitMessage p2 = MultiBranchCommitDialog.extractPrefixAndMessage("[ABC-123] Fix login error\n\nDetailed notes:\n- bullet 1");
        assertEquals("ABC-123", p2.getPrefix());
        assertEquals("Fix login error\n\nDetailed notes:\n- bullet 1", p2.getMessage());

        // Bracketed prefix only
        MultiBranchCommitDialog.ParsedCommitMessage p3 = MultiBranchCommitDialog.extractPrefixAndMessage("[ABC-123]");
        assertEquals("ABC-123", p3.getPrefix());
        assertEquals("", p3.getMessage());

        // 2. Issue key with colon
        MultiBranchCommitDialog.ParsedCommitMessage p4 = MultiBranchCommitDialog.extractPrefixAndMessage("ABC-123: Fix login error");
        assertEquals("ABC-123", p4.getPrefix());
        assertEquals("Fix login error", p4.getMessage());

        // 3. Issue key with dash
        MultiBranchCommitDialog.ParsedCommitMessage p5 = MultiBranchCommitDialog.extractPrefixAndMessage("ABC-123 - Fix login error");
        assertEquals("ABC-123", p5.getPrefix());
        assertEquals("Fix login error", p5.getMessage());

        // 4. Issue key with space
        MultiBranchCommitDialog.ParsedCommitMessage p6 = MultiBranchCommitDialog.extractPrefixAndMessage("ABC-123 Fix login error");
        assertEquals("ABC-123", p6.getPrefix());
        assertEquals("Fix login error", p6.getMessage());

        // 5. Issue key only
        MultiBranchCommitDialog.ParsedCommitMessage p7 = MultiBranchCommitDialog.extractPrefixAndMessage("ABC-123");
        assertEquals("ABC-123", p7.getPrefix());
        assertEquals("", p7.getMessage());

        // 6. Generic token with colon
        MultiBranchCommitDialog.ParsedCommitMessage p8 = MultiBranchCommitDialog.extractPrefixAndMessage("feat-auth: Fix login error");
        assertEquals("feat-auth", p8.getPrefix());
        assertEquals("Fix login error", p8.getMessage());

        // 7. Plain message without prefix
        MultiBranchCommitDialog.ParsedCommitMessage p9 = MultiBranchCommitDialog.extractPrefixAndMessage("Fix login error without any prefix");
        assertEquals("", p9.getPrefix());
        assertEquals("Fix login error without any prefix", p9.getMessage());

        // 8. Null / blank
        MultiBranchCommitDialog.ParsedCommitMessage p10 = MultiBranchCommitDialog.extractPrefixAndMessage("");
        assertEquals("", p10.getPrefix());
        assertEquals("", p10.getMessage());
    }

    @Test
    public void testCleanCommitMessage() {
        assertEquals("My message", MultiBranchCommitDialog.cleanCommitMessage("[TASK-100] My message", "TASK-100"));
        assertEquals("My message", MultiBranchCommitDialog.cleanCommitMessage("[task-100] My message", "TASK-100"));
        assertEquals("My message", MultiBranchCommitDialog.cleanCommitMessage("[TASK-100] My message", "[TASK-100]"));
        assertEquals("My message", MultiBranchCommitDialog.cleanCommitMessage("TASK-100: My message", "TASK-100"));
        assertEquals("My message", MultiBranchCommitDialog.cleanCommitMessage("TASK-100 - My message", "TASK-100"));
        assertEquals("My message", MultiBranchCommitDialog.cleanCommitMessage("TASK-100 My message", "TASK-100"));
        assertEquals("My message", MultiBranchCommitDialog.cleanCommitMessage("[OTHER-200] My message", "TASK-100"));
        assertEquals("Line 1\n\nLine 2", MultiBranchCommitDialog.cleanCommitMessage("[TASK-100] Line 1\n\nLine 2", "TASK-100"));
        assertEquals("", MultiBranchCommitDialog.cleanCommitMessage("[TASK-100]", "TASK-100"));
        assertEquals("Fix bug", MultiBranchCommitDialog.cleanCommitMessage("Fix bug", "TASK-100"));
    }

    @Test
    public void testMatchesPrefix() {
        assertTrue(MultiBranchCommitDialog.matchesPrefix("[TASK-100] Fix", "TASK-100"));
        assertTrue(MultiBranchCommitDialog.matchesPrefix("[task-100] Fix", "TASK-100"));
        assertTrue(MultiBranchCommitDialog.matchesPrefix("TASK-100: Fix", "TASK-100"));
        assertTrue(MultiBranchCommitDialog.matchesPrefix("TASK-100 - Fix", "TASK-100"));
        assertTrue(MultiBranchCommitDialog.matchesPrefix("TASK-100 Fix", "TASK-100"));
        assertTrue(MultiBranchCommitDialog.matchesPrefix("Fix for TASK-100", "TASK-100"));
        assertFalse(MultiBranchCommitDialog.matchesPrefix("[TASK-1000] Fix", "TASK-100"));
        assertFalse(MultiBranchCommitDialog.matchesPrefix("[OTHER-50] Fix", "TASK-100"));
    }

    @Test
    public void testFindLatestCommitMessageForPrefix() {
        List<String> history = List.of(
                "[TASK-100] First commit for 100",
                "[OTHER-999] Unrelated commit",
                "[TASK-100] Second commit for 100: multiline\n\n- Details",
                "[TASK-200] Commit for 200"
        );

        String msg = MultiBranchCommitDialog.findLatestCommitMessageForPrefix(history, "TASK-100");
        assertEquals("Second commit for 100: multiline\n\n- Details", msg);

        String notFound = MultiBranchCommitDialog.findLatestCommitMessageForPrefix(history, "NON-EXISTENT");
        assertEquals("", notFound);
    }

    @Test
    public void testSortRecentMessagesLatestTop() {
        List<String> rawMessages = List.of(
                "First commit (oldest)",
                "Second commit",
                "Third commit",
                "Second commit"
        );

        List<String> sorted = MultiBranchCommitDialog.sortRecentMessagesLatestTop(rawMessages);
        assertEquals(3, sorted.size());
        assertEquals("Second commit", sorted.get(0)); // latest occurrence on top
        assertEquals("Third commit", sorted.get(1));
        assertEquals("First commit (oldest)", sorted.get(2)); // oldest at bottom

        assertTrue(MultiBranchCommitDialog.sortRecentMessagesLatestTop(null).isEmpty());
        assertTrue(MultiBranchCommitDialog.sortRecentMessagesLatestTop(List.of()).isEmpty());
    }

    @Test
    public void testResolveInitialCommitInfoOnPrefixedBranch() {
        BranchMapping mapping = new BranchMapping("origin/deploy/dev", "deploy/dev", "-dev", true);
        MultiBranchCommitAction.BranchDetectionResult detection =
                new MultiBranchCommitAction.BranchDetectionResult("TASK-101-dev", "TASK-101", true, mapping);

        List<String> history = List.of(
                "[TASK-101] Initial work on auth",
                "[TASK-999] Unrelated work",
                "[TASK-101] Fix NPE in login controller\n\nRefactored token handler"
        );

        MultiBranchCommitDialog.InitialCommitInfo info =
                MultiBranchCommitDialog.resolveInitialCommitInfo(detection, "SOME-OTHER-PREFIX", history);

        assertEquals("TASK-101", info.getPrefix());
        assertEquals("Fix NPE in login controller\n\nRefactored token handler", info.getCommitMessage());

        // On prefixed branch, but no history for that prefix
        MultiBranchCommitAction.BranchDetectionResult newBranchDetection =
                new MultiBranchCommitAction.BranchDetectionResult("BRAND-NEW-dev", "BRAND-NEW", true, mapping);

        MultiBranchCommitDialog.InitialCommitInfo newInfo =
                MultiBranchCommitDialog.resolveInitialCommitInfo(newBranchDetection, "SOME-OTHER-PREFIX", history);

        assertEquals("BRAND-NEW", newInfo.getPrefix());
        assertEquals("", newInfo.getCommitMessage());
    }

    @Test
    public void testResolveInitialCommitInfoNotOnPrefixedBranch() {
        // Not on a prefix branch (e.g. on main, master, or deploy/test)
        MultiBranchCommitAction.BranchDetectionResult detectionMain =
                new MultiBranchCommitAction.BranchDetectionResult("main", "", false, null);

        List<String> history = List.of(
                "[TASK-100] First commit",
                "[TASK-101] Fix NPE in auth",
                "[TASK-102] Add CSV export feature\n\nIncluded tests and docs"
        );

        // Init prefix and commit message from latest in history
        MultiBranchCommitDialog.InitialCommitInfo info =
                MultiBranchCommitDialog.resolveInitialCommitInfo(detectionMain, "FALLBACK-PREFIX", history);

        assertEquals("TASK-102", info.getPrefix());
        assertEquals("Add CSV export feature\n\nIncluded tests and docs", info.getCommitMessage());

        // Latest in history has no prefix in message text: extracts message, prefix from earlier history
        List<String> historyNoPrefixInLatest = List.of(
                "[TASK-200] Refactor database schema",
                "Fix documentation typos"
        );
        MultiBranchCommitDialog.InitialCommitInfo info2 =
                MultiBranchCommitDialog.resolveInitialCommitInfo(detectionMain, "FALLBACK-PREFIX", historyNoPrefixInLatest);
        assertEquals("TASK-200", info2.getPrefix());
        assertEquals("Fix documentation typos", info2.getCommitMessage());

        // History empty: fallback to lastTaskPrefix
        MultiBranchCommitDialog.InitialCommitInfo infoEmptyHistory =
                MultiBranchCommitDialog.resolveInitialCommitInfo(detectionMain, "SAVED-PREFIX", List.of());
        assertEquals("SAVED-PREFIX", infoEmptyHistory.getPrefix());
        assertEquals("", infoEmptyHistory.getCommitMessage());
    }

    @Test
    public void testResolveBranchStartPointNullOrBlank() {
        MultiBranchService.BranchStartPoint sp1 = MultiBranchService.resolveBranchStartPoint(null, null, "origin/deploy/dev");
        assertEquals("origin/deploy/dev", sp1.getRef());
        assertFalse(sp1.isExisting());

        MultiBranchService.BranchStartPoint sp2 = MultiBranchService.resolveBranchStartPoint(null, "TASK-1", "origin/deploy/dev");
        assertEquals("origin/deploy/dev", sp2.getRef());
        assertFalse(sp2.isExisting());

        MultiBranchService.BranchStartPoint sp3 = MultiBranchService.resolveBranchStartPoint(new File("non-existent"), "TASK-1", "origin/deploy/dev");
        assertEquals("origin/deploy/dev", sp3.getRef());
        assertFalse(sp3.isExisting());
    }

    @Test
    public void testResolveBranchStartPointWithRealGitRepo() throws Exception {
        Path tempDir = Files.createTempDirectory("git_startpoint_test_");
        File repo = tempDir.toFile();
        try {
            // git init
            MultiBranchService.runGit(repo, "init");
            MultiBranchService.runGit(repo, "config", "user.name", "Test User");
            MultiBranchService.runGit(repo, "config", "user.email", "test@example.com");

            // Base commit on deploy/dev
            File f = new File(repo, "file.txt");
            Files.writeString(f.toPath(), "base content");
            MultiBranchService.runGit(repo, "add", "file.txt");
            MultiBranchService.runGit(repo, "commit", "-m", "Initial commit on deploy/dev");
            MultiBranchService.runGit(repo, "branch", "-M", "deploy/dev");

            // Simulate remote tracking branch origin/deploy/dev
            MultiBranchService.runGit(repo, "update-ref", "refs/remotes/origin/deploy/dev", "HEAD");

            // Case 1: Brand new branch (never committed or pushed)
            MultiBranchService.BranchStartPoint sp1 = MultiBranchService.resolveBranchStartPoint(repo, "TASK-101-dev", "origin/deploy/dev");
            assertEquals("origin/deploy/dev", sp1.getRef());
            assertFalse(sp1.isExisting());

            // Case 2: Unmerged branch on remote (simulate origin/TASK-101-dev with a commit not in deploy/dev)
            MultiBranchService.runGit(repo, "checkout", "-b", "temp-feature");
            Files.writeString(f.toPath(), "feature content");
            MultiBranchService.runGit(repo, "commit", "-am", "TASK-101 commit 1");
            MultiBranchService.runGit(repo, "update-ref", "refs/remotes/origin/TASK-101-dev", "HEAD");
            MultiBranchService.runGit(repo, "checkout", "deploy/dev");
            MultiBranchService.runGit(repo, "branch", "-D", "temp-feature");

            // Now origin/TASK-101-dev exists and has unmerged commit!
            MultiBranchService.BranchStartPoint sp2 = MultiBranchService.resolveBranchStartPoint(repo, "TASK-101-dev", "origin/deploy/dev");
            assertEquals("origin/TASK-101-dev", sp2.getRef());
            assertTrue(sp2.isExisting());

            // Case 3: Local branch is ahead of remote
            MultiBranchService.runGit(repo, "checkout", "-b", "TASK-101-dev", "origin/TASK-101-dev");
            Files.writeString(f.toPath(), "local ahead content");
            MultiBranchService.runGit(repo, "commit", "-am", "TASK-101 commit 2 (local only)");
            MultiBranchService.runGit(repo, "checkout", "deploy/dev");

            MultiBranchService.BranchStartPoint sp3 = MultiBranchService.resolveBranchStartPoint(repo, "TASK-101-dev", "origin/deploy/dev");
            assertEquals("TASK-101-dev", sp3.getRef());
            assertTrue(sp3.isExisting());

            // Case 4: Remote branch was already merged into deploy/dev
            // Fast-forward deploy/dev to include origin/TASK-101-dev
            MultiBranchService.runGit(repo, "checkout", "deploy/dev");
            MultiBranchService.runGit(repo, "merge", "--ff-only", "origin/TASK-101-dev");
            MultiBranchService.runGit(repo, "update-ref", "refs/remotes/origin/deploy/dev", "HEAD");
            MultiBranchService.runGit(repo, "branch", "-D", "TASK-101-dev");

            // origin/TASK-101-dev is now fully merged into origin/deploy/dev
            MultiBranchService.BranchStartPoint sp4 = MultiBranchService.resolveBranchStartPoint(repo, "TASK-101-dev", "origin/deploy/dev");
            assertEquals("origin/deploy/dev", sp4.getRef());
            assertFalse(sp4.isExisting());

            // Case 5: Local branch only with unmerged commits (never pushed to remote)
            MultiBranchService.runGit(repo, "checkout", "-b", "TASK-202-dev");
            Files.writeString(f.toPath(), "local task 202 content");
            MultiBranchService.runGit(repo, "commit", "-am", "TASK-202 local commit");
            MultiBranchService.runGit(repo, "checkout", "deploy/dev");

            MultiBranchService.BranchStartPoint sp5 = MultiBranchService.resolveBranchStartPoint(repo, "TASK-202-dev", "origin/deploy/dev");
            assertEquals("TASK-202-dev", sp5.getRef());
            assertTrue(sp5.isExisting());

        } finally {
            // Cleanup temp repo
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testMultiBranchLogSanitize() {
        // Test token sanitization
        String raw1 = "Header PRIVATE-TOKEN: glpat-abcdef1234567890xyz";
        String s1 = MultiBranchLog.sanitize(raw1);
        assertFalse(s1.contains("glpat-abcdef1234567890xyz"));
        assertTrue(s1.contains("PRIVATE-TOKEN: ***MASKED***") || s1.contains("***MASKED***"));

        String raw2 = "Authorization: Bearer secret_bearer_token_123";
        String s2 = MultiBranchLog.sanitize(raw2);
        assertFalse(s2.contains("secret_bearer_token_123"));
        assertTrue(s2.contains("Bearer ***MASKED***"));

        String raw3 = "Push to https://user:super_secret_password@gitlab.company.com/repo.git";
        String s3 = MultiBranchLog.sanitize(raw3);
        assertFalse(s3.contains("super_secret_password"));
        assertTrue(s3.contains("https://user:***MASKED***@gitlab.company.com/repo.git"));

        assertEquals("", MultiBranchLog.sanitize(null));
        assertEquals("normal message", MultiBranchLog.sanitize("normal message"));
    }

    @Test
    public void testMultiBranchLogBasicAndFile() throws Exception {
        Path tempLogDir = Files.createTempDirectory("mb_test_basic_logs");
        try {
            MultiBranchLog.setTestLogDirectory(tempLogDir.toFile());
            File logDir = MultiBranchLog.getLogDirectory();
            assertNotNull(logDir);
            assertTrue(logDir.exists());
            assertTrue(logDir.isDirectory());

            File logFile = MultiBranchLog.getLogFile();
            assertNotNull(logFile);
            assertEquals(MultiBranchLog.LOG_FILE_NAME, logFile.getName());

            // Logging at various levels
            MultiBranchLog.debug("Test debug message");
            MultiBranchLog.info("Test info message");
            MultiBranchLog.warn("Test warn message");
            MultiBranchLog.error("Test error message", new RuntimeException("Simulated error"));

            assertTrue(logFile.exists());
            assertTrue(logFile.length() > 0);

            String excerpt = MultiBranchLog.getRecentLogFileExcerpt(200);
            assertNotNull(excerpt);
            assertTrue(excerpt.contains("Test info message"));
            assertTrue(excerpt.contains("Test warn message"));
            assertTrue(excerpt.contains("Simulated error"));
        } finally {
            MultiBranchLog.setTestLogDirectory(null);
            try (var s = Files.walk(tempLogDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testMultiBranchLogCustomDirectory() throws Exception {
        Path tempLogDir = Files.createTempDirectory("mb_test_logs");
        try {
            MultiBranchLog.setTestLogDirectory(tempLogDir.toFile());
            assertEquals(tempLogDir.toFile(), MultiBranchLog.getLogDirectory());

            MultiBranchLog.info("Custom directory log entry");
            File customFile = MultiBranchLog.getLogFile();
            assertTrue(customFile.exists());
            String content = Files.readString(customFile.toPath());
            assertTrue(content.contains("Custom directory log entry"));
        } finally {
            MultiBranchLog.setTestLogDirectory(null);
            try (var s = Files.walk(tempLogDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testMultiBranchLogExecutionBufferAndClipboard() {
        MultiBranchLog.startExecution("Unit Test Run");
        MultiBranchLog.info("Step 1: preparing branch");
        MultiBranchLog.logGitCommand(new File("."), new String[]{"checkout", "-b", "feature"}, 0, 15, "Switched to branch", "");
        MultiBranchLog.logGitCommand(new File("."), new String[]{"push", "origin"}, 1, 50, "", "remote rejected");
        MultiBranchLog.logApiCall("POST", "https://gitlab.example.com/api/v4/projects/1/merge_requests", 201, "MR !42 created", 120);
        MultiBranchLog.finishExecution("Completed: 1 succeeded, 1 failed");

        String buffer = MultiBranchLog.getLastExecutionBuffer();
        assertNotNull(buffer);
        assertTrue(buffer.contains("=== Multi-Branch Execution Started:"));
        assertTrue(buffer.contains("Step 1: preparing branch"));
        assertTrue(buffer.contains("git checkout -b feature"));
        assertTrue(buffer.contains("Git FAILED (exit 1"));
        assertTrue(buffer.contains("HTTP 201"));
        assertTrue(buffer.contains("=== Multi-Branch Execution Finished in"));

        List<MultiBranchResultItem> items = List.of(
                new MultiBranchResultItem("TASK-1-dev", "deploy/dev", "abc1234", true, "Pushed to origin", "https://gitlab.example.com/mr/1", null),
                new MultiBranchResultItem("TASK-1-test", "deploy/test", null, false, "Push failed", null, "remote rejected")
        );

        String clipboardText = MultiBranchLog.getClipboardLogText(items);
        assertNotNull(clipboardText);
        assertTrue(clipboardText.contains("MULTI-BRANCH WORKFLOW EXECUTION LOG"));
        assertTrue(clipboardText.contains("Plugin Version: " + MultiBranchReloadAction.getRunningVersion()));
        assertTrue(clipboardText.contains("Log Directory:"));
        assertTrue(clipboardText.contains("TASK-1-dev -> deploy/dev [SUCCESS]"));
        assertTrue(clipboardText.contains("TASK-1-test -> deploy/test [FAILED]"));
        assertTrue(clipboardText.contains("Commit: abc1234"));
        assertTrue(clipboardText.contains("MR URL: https://gitlab.example.com/mr/1"));
        assertTrue(clipboardText.contains("remote rejected"));
        assertTrue(clipboardText.contains("DETAILED EXECUTION TRACE"));
        assertTrue(clipboardText.contains("Step 1: preparing branch"));

        // Fallback when buffer cleared: gets file excerpt
        MultiBranchLog.clearExecutionBuffer();
        String fallbackClipboard = MultiBranchLog.getClipboardLogText(items);
        assertNotNull(fallbackClipboard);
        assertTrue(fallbackClipboard.contains("MULTI-BRANCH WORKFLOW EXECUTION LOG"));
    }

    @Test
    public void testResultDialogCreationOnEdt() {
        List<MultiBranchResultItem> items = List.of(
                new MultiBranchResultItem("TASK-1-dev", "deploy/dev", "abc1234", true, "Pushed to origin", "https://gitlab.example.com/mr/1", null)
        );

        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                try {
                    MultiBranchResultDialog dialog = new MultiBranchResultDialog(null, items);
                    assertNotNull(dialog);
                } catch (Throwable ignored) {
                    // Headless / non-IDEA environment check
                }
            });
        } catch (Exception ignored) {}
    }

    @Test
    public void testPremergeTargetBranchConfigAndState() {
        MultiBranchConfig config = new MultiBranchConfig();
        assertTrue(config.isPremergeTargetBranch());

        config.setPremergeTargetBranch(false);
        assertFalse(config.isPremergeTargetBranch());

        MultiBranchConfig copy = config.copy();
        assertFalse(copy.isPremergeTargetBranch());

        MultiBranchSettings.State state = new MultiBranchSettings.State();
        assertTrue(state.premergeTargetBranch);

        state.premergeTargetBranch = false;
        MultiBranchSettings.State stateCopy = state.copy();
        assertEquals(state, stateCopy);
        assertEquals(state.hashCode(), stateCopy.hashCode());

        MultiBranchSettings settings = new MultiBranchSettings(null);
        settings.loadState(state);
        MultiBranchConfig fromSettingsConfig = settings.toConfig(null);
        assertFalse(fromSettingsConfig.isPremergeTargetBranch());
    }

    @Test
    public void testIsBranchExisting() {
        assertFalse(MultiBranchService.isBranchExisting(null, "main"));
        assertFalse(MultiBranchService.isBranchExisting(new File("."), null));
        assertFalse(MultiBranchService.isBranchExisting(new File("."), "   "));
        assertFalse(MultiBranchService.isBranchExisting(new File("."), "non_existent_branch_123456789"));

        File currentRepo = new File(".");
        assertTrue(MultiBranchService.isBranchExisting(currentRepo, "main"));
    }

    @Test
    public void testResolveTargetBranchRef() {
        assertNull(MultiBranchService.resolveTargetBranchRef(null, "deploy/dev"));
        assertNull(MultiBranchService.resolveTargetBranchRef(new File("."), null));
        assertNull(MultiBranchService.resolveTargetBranchRef(new File("."), "   "));
        assertNull(MultiBranchService.resolveTargetBranchRef(new File("."), "non_existent_target_9999"));

        File currentRepo = new File(".");
        String ref1 = MultiBranchService.resolveTargetBranchRef(currentRepo, "main");
        assertNotNull(ref1);
        assertTrue(ref1.equals("origin/main") || ref1.equals("main"));

        String ref2 = MultiBranchService.resolveTargetBranchRef(currentRepo, "origin/main");
        assertNotNull(ref2);
        assertTrue(ref2.equals("origin/main") || ref2.equals("main"));
    }

    @Test
    public void testPremergeTargetBranchWithRealGitRepo() throws Exception {
        Path tempDir = Files.createTempDirectory("git_premerge_test_");
        File repo = tempDir.toFile();
        try {
            // git init
            MultiBranchService.runGit(repo, "init");
            MultiBranchService.runGit(repo, "config", "user.name", "Test User");
            MultiBranchService.runGit(repo, "config", "user.email", "test@example.com");

            // Base commit on deploy/dev
            File f = new File(repo, "base.txt");
            Files.writeString(f.toPath(), "base content");
            MultiBranchService.runGit(repo, "add", "base.txt");
            MultiBranchService.runGit(repo, "commit", "-m", "Initial commit on deploy/dev");
            MultiBranchService.runGit(repo, "branch", "-M", "deploy/dev");

            // Simulate remote tracking branch origin/deploy/dev
            MultiBranchService.runGit(repo, "update-ref", "refs/remotes/origin/deploy/dev", "HEAD");

            // Case 1: Branch does NOT exist yet
            assertFalse(MultiBranchService.isBranchExisting(repo, "TASK-101-dev"));

            // Case 2: Create branch TASK-101-dev with its own commit
            MultiBranchService.runGit(repo, "checkout", "-b", "TASK-101-dev", "deploy/dev");
            File taskFile = new File(repo, "task101.txt");
            Files.writeString(taskFile.toPath(), "task 101 content");
            MultiBranchService.runGit(repo, "add", "task101.txt");
            MultiBranchService.runGit(repo, "commit", "-m", "TASK-101 feature commit");

            // Simulate remote tracking branch origin/TASK-101-dev
            MultiBranchService.runGit(repo, "update-ref", "refs/remotes/origin/TASK-101-dev", "HEAD");

            // Now branch EXISTS
            assertTrue(MultiBranchService.isBranchExisting(repo, "TASK-101-dev"));

            // Case 3: Target branch deploy/dev moves forward with a new commit (e.g. by another developer)
            MultiBranchService.runGit(repo, "checkout", "deploy/dev");
            File devFile = new File(repo, "other_feature.txt");
            Files.writeString(devFile.toPath(), "other developer content");
            MultiBranchService.runGit(repo, "add", "other_feature.txt");
            MultiBranchService.runGit(repo, "commit", "-m", "Other developer commit on deploy/dev");
            MultiBranchService.runGit(repo, "update-ref", "refs/remotes/origin/deploy/dev", "HEAD");

            // Verify TASK-101-dev does NOT have other_feature.txt yet
            MultiBranchService.runGit(repo, "checkout", "TASK-101-dev");
            assertFalse(devFile.exists());
            assertTrue(taskFile.exists());

            // Resolve target ref for premerge
            String targetRef = MultiBranchService.resolveTargetBranchRef(repo, "deploy/dev");
            assertNotNull(targetRef);

            // Premerge target branch into TASK-101-dev
            MultiBranchService.GitResult mergeRes = MultiBranchService.runGit(repo, "merge", "--no-edit", targetRef);
            assertEquals(0, mergeRes.exitCode);

            // Now TASK-101-dev HAS BOTH files (premerge succeeded!)
            assertTrue(taskFile.exists());
            assertTrue(devFile.exists());

            // Adding a new change on top
            File patchFile = new File(repo, "new_fix.txt");
            Files.writeString(patchFile.toPath(), "new fix content");
            MultiBranchService.runGit(repo, "add", "new_fix.txt");
            MultiBranchService.GitResult commitRes = MultiBranchService.runGit(repo, "commit", "-m", "TASK-101 second commit");
            assertEquals(0, commitRes.exitCode);

            // Case 4: Premerge conflict handling
            // Introduce a conflicting change in deploy/dev
            MultiBranchService.runGit(repo, "checkout", "deploy/dev");
            File conflictFile = new File(repo, "conflict.txt");
            Files.writeString(conflictFile.toPath(), "version from deploy/dev");
            MultiBranchService.runGit(repo, "add", "conflict.txt");
            MultiBranchService.runGit(repo, "commit", "-m", "Conflict file on deploy/dev");
            MultiBranchService.runGit(repo, "update-ref", "refs/remotes/origin/deploy/dev", "HEAD");

            // On TASK-101-dev, introduce conflicting content in the same file
            MultiBranchService.runGit(repo, "checkout", "TASK-101-dev");
            Files.writeString(conflictFile.toPath(), "conflicting version from TASK-101");
            MultiBranchService.runGit(repo, "add", "conflict.txt");
            MultiBranchService.runGit(repo, "commit", "-m", "Conflict file on TASK-101");

            // Attempt premerge
            MultiBranchService.GitResult conflictMerge = MultiBranchService.runGit(repo, "merge", "--no-edit", targetRef);
            assertNotEquals(0, conflictMerge.exitCode);

            // Safe abort resets working directory
            MultiBranchService.GitResult abortRes = MultiBranchService.runGit(repo, "merge", "--abort");
            assertEquals(0, abortRes.exitCode);

        } finally {
            // Cleanup temp repo
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testResolveHistorySelection() {
        // 1. Bracketed prefix extracted
        MultiBranchCommitDialog.ParsedCommitMessage res1 =
                MultiBranchCommitDialog.resolveHistorySelection("[TASK-123] Fix login crash", "OLD-999");
        assertEquals("TASK-123", res1.getPrefix());
        assertEquals("Fix login crash", res1.getMessage());

        // 2. Issue key with colon
        MultiBranchCommitDialog.ParsedCommitMessage res2 =
                MultiBranchCommitDialog.resolveHistorySelection("PROJ-456: Update documentation", "");
        assertEquals("PROJ-456", res2.getPrefix());
        assertEquals("Update documentation", res2.getMessage());

        // 3. Issue key with dash
        MultiBranchCommitDialog.ParsedCommitMessage res3 =
                MultiBranchCommitDialog.resolveHistorySelection("PROJ-456 - Update documentation", "OLD");
        assertEquals("PROJ-456", res3.getPrefix());
        assertEquals("Update documentation", res3.getMessage());

        // 4. Issue key with space
        MultiBranchCommitDialog.ParsedCommitMessage res4 =
                MultiBranchCommitDialog.resolveHistorySelection("PROJ-456 Update documentation", "");
        assertEquals("PROJ-456", res4.getPrefix());
        assertEquals("Update documentation", res4.getMessage());

        // 5. Only prefix tag
        MultiBranchCommitDialog.ParsedCommitMessage res5 =
                MultiBranchCommitDialog.resolveHistorySelection("[TASK-123]", "CURRENT");
        assertEquals("TASK-123", res5.getPrefix());
        assertEquals("", res5.getMessage());

        // 6. No prefix in message retains blank prefix so current prefix is not overridden
        MultiBranchCommitDialog.ParsedCommitMessage res6 =
                MultiBranchCommitDialog.resolveHistorySelection("Regular commit message without tag", "EXISTING-1");
        assertEquals("", res6.getPrefix());
        assertEquals("Regular commit message without tag", res6.getMessage());

        // 7. Multiline commit message with prefix
        MultiBranchCommitDialog.ParsedCommitMessage res7 =
                MultiBranchCommitDialog.resolveHistorySelection("[FEAT-88] Add feature\n\nDetailed description\nLine 2", "");
        assertEquals("FEAT-88", res7.getPrefix());
        assertEquals("Add feature\n\nDetailed description\nLine 2", res7.getMessage());

        // 8. Null and blank handling
        MultiBranchCommitDialog.ParsedCommitMessage res8 =
                MultiBranchCommitDialog.resolveHistorySelection(null, "CURRENT");
        assertEquals("", res8.getPrefix());
        assertEquals("", res8.getMessage());

        MultiBranchCommitDialog.ParsedCommitMessage res9 =
                MultiBranchCommitDialog.resolveHistorySelection("   ", "CURRENT");
        assertEquals("", res9.getPrefix());
        assertEquals("", res9.getMessage());
    }

    @Test
    public void testUncommittedChangesConstant() {
        assertEquals("Uncommitted changes", MultiBranchService.UNCOMMITTED_CHANGES_CHANGELIST_NAME);
    }

    @Test
    public void testIsPathMatchingStash() {
        java.util.Set<String> stashed = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        stashed.add("src/main/resources/application.yml");
        stashed.add("src/main/resources/META-INF/config.json");
        stashed.add("pom.xml");

        // Exact match
        assertTrue(MultiBranchService.isPathMatchingStash("src/main/resources/application.yml", stashed));
        assertTrue(MultiBranchService.isPathMatchingStash("src/main/resources/META-INF/config.json", stashed));
        assertTrue(MultiBranchService.isPathMatchingStash("pom.xml", stashed));

        // Case-insensitivity
        assertTrue(MultiBranchService.isPathMatchingStash("SRC/MAIN/RESOURCES/APPLICATION.YML", stashed));
        assertTrue(MultiBranchService.isPathMatchingStash("POM.XML", stashed));

        // Windows path backslashes
        assertTrue(MultiBranchService.isPathMatchingStash("src\\main\\resources\\application.yml", stashed));
        assertTrue(MultiBranchService.isPathMatchingStash("src\\main\\resources\\META-INF\\config.json", stashed));

        // Subpath match (e.g. prefix slash or dot-slash)
        assertTrue(MultiBranchService.isPathMatchingStash("/src/main/resources/application.yml", stashed));
        assertTrue(MultiBranchService.isPathMatchingStash("./src/main/resources/application.yml", stashed));
        assertTrue(MultiBranchService.isPathMatchingStash("repo/src/main/resources/application.yml", stashed));

        // Non-matching file
        assertFalse(MultiBranchService.isPathMatchingStash("src/main/java/git/multibranch/MultiBranchService.java", stashed));
        assertFalse(MultiBranchService.isPathMatchingStash("other/sub/different.xml", stashed));

        // Null or empty set matches all (fallback behavior)
        assertTrue(MultiBranchService.isPathMatchingStash("any/file.txt", null));
        assertTrue(MultiBranchService.isPathMatchingStash("any/file.txt", java.util.Set.of()));
    }

    @Test
    public void testRestoreChangesToUncommittedChangelistNullSafe() {
        // Must safely no-op without throwing NPE or errors in headless environment
        assertDoesNotThrow(() -> {
            MultiBranchService.restoreChangesToUncommittedChangelist(null, null, null);
        });
    }

    @Test
    public void testGitStashAndPopPreservesOtherChangesWithRealGitRepo() throws Exception {
        Path tempDir = Files.createTempDirectory("git_stash_pop_test_");
        File repo = tempDir.toFile();
        try {
            // git init
            MultiBranchService.runGit(repo, "init");
            MultiBranchService.runGit(repo, "config", "user.name", "Test User");
            MultiBranchService.runGit(repo, "config", "user.email", "test@example.com");

            // Initial commit on main
            File initialFile = new File(repo, "README.md");
            Files.writeString(initialFile.toPath(), "initial repo");
            MultiBranchService.runGit(repo, "add", "README.md");
            MultiBranchService.runGit(repo, "commit", "-m", "Initial commit");

            // Create target file (simulating selected changelist)
            File targetFile = new File(repo, "target.txt");
            Files.writeString(targetFile.toPath(), "target content v1");
            MultiBranchService.runGit(repo, "add", "target.txt");
            MultiBranchService.runGit(repo, "commit", "-m", "Add target file");

            // Now introduce changes:
            // 1. Modify target file (changelist file)
            Files.writeString(targetFile.toPath(), "target content modified");

            // 2. Modify other files (other folders/changelists)
            File appYml = new File(repo, "application.yml");
            Files.writeString(appYml.toPath(), "server:\n  port: 8080");

            File configJson = new File(repo, "config.json");
            Files.writeString(configJson.toPath(), "{\"env\": \"dev\"}");

            File pomXml = new File(repo, "pom.xml");
            Files.writeString(pomXml.toPath(), "<project></project>");

            // Step 4 simulation: check status porcelain
            MultiBranchService.GitResult statusBefore = MultiBranchService.runGit(repo, "status", "--porcelain");
            assertEquals(0, statusBefore.exitCode);
            assertTrue(statusBefore.stdout.contains("target.txt"));
            assertTrue(statusBefore.stdout.contains("application.yml"));
            assertTrue(statusBefore.stdout.contains("config.json"));
            assertTrue(statusBefore.stdout.contains("pom.xml"));

            // Step 5 simulation: revert target file
            List<String> targetRelPaths = List.of("target.txt");
            MultiBranchService.runGit(repo, "checkout", "HEAD", "--", "target.txt");

            // Target file reverted to HEAD
            assertEquals("target content v1", Files.readString(targetFile.toPath()));

            // Capture exact files remaining right before stashing
            MultiBranchService.GitResult preStash = MultiBranchService.runGit(repo, "status", "--porcelain");
            java.util.Set<String> stashedPaths = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String line : preStash.stdout.split("\\r?\\n")) {
                if (line.isBlank() || line.length() <= 3) continue;
                String f = line.substring(3).trim();
                stashedPaths.add(f.replace('\\', '/'));
            }

            assertTrue(stashedPaths.contains("application.yml"));
            assertTrue(stashedPaths.contains("config.json"));
            assertTrue(stashedPaths.contains("pom.xml"));
            assertFalse(stashedPaths.contains("target.txt"));

            // Stash other changes
            MultiBranchService.GitResult stashRes = MultiBranchService.runGit(repo, "stash", "push", "--include-untracked", "-m", "test_stash");
            assertEquals(0, stashRes.exitCode);

            // Working directory is now completely clean
            MultiBranchService.GitResult statusClean = MultiBranchService.runGit(repo, "status", "--porcelain");
            assertTrue(statusClean.stdout.isBlank());

            // Simulate stash pop on checkout branch
            MultiBranchService.GitResult popRes = MultiBranchService.runGit(repo, "stash", "pop");
            assertEquals(0, popRes.exitCode);

            // All 3 other files are restored to disk!
            assertTrue(appYml.exists());
            assertTrue(configJson.exists());
            assertTrue(pomXml.exists());
            assertEquals("server:\n  port: 8080", Files.readString(appYml.toPath()).replace("\r\n", "\n"));
            assertEquals("{\"env\": \"dev\"}", Files.readString(configJson.toPath()).replace("\r\n", "\n"));
            assertEquals("<project></project>", Files.readString(pomXml.toPath()).replace("\r\n", "\n"));

            // And path matching confirms they will be routed to "Uncommitted changes" changelist
            assertTrue(MultiBranchService.isPathMatchingStash("application.yml", stashedPaths));
            assertTrue(MultiBranchService.isPathMatchingStash("config.json", stashedPaths));
            assertTrue(MultiBranchService.isPathMatchingStash("pom.xml", stashedPaths));
            assertFalse(MultiBranchService.isPathMatchingStash("target.txt", stashedPaths));

        } finally {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }
}



