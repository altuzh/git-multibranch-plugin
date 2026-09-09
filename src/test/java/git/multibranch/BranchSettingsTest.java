package git.multibranch;

import org.junit.jupiter.api.Test;

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

        // Avoid double prefixing
        config.setCommitMessage("[PROJ-99] Fix critical null pointer");
        assertEquals("[PROJ-99] Fix critical null pointer", config.getFormattedCommitMessage());

        // Disabled prefixing
        config.setPrefixMessageWithTask(false);
        assertEquals("[PROJ-99] Fix critical null pointer", config.getFormattedCommitMessage());

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
    public void testBranchMappingAllowMerge() {
        BranchMapping bm1 = new BranchMapping("origin/deploy/dev", "deploy/dev", "-dev", true);
        assertTrue(bm1.isAllowMerge(), "Default constructor or 4-arg constructor should have allowMerge=true");

        BranchMapping bm2 = new BranchMapping("origin/deploy/dev", "deploy/dev", "-dev", true, false);
        assertFalse(bm2.isAllowMerge());

        bm2.setAllowMerge(true);
        assertTrue(bm2.isAllowMerge());

        // Copy
        BranchMapping copy = bm2.copy();
        assertEquals(bm2, copy);
        assertEquals(bm2.hashCode(), copy.hashCode());

        copy.setAllowMerge(false);
        assertNotEquals(bm2, copy);
        assertNotEquals(bm2.hashCode(), copy.hashCode());
    }

    @Test
    public void testStateAndConfigGitLabMergeMr() {
        MultiBranchSettings.State state = new MultiBranchSettings.State();
        assertFalse(state.gitLabMergeMr);
        for (BranchMapping mapping : state.branchMappings) {
            assertTrue(mapping.isAllowMerge(), "Default mapping should have allowMerge enabled");
        }

        state.gitLabMergeMr = true;
        MultiBranchSettings.State stateCopy = state.copy();
        assertEquals(state, stateCopy);
        assertEquals(state.hashCode(), stateCopy.hashCode());

        stateCopy.gitLabMergeMr = false;
        assertNotEquals(state, stateCopy);

        // toConfig
        MultiBranchSettings settings = new MultiBranchSettings();
        settings.loadState(state);
        MultiBranchConfig config = MultiBranchConfig.fromSettings(settings);
        assertTrue(config.isGitLabMergeMr());
        for (BranchMapping m : config.getBranchMappings()) {
            assertTrue(m.isAllowMerge());
        }

        // config copy
        MultiBranchConfig configCopy = config.copy();
        assertTrue(configCopy.isGitLabMergeMr());
        configCopy.setGitLabMergeMr(false);
        assertFalse(configCopy.isGitLabMergeMr());
        assertTrue(config.isGitLabMergeMr());
    }

    @Test
    public void testGitLabMergeMrResult() {
        GitLabApiService.MergeMrResult success = GitLabApiService.MergeMrResult.success("Merged successfully", "789abc");
        assertTrue(success.isSuccess());
        assertEquals("Merged successfully", success.getMessage());
        assertEquals("789abc", success.getMergeCommitSha());

        GitLabApiService.MergeMrResult error = GitLabApiService.MergeMrResult.error("Conflict detected");
        assertFalse(error.isSuccess());
        assertEquals("Conflict detected", error.getMessage());
        assertNull(error.getMergeCommitSha());
    }

    @Test
    public void testResultItemMrMergeFields() {
        MultiBranchResultItem itemSuccess = new MultiBranchResultItem(
                "TASK-101-dev", "deploy/dev", "abc1234", true, "Pushed to origin",
                "https://gitlab.example.com/group/repo/-/merge_requests/42", null,
                true, "Merged successfully (sha123)", false
        );
        assertTrue(itemSuccess.isSuccess());
        assertTrue(itemSuccess.isMrMerged());
        assertFalse(itemSuccess.isMrMergeError());
        assertEquals("Merged successfully (sha123)", itemSuccess.getMrMergeStatus());

        MultiBranchResultItem itemFailed = new MultiBranchResultItem(
                "TASK-101-test", "deploy/test", "def5678", true, "Pushed to origin",
                "https://gitlab.example.com/group/repo/-/merge_requests/43", null,
                false, "Failed: HTTP 405 Branch cannot be merged", true
        );
        assertTrue(itemFailed.isSuccess()); // commit/push succeeded
        assertFalse(itemFailed.isMrMerged());
        assertTrue(itemFailed.isMrMergeError());
        assertEquals("Failed: HTTP 405 Branch cannot be merged", itemFailed.getMrMergeStatus());
    }

    @Test
    public void testBrowserOpenLogicAfterMerge() {
        // Case 1: Merge MR option is enabled, branch allows merge, merge succeeded without error -> DO NOT OPEN IN BROWSER
        boolean openInBrowser1 = decideOpenInBrowser(true, true, true, false, true, "https://gitlab.com/mr/1");
        assertFalse(openInBrowser1, "Successful merge without error should suppress opening in browser");

        // Case 2: Merge MR option is enabled, branch allows merge, merge failed with error -> OPEN IN BROWSER
        boolean openInBrowser2 = decideOpenInBrowser(true, true, false, true, true, "https://gitlab.com/mr/2");
        assertTrue(openInBrowser2, "Merge failure should open MR in browser so user can review/resolve conflicts");

        // Case 3: Merge MR option is enabled, but branch does NOT allow merge -> OPEN IN BROWSER (Merge MR not applied to this branch)
        boolean openInBrowser3 = decideOpenInBrowser(true, false, false, false, true, "https://gitlab.com/mr/3");
        assertTrue(openInBrowser3, "Branch with Allow merge unchecked should follow standard browser open behavior");

        // Case 4: Merge MR option is disabled -> OPEN IN BROWSER
        boolean openInBrowser4 = decideOpenInBrowser(false, true, false, false, true, "https://gitlab.com/mr/4");
        assertTrue(openInBrowser4, "When Merge MR is disabled, standard browser open behavior applies");

        // Case 5: Open MR in browser setting is disabled -> DO NOT OPEN
        boolean openInBrowser5 = decideOpenInBrowser(true, true, false, true, false, "https://gitlab.com/mr/5");
        assertFalse(openInBrowser5, "When open in browser setting is off, do not open");
    }

    private boolean decideOpenInBrowser(boolean isGitLabMergeMr, boolean isAllowMerge, boolean mrMerged, boolean mrMergeError, boolean isOpenMrLinksInBrowser, String mrUrl) {
        if (!isOpenMrLinksInBrowser || mrUrl == null || mrUrl.isBlank()) {
            return false;
        }
        if (isGitLabMergeMr) {
            if (isAllowMerge) {
                if (mrMergeError) {
                    return true;
                } else if (mrMerged) {
                    return false;
                } else {
                    return true;
                }
            } else {
                return true;
            }
        }
        return true;
    }
}
