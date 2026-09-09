package git.multibranch;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@State(
        name = "git.multibranch.MultiBranchSettings",
        storages = @Storage("multiBranchSettings.xml")
)
public class MultiBranchSettings implements PersistentStateComponent<MultiBranchSettings.State> {

    public static class State {
        public List<BranchMapping> branchMappings = new ArrayList<>();
        public String checkoutBranch = "deploy/test";
        public boolean fetchOriginFirst = true;
        public boolean pushAfterCommit = true;
        public boolean generateMrLinks = true;
        public boolean openMrLinksInBrowser = true;
        public boolean stashOtherChanges = true;
        public boolean checkoutTestAfter = true;
        public boolean prefixMessageWithTask = true;
        public String defaultChangelistName = "Changes";
        public boolean gitLabCreateMr = true;
        public boolean gitLabAssignToMe = true;
        public boolean gitLabDeleteSourceBranch = true;
        public boolean gitLabSquashCommits = true;
        public String gitLabHost = "";
        public String gitLabApiToken = "";

        public State() {
            initDefaultMappings();
        }

        public void initDefaultMappings() {
            branchMappings.clear();
            branchMappings.add(new BranchMapping("origin/deploy/dev", "deploy/dev", "-dev", true));
            branchMappings.add(new BranchMapping("origin/deploy/test", "deploy/test", "-test", true));
            branchMappings.add(new BranchMapping("origin/merge_to_uat", "merge_to_uat", "-uat", true));
            branchMappings.add(new BranchMapping("origin/merge_to_prod", "merge_to_prod", "-prod", true));
        }

        public State copy() {
            State s = new State();
            s.branchMappings.clear();
            if (this.branchMappings != null) {
                for (BranchMapping bm : this.branchMappings) {
                    s.branchMappings.add(bm.copy());
                }
            }
            s.checkoutBranch = this.checkoutBranch;
            s.fetchOriginFirst = this.fetchOriginFirst;
            s.pushAfterCommit = this.pushAfterCommit;
            s.generateMrLinks = this.generateMrLinks;
            s.openMrLinksInBrowser = this.openMrLinksInBrowser;
            s.stashOtherChanges = this.stashOtherChanges;
            s.checkoutTestAfter = this.checkoutTestAfter;
            s.prefixMessageWithTask = this.prefixMessageWithTask;
            s.defaultChangelistName = this.defaultChangelistName;
            s.gitLabCreateMr = this.gitLabCreateMr;
            s.gitLabAssignToMe = this.gitLabAssignToMe;
            s.gitLabDeleteSourceBranch = this.gitLabDeleteSourceBranch;
            s.gitLabSquashCommits = this.gitLabSquashCommits;
            s.gitLabHost = this.gitLabHost;
            s.gitLabApiToken = this.gitLabApiToken;
            return s;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            State state = (State) o;
            return fetchOriginFirst == state.fetchOriginFirst &&
                    pushAfterCommit == state.pushAfterCommit &&
                    generateMrLinks == state.generateMrLinks &&
                    openMrLinksInBrowser == state.openMrLinksInBrowser &&
                    stashOtherChanges == state.stashOtherChanges &&
                    checkoutTestAfter == state.checkoutTestAfter &&
                    prefixMessageWithTask == state.prefixMessageWithTask &&
                    gitLabCreateMr == state.gitLabCreateMr &&
                    gitLabAssignToMe == state.gitLabAssignToMe &&
                    gitLabDeleteSourceBranch == state.gitLabDeleteSourceBranch &&
                    gitLabSquashCommits == state.gitLabSquashCommits &&
                    Objects.equals(branchMappings, state.branchMappings) &&
                    Objects.equals(checkoutBranch, state.checkoutBranch) &&
                    Objects.equals(defaultChangelistName, state.defaultChangelistName) &&
                    Objects.equals(gitLabHost, state.gitLabHost) &&
                    Objects.equals(gitLabApiToken, state.gitLabApiToken);
        }

        @Override
        public int hashCode() {
            return Objects.hash(branchMappings, checkoutBranch, fetchOriginFirst, pushAfterCommit,
                    generateMrLinks, openMrLinksInBrowser, stashOtherChanges, checkoutTestAfter,
                    prefixMessageWithTask, defaultChangelistName, gitLabCreateMr, gitLabAssignToMe,
                    gitLabDeleteSourceBranch, gitLabSquashCommits, gitLabHost, gitLabApiToken);
        }
    }

    private State myState = new State();

    public static @Nullable MultiBranchSettings getInstance(@Nullable Project project) {
        if (project == null || project.isDefault()) {
            return null;
        }
        return project.getService(MultiBranchSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.myState = state;
        if (this.myState.branchMappings == null || this.myState.branchMappings.isEmpty()) {
            this.myState.initDefaultMappings();
        }
    }

    public MultiBranchConfig toConfig() {
        MultiBranchConfig config = new MultiBranchConfig();
        config.getBranchMappings().clear();
        if (myState.branchMappings != null) {
            for (BranchMapping mapping : myState.branchMappings) {
                config.getBranchMappings().add(mapping.copy());
            }
        }
        config.setCheckoutBranch(myState.checkoutBranch != null && !myState.checkoutBranch.isBlank()
                ? myState.checkoutBranch.trim()
                : "deploy/test");
        config.setFetchOriginFirst(myState.fetchOriginFirst);
        config.setPushAfterCommit(myState.pushAfterCommit);
        config.setGenerateMrLinks(myState.generateMrLinks);
        config.setOpenMrLinksInBrowser(myState.openMrLinksInBrowser);
        config.setStashOtherChanges(myState.stashOtherChanges);
        config.setCheckoutTestAfter(myState.checkoutTestAfter);
        config.setPrefixMessageWithTask(myState.prefixMessageWithTask);
        if (myState.defaultChangelistName != null && !myState.defaultChangelistName.isBlank()) {
            config.setChangelistName(myState.defaultChangelistName.trim());
        }
        config.setGitLabCreateMr(myState.gitLabCreateMr);
        config.setGitLabAssignToMe(myState.gitLabAssignToMe);
        config.setGitLabDeleteSourceBranch(myState.gitLabDeleteSourceBranch);
        config.setGitLabSquashCommits(myState.gitLabSquashCommits);
        config.setGitLabHost(myState.gitLabHost);
        String token = GitLabTokenManager.getToken();
        if (token != null && !token.isBlank()) {
            config.setGitLabApiToken(token);
        } else {
            config.setGitLabApiToken(myState.gitLabApiToken);
        }
        return config;
    }
}
