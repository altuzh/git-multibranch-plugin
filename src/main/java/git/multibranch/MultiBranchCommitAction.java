package git.multibranch;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultiBranchCommitAction extends AnAction {
    private static final Pattern PREFIX_PATTERN = Pattern.compile("([A-Z]+-[0-9]+)");

    public static class BranchDetectionResult {
        private final String currentBranch;
        private final String detectedPrefix;
        private final boolean onDesignatedBranch;
        private final BranchMapping matchedMapping;

        public BranchDetectionResult(String currentBranch, String detectedPrefix, boolean onDesignatedBranch, @Nullable BranchMapping matchedMapping) {
            this.currentBranch = currentBranch != null ? currentBranch : "";
            this.detectedPrefix = detectedPrefix != null ? detectedPrefix : "";
            this.onDesignatedBranch = onDesignatedBranch;
            this.matchedMapping = matchedMapping;
        }

        public String getCurrentBranch() { return currentBranch; }
        public String getDetectedPrefix() { return detectedPrefix; }
        public boolean isOnDesignatedBranch() { return onDesignatedBranch; }
        public @Nullable BranchMapping getMatchedMapping() { return matchedMapping; }
    }

    public static BranchDetectionResult detectBranchContext(@Nullable String currentBranch, @Nullable List<BranchMapping> mappings) {
        if (currentBranch == null || currentBranch.isBlank()) {
            return new BranchDetectionResult("", "", false, null);
        }

        String branch = currentBranch.trim();
        if (branch.startsWith("refs/heads/")) {
            branch = branch.substring("refs/heads/".length());
        }

        // 1. Check if current branch ends with any configured branch mapping suffix (check longer suffixes first)
        if (mappings != null && !mappings.isEmpty()) {
            List<BranchMapping> sortedMappings = new java.util.ArrayList<>(mappings);
            sortedMappings.sort((a, b) -> {
                int lenA = a.getBranchSuffix() != null ? a.getBranchSuffix().length() : 0;
                int lenB = b.getBranchSuffix() != null ? b.getBranchSuffix().length() : 0;
                return Integer.compare(lenB, lenA);
            });

            for (BranchMapping mapping : sortedMappings) {
                String suffix = mapping.getBranchSuffix();
                if (suffix != null && !suffix.isBlank() && branch.endsWith(suffix)) {
                    String prefixCandidate = branch.substring(0, branch.length() - suffix.length());
                    if (!prefixCandidate.isBlank()) {
                        return new BranchDetectionResult(branch, prefixCandidate, true, mapping);
                    }
                }
            }
        }

        // 2. Fallback to standard issue key regex (e.g. ABC-123)
        String prefix = "";
        Matcher m = PREFIX_PATTERN.matcher(branch);
        if (m.find()) {
            prefix = m.group(1);
        }

        return new BranchDetectionResult(branch, prefix, false, null);
    }

    public static void trigger(Project project) {
        if (project == null) return;
        Collection<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
        if (repos.isEmpty()) return;
        GitRepository repository = repos.iterator().next();
        startMultiBranchWorkflow(project, repository);
    }

    private static void startMultiBranchWorkflow(@NotNull Project project, @NotNull GitRepository repository) {
        MultiBranchService.syncDocumentsToDisk(project);

        MultiBranchSettings settings = MultiBranchSettings.getInstance(project);
        MultiBranchConfig config = MultiBranchConfig.fromSettings(settings, project);

        String currentBranch = repository.getCurrentBranchName();
        BranchDetectionResult detection = detectBranchContext(currentBranch, config.getBranchMappings());

        MultiBranchCommitDialog dialog = new MultiBranchCommitDialog(project, config, detection.getDetectedPrefix(), detection);
        if (dialog.showAndGet()) {
            MultiBranchService.syncDocumentsToDisk(project);

            MultiBranchConfirmDialog confirmDialog = new MultiBranchConfirmDialog(project, repository, dialog.getConfig());
            if (confirmDialog.showAndGet()) {
                MultiBranchService.execute(project, repository, dialog.getConfig());
            }
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        Collection<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
        boolean hasRepo = !repos.isEmpty();
        e.getPresentation().setEnabledAndVisible(hasRepo);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        GitRepository repository = GitBranchUtil.guessWidgetRepository(project, e.getDataContext());
        if (repository == null) {
            Collection<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
            if (!repos.isEmpty()) {
                repository = repos.iterator().next();
            }
        }
        if (repository == null) return;

        startMultiBranchWorkflow(project, repository);
    }
}
