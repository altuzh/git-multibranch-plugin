package git.multibranch;

public class MultiBranchResultItem {
    private final String branchName;
    private final String targetBranch;
    private final String commitHash;
    private final boolean pushed;
    private final String pushDetails;
    private final String mrUrl;
    private final String errorMessage;

    public MultiBranchResultItem(String branchName, String targetBranch, String commitHash, boolean pushed, String mrUrl, String errorMessage) {
        this(branchName, targetBranch, commitHash, pushed, null, mrUrl, errorMessage);
    }

    public MultiBranchResultItem(String branchName, String targetBranch, String commitHash, boolean pushed, String pushDetails, String mrUrl, String errorMessage) {
        this.branchName = branchName;
        this.targetBranch = targetBranch;
        this.commitHash = commitHash;
        this.pushed = pushed;
        this.pushDetails = pushDetails;
        this.mrUrl = mrUrl;
        this.errorMessage = errorMessage;
    }

    public String getBranchName() { return branchName; }
    public String getTargetBranch() { return targetBranch; }
    public String getCommitHash() { return commitHash; }
    public boolean isPushed() { return pushed; }
    public String getPushDetails() {
        if (pushDetails != null && !pushDetails.isBlank()) {
            return pushDetails;
        }
        return pushed ? "Pushed to origin" : "Local only";
    }
    public String getMrUrl() { return mrUrl; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isSuccess() { return errorMessage == null || errorMessage.isEmpty(); }
}
