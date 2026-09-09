package git.multibranch;

import java.util.Objects;

public class BranchMapping {
    private String sourceOriginBranch;
    private String targetOriginBranchName;
    private String branchSuffix;
    private boolean enabled;
    private boolean allowMerge;

    /**
     * Default constructor required for IntelliJ PersistentStateComponent XML serialization.
     */
    public BranchMapping() {
        this("", "", "", true, true);
    }

    public BranchMapping(String sourceOriginBranch, String targetOriginBranchName, String branchSuffix, boolean enabled) {
        this(sourceOriginBranch, targetOriginBranchName, branchSuffix, enabled, true);
    }

    public BranchMapping(String sourceOriginBranch, String targetOriginBranchName, String branchSuffix, boolean enabled, boolean allowMerge) {
        this.sourceOriginBranch = sourceOriginBranch;
        this.targetOriginBranchName = targetOriginBranchName;
        this.branchSuffix = branchSuffix;
        this.enabled = enabled;
        this.allowMerge = allowMerge;
    }

    public BranchMapping(BranchMapping other) {
        this.sourceOriginBranch = other.sourceOriginBranch;
        this.targetOriginBranchName = other.targetOriginBranchName;
        this.branchSuffix = other.branchSuffix;
        this.enabled = other.enabled;
        this.allowMerge = other.allowMerge;
    }

    public String getSourceOriginBranch() {
        return sourceOriginBranch;
    }

    public void setSourceOriginBranch(String sourceOriginBranch) {
        this.sourceOriginBranch = sourceOriginBranch;
    }

    public String getTargetOriginBranchName() {
        return targetOriginBranchName;
    }

    public void setTargetOriginBranchName(String targetOriginBranchName) {
        this.targetOriginBranchName = targetOriginBranchName;
    }

    public String getBranchSuffix() {
        return branchSuffix;
    }

    public void setBranchSuffix(String branchSuffix) {
        this.branchSuffix = branchSuffix;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowMerge() {
        return allowMerge;
    }

    public void setAllowMerge(boolean allowMerge) {
        this.allowMerge = allowMerge;
    }

    public String getLocalBranchName(String prefix) {
        String p = prefix != null ? prefix.trim() : "";
        String s = branchSuffix != null ? branchSuffix.trim() : "";
        return p + s;
    }

    public BranchMapping copy() {
        return new BranchMapping(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BranchMapping that = (BranchMapping) o;
        return enabled == that.enabled &&
                allowMerge == that.allowMerge &&
                Objects.equals(sourceOriginBranch, that.sourceOriginBranch) &&
                Objects.equals(targetOriginBranchName, that.targetOriginBranchName) &&
                Objects.equals(branchSuffix, that.branchSuffix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceOriginBranch, targetOriginBranchName, branchSuffix, enabled, allowMerge);
    }
}
