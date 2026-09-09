package git.multibranch;

import java.util.ArrayList;
import java.util.List;

public class MultiBranchConfig {
    private String taskPrefix = "";
    private String commitMessage = "";
    private boolean prefixMessageWithTask = true;
    private String changelistName = "Changes";
    private final List<BranchMapping> branchMappings = new ArrayList<>();
    private boolean fetchOriginFirst = true;
    private boolean pushAfterCommit = true;
    private boolean generateMrLinks = true;
    private boolean openMrLinksInBrowser = true;
    private boolean stashOtherChanges = true;
    private boolean checkoutTestAfter = true;
    private String checkoutBranch = "deploy/test";
    private boolean gitLabCreateMr = true;
    private boolean gitLabAssignToMe = true;
    private boolean gitLabDeleteSourceBranch = true;
    private boolean gitLabSquashCommits = true;
    private String gitLabHost = "";
    private String gitLabApiToken = "";

    public MultiBranchConfig() {
        branchMappings.add(new BranchMapping("origin/deploy/dev", "deploy/dev", "-dev", true));
        branchMappings.add(new BranchMapping("origin/deploy/test", "deploy/test", "-test", true));
        branchMappings.add(new BranchMapping("origin/merge_to_uat", "merge_to_uat", "-uat", true));
        branchMappings.add(new BranchMapping("origin/merge_to_prod", "merge_to_prod", "-prod", true));
    }

    public static MultiBranchConfig fromSettings(MultiBranchSettings settings) {
        if (settings == null || settings.getState() == null) {
            return new MultiBranchConfig();
        }
        return settings.toConfig();
    }

    public MultiBranchConfig copy() {
        MultiBranchConfig copy = new MultiBranchConfig();
        copy.setTaskPrefix(this.taskPrefix);
        copy.setCommitMessage(this.commitMessage);
        copy.setPrefixMessageWithTask(this.prefixMessageWithTask);
        copy.setChangelistName(this.changelistName);
        copy.getBranchMappings().clear();
        for (BranchMapping mapping : this.branchMappings) {
            copy.getBranchMappings().add(mapping.copy());
        }
        copy.setFetchOriginFirst(this.fetchOriginFirst);
        copy.setPushAfterCommit(this.pushAfterCommit);
        copy.setGenerateMrLinks(this.generateMrLinks);
        copy.setOpenMrLinksInBrowser(this.openMrLinksInBrowser);
        copy.setStashOtherChanges(this.stashOtherChanges);
        copy.setCheckoutTestAfter(this.checkoutTestAfter);
        copy.setCheckoutBranch(this.checkoutBranch);
        copy.setGitLabCreateMr(this.gitLabCreateMr);
        copy.setGitLabAssignToMe(this.gitLabAssignToMe);
        copy.setGitLabDeleteSourceBranch(this.gitLabDeleteSourceBranch);
        copy.setGitLabSquashCommits(this.gitLabSquashCommits);
        copy.setGitLabHost(this.gitLabHost);
        copy.setGitLabApiToken(this.gitLabApiToken);
        return copy;
    }

    public String getFormattedCommitMessage() {
        String msg = commitMessage != null ? commitMessage.trim() : "";
        if (prefixMessageWithTask && taskPrefix != null && !taskPrefix.isBlank()) {
            String tag = "[" + taskPrefix.trim() + "]";
            if (!msg.startsWith(tag)) {
                return tag + " " + msg;
            }
        }
        return msg;
    }

    public String getTaskPrefix() {
        return taskPrefix;
    }

    public void setTaskPrefix(String taskPrefix) {
        this.taskPrefix = taskPrefix;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    public boolean isPrefixMessageWithTask() {
        return prefixMessageWithTask;
    }

    public void setPrefixMessageWithTask(boolean prefixMessageWithTask) {
        this.prefixMessageWithTask = prefixMessageWithTask;
    }

    public String getChangelistName() {
        return changelistName;
    }

    public void setChangelistName(String changelistName) {
        this.changelistName = changelistName;
    }

    public List<BranchMapping> getBranchMappings() {
        return branchMappings;
    }

    public boolean isFetchOriginFirst() {
        return fetchOriginFirst;
    }

    public void setFetchOriginFirst(boolean fetchOriginFirst) {
        this.fetchOriginFirst = fetchOriginFirst;
    }

    public boolean isPushAfterCommit() {
        return pushAfterCommit;
    }

    public void setPushAfterCommit(boolean pushAfterCommit) {
        this.pushAfterCommit = pushAfterCommit;
    }

    public boolean isGenerateMrLinks() {
        return generateMrLinks;
    }

    public void setGenerateMrLinks(boolean generateMrLinks) {
        this.generateMrLinks = generateMrLinks;
    }

    public boolean isOpenMrLinksInBrowser() {
        return openMrLinksInBrowser;
    }

    public void setOpenMrLinksInBrowser(boolean openMrLinksInBrowser) {
        this.openMrLinksInBrowser = openMrLinksInBrowser;
    }

    public boolean isStashOtherChanges() {
        return stashOtherChanges;
    }

    public void setStashOtherChanges(boolean stashOtherChanges) {
        this.stashOtherChanges = stashOtherChanges;
    }

    public boolean isCheckoutTestAfter() {
        return checkoutTestAfter;
    }

    public void setCheckoutTestAfter(boolean checkoutTestAfter) {
        this.checkoutTestAfter = checkoutTestAfter;
    }

    public String getCheckoutBranch() {
        return checkoutBranch;
    }

    public void setCheckoutBranch(String checkoutBranch) {
        this.checkoutBranch = checkoutBranch;
    }

    public boolean isGitLabCreateMr() {
        return gitLabCreateMr;
    }

    public void setGitLabCreateMr(boolean gitLabCreateMr) {
        this.gitLabCreateMr = gitLabCreateMr;
    }

    public boolean isGitLabAssignToMe() {
        return gitLabAssignToMe;
    }

    public void setGitLabAssignToMe(boolean gitLabAssignToMe) {
        this.gitLabAssignToMe = gitLabAssignToMe;
    }

    public boolean isGitLabDeleteSourceBranch() {
        return gitLabDeleteSourceBranch;
    }

    public void setGitLabDeleteSourceBranch(boolean gitLabDeleteSourceBranch) {
        this.gitLabDeleteSourceBranch = gitLabDeleteSourceBranch;
    }

    public boolean isGitLabSquashCommits() {
        return gitLabSquashCommits;
    }

    public void setGitLabSquashCommits(boolean gitLabSquashCommits) {
        this.gitLabSquashCommits = gitLabSquashCommits;
    }

    public String getGitLabHost() {
        return gitLabHost;
    }

    public void setGitLabHost(String gitLabHost) {
        this.gitLabHost = gitLabHost != null ? gitLabHost.trim() : "";
    }

    public String getGitLabApiToken() {
        return gitLabApiToken;
    }

    public void setGitLabApiToken(String gitLabApiToken) {
        this.gitLabApiToken = gitLabApiToken != null ? gitLabApiToken.trim() : "";
    }
}
