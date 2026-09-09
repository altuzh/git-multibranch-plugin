package git.multibranch;

import java.util.Objects;

public class BranchMapping {
    private String sourceOriginBranch;
    private String targetOriginBranchName;
    private String branchSuffix;
    private boolean enabled;

    /**
     * Default constructor required for IntelliJ PersistentStateComponent XML serialization.
     */
    public BranchMapping() {
        this("", "", "", true);
    }

    public BranchMapping(String sourceOriginBranch, String targetOriginBranchName, String branchSuffix, boolean enabled) {
        this.sourceOriginBranch = sourceOriginBranch;
        this.targetOriginBranchName = targetOriginBranchName;
        this.branchSuffix = branchSuffix;
        this.enabled = enabled;
    }

    public BranchMapping(BranchMapping other) {
        this.sourceOriginBranch = other.sourceOriginBranch;
        this.targetOriginBranchName = other.targetOriginBranchName;
        this.branchSuffix = other.branchSuffix;
        this.enabled = other.enabled;
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
                Objects.equals(sourceOriginBranch, that.sourceOriginBranch) &&
                Objects.equals(targetOriginBranchName, that.targetOriginBranchName) &&
                Objects.equals(branchSuffix, that.branchSuffix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceOriginBranch, targetOriginBranchName, branchSuffix, enabled);
    }
}
