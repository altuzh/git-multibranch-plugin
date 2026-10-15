package git.multibranch;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.VcsConfiguration;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class MultiBranchService {

    public static final String UNCOMMITTED_CHANGES_CHANGELIST_NAME = "Uncommitted changes";

    public static void syncDocumentsToDisk(@Nullable Project project) {
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app != null) {
            Runnable saveTask = () -> {
                FileDocumentManager.getInstance().saveAllDocuments();
                if (project != null && !project.isDisposed()) {
                    project.save();
                }
            };
            if (app.isDispatchThread()) {
                saveTask.run();
            } else {
                app.invokeAndWait(saveTask);
            }
        }
    }

    public static void execute(Project project, GitRepository repository, MultiBranchConfig config) {
        syncDocumentsToDisk(project);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Processing Multi-Branch Commit...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                syncDocumentsToDisk(project);
                File repoDir = new File(repository.getRoot().getPath());
                List<MultiBranchResultItem> results = new ArrayList<>();
                File tempPatch = null;
                File worktreeDir = null;
                boolean didStash = false;
                Set<String> stashedRelativePaths = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                List<String> targetRelativePaths = new ArrayList<>();

                GitResult curBranchRes = runGit(repoDir, "rev-parse", "--abbrev-ref", "HEAD");
                String initialBranch = (curBranchRes.exitCode == 0 && !curBranchRes.stdout.isBlank()) ? curBranchRes.stdout.trim() : null;
                if ("HEAD".equalsIgnoreCase(initialBranch)) {
                    initialBranch = null; // detached HEAD
                }

                try {
                    MultiBranchLog.startExecution("Multi-Branch Commit & Push");
                    MultiBranchLog.info("Project: " + (project != null ? project.getName() : "null") + " | Repository: " + repoDir.getAbsolutePath());
                    MultiBranchLog.info("Initial branch: " + (initialBranch != null ? initialBranch : "(detached)"));
                    MultiBranchLog.info("Changelist: '" + config.getChangelistName() + "' | Task Prefix: '" + config.getTaskPrefix() + "'");
                    MultiBranchLog.info("Commit Message: " + config.getFormattedCommitMessage());
                    MultiBranchLog.info("Options: fetchOrigin=" + config.isFetchOriginFirst() +
                            ", push=" + config.isPushAfterCommit() +
                            ", premergeTarget=" + config.isPremergeTargetBranch() +
                            ", mrLinks=" + config.isGenerateMrLinks() +
                            ", openBrowser=" + config.isOpenMrLinksInBrowser() +
                            ", stashOther=" + config.isStashOtherChanges() +
                            ", checkoutTestAfter=" + config.isCheckoutTestAfter() +
                            ", checkoutBranch=" + config.getCheckoutBranch());
                    MultiBranchLog.info("GitLab Settings: createMr=" + config.isGitLabCreateMr() +
                            ", assignToMe=" + config.isGitLabAssignToMe() +
                            ", deleteSourceBranch=" + config.isGitLabDeleteSourceBranch() +
                            ", squashCommits=" + config.isGitLabSquashCommits() +
                            ", host=" + (config.getGitLabHost() != null && !config.getGitLabHost().isBlank() ? config.getGitLabHost() : "(auto-detect)") +
                            ", tokenConfigured=" + (config.getGitLabApiToken() != null && !config.getGitLabApiToken().isBlank()));

                    for (BranchMapping bm : config.getBranchMappings()) {
                        MultiBranchLog.info("Branch Mapping: enabled=" + bm.isEnabled() +
                                ", source=" + bm.getSourceOriginBranch() +
                                ", target=" + bm.getTargetOriginBranchName() +
                                ", suffix=" + bm.getBranchSuffix() +
                                " -> localBranch=" + bm.getLocalBranchName(config.getTaskPrefix()));
                    }

                    indicator.setText("Preparing changes from changelist '" + config.getChangelistName() + "'...");

                    // 1. Fetch origin
                    if (config.isFetchOriginFirst()) {
                        indicator.setText("Fetching origin (with prune)...");
                        runGit(repoDir, "fetch", "--prune", "origin");
                    }

                    // 2. Get files from the selected changelist
                    ChangeListManager clm = ChangeListManager.getInstance(project);
                    LocalChangeList targetList = null;
                    for (LocalChangeList cl : clm.getChangeLists()) {
                        if (cl.getName().equalsIgnoreCase(config.getChangelistName())) {
                            targetList = cl;
                            break;
                        }
                    }
                    if (targetList == null) {
                        MultiBranchLog.error("Changelist '" + config.getChangelistName() + "' not found in project.");
                        notifyError(project, "Changelist '" + config.getChangelistName() + "' not found.");
                        return;
                    }

                    for (Change change : targetList.getChanges()) {
                        ContentRevision rev = change.getAfterRevision() != null ? change.getAfterRevision() : change.getBeforeRevision();
                        if (rev != null) {
                            String fullPath = rev.getFile().getPath();
                            String rel = getRelativePath(repoDir, fullPath);
                            targetRelativePaths.add(rel.replace('\\', '/'));
                        }
                    }

                    MultiBranchLog.info("Changelist '" + config.getChangelistName() + "' contains " + targetRelativePaths.size() + " modified file(s): " + targetRelativePaths);

                    if (targetRelativePaths.isEmpty()) {
                        MultiBranchLog.error("No modified files found in changelist '" + config.getChangelistName() + "'.");
                        notifyError(project, "No modified files in changelist '" + config.getChangelistName() + "'.");
                        return;
                    }

                    // Stage intent for any new files in target list so diff can capture untracked files
                    for (String rel : targetRelativePaths) {
                        File f = new File(repoDir, rel);
                        if (f.exists()) {
                            runGit(repoDir, "add", "-N", "--", rel);
                        }
                    }

                    // 3. Create diff of only target files
                    tempPatch = File.createTempFile("multibranch_patch_", ".patch");
                    List<String> diffCmd = new ArrayList<>(List.of("diff", "HEAD", "--binary", "--"));
                    diffCmd.addAll(targetRelativePaths);
                    GitResult diffRes = runGit(repoDir, diffCmd.toArray(new String[0]));

                    if (diffRes.stdout.trim().isEmpty()) {
                        MultiBranchLog.error("No diff produced for the files in changelist '" + config.getChangelistName() + "'.");
                        notifyError(project, "No diff produced for the files in changelist '" + config.getChangelistName() + "'.");
                        return;
                    }
                    Files.writeString(tempPatch.toPath(), diffRes.stdout, StandardCharsets.UTF_8);
                    MultiBranchLog.info("Created binary patch (" + tempPatch.length() + " bytes) at " + tempPatch.getAbsolutePath());

                    // Unstage intent-to-add so index remains clean and doesn't conflict with stash or branch switching
                    for (String rel : targetRelativePaths) {
                        runGit(repoDir, "reset", "HEAD", "--", rel);
                    }

                    // 4. Check if uncommitted changes exist in other folders / working tree
                    boolean hasOtherChanges = false;
                    GitResult statusRes = runGit(repoDir, "status", "--porcelain", "-uall");
                    List<String> otherFilesToStash = new ArrayList<>();
                    if (statusRes.exitCode == 0) {
                        Set<String> targetSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                        targetSet.addAll(targetRelativePaths);
                        for (String line : statusRes.stdout.split("\\r?\\n")) {
                            if (line.isBlank() || line.length() <= 3) continue;
                            String statusFile = line.substring(3).trim();
                            if (statusFile.startsWith("\"") && statusFile.endsWith("\"")) {
                                statusFile = statusFile.substring(1, statusFile.length() - 1);
                            }
                            if (statusFile.contains(" -> ")) {
                                statusFile = statusFile.substring(statusFile.indexOf(" -> ") + 4).trim();
                            }
                            statusFile = statusFile.replace('\\', '/');
                            if (!targetSet.contains(statusFile) && !isPathMatchingStash(statusFile, targetSet)) {
                                hasOtherChanges = true;
                                otherFilesToStash.add(statusFile);
                                stashedRelativePaths.add(statusFile);
                            }
                        }
                    }

                    MultiBranchLog.info("Working tree status check: uncommitted changes in other folders = " + hasOtherChanges + " (" + stashedRelativePaths.size() + " files: " + stashedRelativePaths + ")");

                    // 5. If uncommitted changes exist in other folders and stash is enabled, stash ONLY the other files!
                    // We deliberately do NOT revert or stash targetRelativePaths here:
                    // If an error happens during worktree creation or branch execution, the target files remain safely intact in working tree.
                    if (hasOtherChanges && config.isStashOtherChanges()) {
                        indicator.setText("Stashing uncommitted changes from other folders...");
                        List<String> stashCmd = new ArrayList<>(List.of("stash", "push", "--include-untracked", "-m", "multibranch_temp_stash_" + System.currentTimeMillis(), "--"));
                        stashCmd.addAll(otherFilesToStash);
                        GitResult stashRes = runGit(repoDir, stashCmd.toArray(new String[0]));
                        didStash = (stashRes.exitCode == 0 && !stashRes.stdout.contains("No local changes to save"));
                        MultiBranchLog.info("Stashed other changes: didStash=" + didStash + " (" + stashedRelativePaths.size() + " files: " + stashedRelativePaths + ")");
                    }

                    // 6. Create detached worktree
                    indicator.setText("Setting up isolated git worktree...");
                    worktreeDir = new File(repoDir, ".git/temp_mb_worktree_" + System.currentTimeMillis());
                    MultiBranchLog.info("Creating isolated worktree at: " + worktreeDir.getAbsolutePath());
                    GitResult wtRes = runGit(repoDir, "worktree", "add", "--detach", worktreeDir.getAbsolutePath());
                    if (wtRes.exitCode != 0) {
                        MultiBranchLog.error("Failed to create git worktree: " + wtRes.stderr);
                        notifyError(project, "Failed to create git worktree: " + wtRes.stderr);
                        return;
                    }

                    // 7. Initialize GitLab API user if token & auto-assign are enabled
                    String detectedHost = GitLabTokenManager.detectHost(repoDir, config.getGitLabHost());
                    String gitLabToken = config.getGitLabApiToken();
                    if (gitLabToken == null || gitLabToken.isBlank()) {
                        gitLabToken = GitLabTokenManager.getToken(detectedHost);
                    }

                    Integer gitLabUserId = null;
                    if (config.isGitLabCreateMr() && config.isGitLabAssignToMe() && gitLabToken != null && !gitLabToken.isBlank()) {
                        indicator.setText("Connecting to GitLab API to get current user...");
                        GitLabApiService.GitLabProjectInfo glInfo = GitLabApiService.parseProjectInfo(
                                GitLabApiService.getOriginRemoteUrl(repoDir), config.getGitLabHost());
                        if (glInfo != null) {
                            GitLabApiService.GitLabUser user = GitLabApiService.getCurrentUser(glInfo.getApiUrl(), gitLabToken);
                            if (user != null) {
                                gitLabUserId = user.getId();
                                MultiBranchLog.info("GitLab current user resolved: id=" + gitLabUserId + ", username=" + user.getUsername());
                            } else {
                                MultiBranchLog.warn("Failed to resolve GitLab current user from " + glInfo.getApiUrl());
                            }
                        }
                    }

                    // 8. Process each branch mapping
                    String commitMessage = config.getFormattedCommitMessage();
                    boolean[] forcePushAll = new boolean[]{false};
                    boolean[] skipAllRemainingBehind = new boolean[]{false};

                    for (BranchMapping mapping : config.getBranchMappings()) {
                        if (!mapping.isEnabled()) continue;

                        String branchName = mapping.getLocalBranchName(config.getTaskPrefix());
                        String targetBranch = mapping.getTargetOriginBranchName();
                        indicator.setText("Processing branch: " + branchName);
                        MultiBranchLog.info(">>> Processing branch: " + branchName + " -> " + targetBranch + " <<<");

                        try {
                            // Ensure clean detached worktree before starting each branch
                            runGit(worktreeDir, "checkout", "--detach");
                            runGit(worktreeDir, "reset", "--hard");
                            runGit(worktreeDir, "clean", "-fd");

                            // Resolve starting point: update existing unmerged branch or start fresh from source origin
                            BranchStartPoint startPoint = resolveBranchStartPoint(worktreeDir, branchName, mapping.getSourceOriginBranch());
                            boolean branchExists = startPoint.isExisting() || isBranchExisting(worktreeDir, branchName);
                            indicator.setText("Processing branch: " + branchName + " (from " + startPoint.getRef() + ")...");
                            MultiBranchLog.info("Branch " + branchName + " start point resolved: ref=" + startPoint.getRef() + " (" + startPoint.getDescription() + "), branchExists=" + branchExists);

                            // Checkout branch starting from resolved start point (with --ignore-other-worktrees in case current worktree is on this branch)
                            GitResult coRes = runGit(worktreeDir, "checkout", "-B", branchName, "--ignore-other-worktrees", startPoint.getRef());
                            if (coRes.exitCode != 0) {
                                // Fallback without --ignore-other-worktrees if older git
                                coRes = runGit(worktreeDir, "checkout", "-B", branchName, startPoint.getRef());
                            }
                            if (coRes.exitCode != 0) {
                                revertWorktreeAndLocalBranch(worktreeDir, repoDir, branchName);
                                String errMsg = "Failed to checkout from " + startPoint.getRef() + ": " + (coRes.stderr.isBlank() ? coRes.stdout : coRes.stderr).trim();
                                MultiBranchLog.error("Branch " + branchName + " checkout failed: " + errMsg);
                                results.add(new MultiBranchResultItem(branchName, targetBranch, null, false, "Not pushed (checkout failed)", null, errMsg));
                                continue;
                            }

                            // Premerge target branch if enabled and branch exists
                            if (config.isPremergeTargetBranch() && branchExists) {
                                String targetRef = resolveTargetBranchRef(worktreeDir, targetBranch);
                                if (targetRef != null) {
                                    indicator.setText("Premerging target branch " + targetRef + " into " + branchName + "...");
                                    MultiBranchLog.info("Premerging target branch " + targetRef + " into " + branchName + "...");
                                    GitResult mergeRes = runGit(worktreeDir, "merge", "--no-edit", targetRef);
                                    if (mergeRes.exitCode != 0) {
                                        runGit(worktreeDir, "merge", "--abort");
                                        revertWorktreeAndLocalBranch(worktreeDir, repoDir, branchName);
                                        String errMsg = "Failed to premerge target branch '" + targetRef + "': " + (mergeRes.stderr.isBlank() ? mergeRes.stdout : mergeRes.stderr).trim();
                                        MultiBranchLog.error("Branch " + branchName + " premerge failed: " + errMsg);
                                        results.add(new MultiBranchResultItem(branchName, targetBranch, null, false, "Not pushed (premerge conflict)", null, errMsg));
                                        continue;
                                    }
                                    MultiBranchLog.info("Premerge of " + targetRef + " into " + branchName + " completed: " + mergeRes.stdout.trim());
                                } else {
                                    MultiBranchLog.warn("Target branch '" + targetBranch + "' could not be resolved for premerge into " + branchName);
                                }
                            }

                            // Apply patch
                            GitResult applyRes = runGit(worktreeDir, "apply", "--3way", "--binary", tempPatch.getAbsolutePath());
                            if (applyRes.exitCode != 0) {
                                revertWorktreeAndLocalBranch(worktreeDir, repoDir, branchName);
                                String errMsg = "Failed to apply changes: " + (applyRes.stderr.isBlank() ? applyRes.stdout : applyRes.stderr).trim();
                                MultiBranchLog.error("Branch " + branchName + " patch apply failed: " + errMsg);
                                results.add(new MultiBranchResultItem(branchName, targetBranch, null, false, "Not pushed (patch conflict)", null, errMsg));
                                continue;
                            }

                            // Stage and Commit
                            runGit(worktreeDir, "add", "-A");
                            GitResult commitRes = runGit(worktreeDir, "commit", "-m", commitMessage);
                            if (commitRes.exitCode != 0) {
                                revertWorktreeAndLocalBranch(worktreeDir, repoDir, branchName);
                                String errMsg = "Commit failed: " + (commitRes.stderr.isBlank() ? commitRes.stdout : commitRes.stderr).trim();
                                MultiBranchLog.error("Branch " + branchName + " commit failed: " + errMsg);
                                results.add(new MultiBranchResultItem(branchName, targetBranch, null, false, "Not pushed (commit failed)", null, errMsg));
                                continue;
                            }

                            // Get commit hash
                            GitResult revRes = runGit(worktreeDir, "rev-parse", "--short", "HEAD");
                            String commitHash = revRes.stdout.trim();
                            MultiBranchLog.info("Branch " + branchName + " commit created successfully: " + commitHash);

                            // Push to origin with check if branch is behind remote HEAD
                            boolean pushed = false;
                            String pushDetails = "Local only";
                            boolean pushFailed = false;

                            if (config.isPushAfterCommit()) {
                                indicator.setText("Checking remote status for " + branchName + "...");
                                BranchBehindStatus behindStatus = checkBranchBehindStatus(worktreeDir, branchName);

                                boolean useForceLease = forcePushAll[0];
                                boolean skipThisPush = skipAllRemainingBehind[0];

                                if (behindStatus.isBehind() && !forcePushAll[0] && !skipAllRemainingBehind[0]) {
                                    final AtomicInteger userChoice = new AtomicInteger(2); // default: Skip
                                    ApplicationManager.getApplication().invokeAndWait(() -> {
                                        String msg = String.format(
                                                "Branch '%s' is behind remote 'origin/%s' by %d commit(s).\n\n" +
                                                "A standard push will be rejected by Git because the remote branch contains commits that are not present locally.\n\n" +
                                                "Do you want to push using --force-with-lease?",
                                                branchName, branchName, behindStatus.getBehindCount()
                                        );
                                        String[] options = new String[]{
                                                "Push with Force-with-lease",
                                                "Force-push All Remaining",
                                                "Skip Push",
                                                "Cancel Push"
                                        };
                                        int res = Messages.showDialog(
                                                project,
                                                msg,
                                                "Branch Behind Remote - " + branchName,
                                                options,
                                                0,
                                                Messages.getWarningIcon()
                                        );
                                        userChoice.set(res);
                                    });

                                    int choice = userChoice.get();
                                    if (choice == 0) {
                                        useForceLease = true;
                                    } else if (choice == 1) {
                                        useForceLease = true;
                                        forcePushAll[0] = true;
                                    } else if (choice == 2) {
                                        skipThisPush = true;
                                    } else {
                                        skipThisPush = true;
                                        skipAllRemainingBehind[0] = true;
                                    }
                                }

                                if (skipThisPush) {
                                    pushDetails = "Skipped (branch is behind remote by " + behindStatus.getBehindCount() + " commit(s))";
                                } else if (useForceLease) {
                                    indicator.setText("Pushing " + branchName + " with --force-with-lease...");
                                    GitResult pushRes = runGit(worktreeDir, "push", "--force-with-lease", "-u", "origin", branchName);
                                    if (pushRes.exitCode == 0) {
                                        pushed = true;
                                        pushDetails = "Pushed with --force-with-lease";
                                    } else {
                                        pushDetails = "Failed (--force-with-lease): " + pushRes.stderr.trim();
                                        pushFailed = true;
                                    }
                                } else {
                                    indicator.setText("Pushing " + branchName + " to origin...");
                                    GitResult pushRes = runGit(worktreeDir, "push", "-u", "origin", branchName);
                                    if (pushRes.exitCode == 0) {
                                        pushed = true;
                                        pushDetails = "Pushed to origin";
                                    } else {
                                        String err = pushRes.stderr.toLowerCase();
                                        if (err.contains("behind") || err.contains("rejected") || err.contains("non-fast-forward") || err.contains("fetch first")) {
                                            final AtomicInteger retryChoice = new AtomicInteger(1);
                                            ApplicationManager.getApplication().invokeAndWait(() -> {
                                                String retryMsg = "Push for branch '" + branchName + "' was rejected because it is behind remote:\n\n"
                                                        + pushRes.stderr.trim() + "\n\n"
                                                        + "Do you want to retry pushing using --force-with-lease?";
                                                int res = Messages.showYesNoDialog(
                                                        project,
                                                        retryMsg,
                                                        "Push Rejected - " + branchName,
                                                        "Retry with Force-with-lease",
                                                        "Skip Push",
                                                        Messages.getWarningIcon()
                                                );
                                                retryChoice.set(res);
                                            });

                                            if (retryChoice.get() == Messages.YES) {
                                                indicator.setText("Retrying push for " + branchName + " with --force-with-lease...");
                                                GitResult retryRes = runGit(worktreeDir, "push", "--force-with-lease", "-u", "origin", branchName);
                                                if (retryRes.exitCode == 0) {
                                                    pushed = true;
                                                    pushDetails = "Pushed with --force-with-lease";
                                                } else {
                                                    pushDetails = "Failed (--force-with-lease): " + retryRes.stderr.trim();
                                                    pushFailed = true;
                                                }
                                            } else {
                                                pushDetails = "Rejected by remote (not fast-forward)";
                                                pushFailed = true;
                                            }
                                        } else {
                                            pushDetails = "Push failed: " + pushRes.stderr.trim();
                                            pushFailed = true;
                                        }
                                    }
                                }
                            }

                            if (pushFailed) {
                                revertWorktreeAndLocalBranch(worktreeDir, repoDir, branchName);
                                MultiBranchLog.error("Branch " + branchName + " push failed: " + pushDetails);
                                results.add(new MultiBranchResultItem(
                                        branchName,
                                        targetBranch,
                                        commitHash,
                                        false,
                                        pushDetails,
                                        null,
                                        pushDetails
                                ));
                                continue;
                            }

                            // Create MR via GitLab API or generate web URL
                            String mrUrl = null;

                            if (config.isGenerateMrLinks()) {
                                if (pushed && config.isGitLabCreateMr() && gitLabToken != null && !gitLabToken.isBlank()) {
                                    indicator.setText("Creating GitLab MR for " + branchName + "...");
                                    GitLabApiService.MrResult mrRes = GitLabApiService.createOrFindMergeRequest(
                                            repoDir,
                                            config.getGitLabHost(),
                                            gitLabToken,
                                            branchName,
                                            targetBranch,
                                            config.getFormattedCommitMessage(),
                                            "Auto-created by Multi-Branch Plugin",
                                            gitLabUserId,
                                            config.isGitLabDeleteSourceBranch(),
                                            config.isGitLabSquashCommits()
                                    );
                                    if (mrRes.isSuccess()) {
                                        mrUrl = mrRes.getWebUrl();
                                        MultiBranchLog.info("Branch " + branchName + " GitLab MR created/found: " + mrUrl);
                                    } else {
                                        mrUrl = GitLabApiService.generateWebMrUrl(repoDir, config.getGitLabHost(), branchName, targetBranch);
                                        MultiBranchLog.warn("Branch " + branchName + " GitLab MR API call did not succeed (" + mrRes.getMessage() + "), fell back to web URL: " + mrUrl);
                                    }
                                } else {
                                    mrUrl = GitLabApiService.generateWebMrUrl(repoDir, config.getGitLabHost(), branchName, targetBranch);
                                    MultiBranchLog.info("Branch " + branchName + " generated review web URL: " + mrUrl);
                                }
                            }

                            MultiBranchLog.info("Branch " + branchName + " successfully finished. Commit=" + commitHash + ", Push=" + pushDetails + ", MR=" + (mrUrl != null ? mrUrl : "N/A"));

                            results.add(new MultiBranchResultItem(
                                    branchName,
                                    targetBranch,
                                    commitHash,
                                    pushed,
                                    pushDetails,
                                    mrUrl,
                                    null
                            ));

                            if (config.isOpenMrLinksInBrowser() && mrUrl != null && !mrUrl.isBlank()) {
                                final String urlToOpen = mrUrl;
                                ApplicationManager.getApplication().invokeLater(() -> BrowserUtil.browse(urlToOpen));
                            }

                            // Clean worktree before next branch
                            runGit(worktreeDir, "checkout", "--detach");
                            runGit(worktreeDir, "reset", "--hard");
                            runGit(worktreeDir, "clean", "-fd");

                        } catch (Throwable t) {
                            revertWorktreeAndLocalBranch(worktreeDir, repoDir, branchName);
                            String errMsg = "Unexpected error during branch operation: " + t.getMessage();
                            MultiBranchLog.error("Branch " + branchName + " failed with unexpected error: " + errMsg, t);
                            results.add(new MultiBranchResultItem(
                                    branchName,
                                    targetBranch,
                                    null,
                                    false,
                                    "Failed",
                                    null,
                                    errMsg
                            ));
                        }
                    }

                } catch (Exception ex) {
                    MultiBranchLog.error("Error during multi-branch execution: " + ex.getMessage(), ex);
                    notifyError(project, "Error during multi-branch execution: " + ex.getMessage());
                } finally {
                    // Clean up worktree
                    if (worktreeDir != null && worktreeDir.exists()) {
                        indicator.setText("Cleaning up temporary worktree...");
                        MultiBranchLog.info("Removing temporary worktree: " + worktreeDir.getAbsolutePath());
                        runGit(repoDir, "worktree", "remove", "--force", worktreeDir.getAbsolutePath());
                    }
                    // Clean up patch
                    if (tempPatch != null && tempPatch.exists()) {
                        tempPatch.delete();
                        MultiBranchLog.info("Deleted temporary patch file.");
                    }

                    boolean anyCommitSucceeded = results.stream().anyMatch(MultiBranchResultItem::isSuccess);
                    Set<String> targetSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    targetSet.addAll(targetRelativePaths);

                    if (anyCommitSucceeded) {
                        // The changes were committed to target branches.
                        // Ensure repoDir is clean and committed files are NOT resurrected.
                        Set<String> successfulBranchNames = results.stream()
                                .filter(MultiBranchResultItem::isSuccess)
                                .map(MultiBranchResultItem::getBranchName)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet());

                        boolean initialBranchWasCommitted = (initialBranch != null && successfulBranchNames.contains(initialBranch));

                        if (initialBranchWasCommitted) {
                            // initialBranch was updated by the worktree commit to the new HEAD.
                            // Reset hard to HEAD syncs repoDir's working tree and index to the new commit cleanly!
                            runGit(repoDir, "reset", "--hard", "HEAD");
                        } else {
                            // Target files were committed to other branches, not to initialBranch.
                            // Revert them on initialBranch so they don't remain dirty.
                            for (String rel : targetRelativePaths) {
                                runGit(repoDir, "reset", "HEAD", "--", rel);
                                runGit(repoDir, "checkout", "HEAD", "--", rel);
                                runGit(repoDir, "clean", "-fd", "--", rel);
                                try {
                                    File f = new File(repoDir, rel);
                                    if (f.exists()) {
                                        Files.deleteIfExists(f.toPath());
                                    }
                                } catch (Exception ignored) {}
                            }
                            runGit(repoDir, "reset", "--hard", "HEAD");
                        }

                        // Post-action checkout if enabled
                        if (config.isCheckoutTestAfter()) {
                            String targetBranch = config.getCheckoutBranch();
                            if (targetBranch == null || targetBranch.isBlank()) {
                                targetBranch = "deploy/test";
                            } else {
                                targetBranch = targetBranch.trim();
                            }
                            if (targetBranch.startsWith("origin/")) {
                                targetBranch = targetBranch.substring("origin/".length());
                            }

                            // Also fetch post action checkout branch
                            indicator.setText("Fetching post-action checkout branch '" + targetBranch + "' from origin...");
                            runGit(repoDir, "fetch", "origin", targetBranch);

                            indicator.setText("Checking out " + targetBranch + "...");
                            MultiBranchLog.info("Checking out post-action branch: " + targetBranch);
                            GitResult coTest = runGit(repoDir, "checkout", targetBranch);
                            if (coTest.exitCode != 0) {
                                coTest = runGit(repoDir, "checkout", "-B", targetBranch, "origin/" + targetBranch);
                                if (coTest.exitCode != 0) {
                                    runGit(repoDir, "checkout", "origin/" + targetBranch);
                                }
                            } else {
                                // Fast-forward to match latest origin if commits were merged
                                runGit(repoDir, "merge", "--ff-only", "origin/" + targetBranch);
                            }
                        }
                    } else {
                        MultiBranchLog.warn("No branch commits succeeded; keeping modified files in working directory so changes are not lost.");
                    }

                    // Stash pop if stashed
                    if (didStash) {
                        indicator.setText("Restoring stashed changes from other folders (stash pop)...");
                        MultiBranchLog.info("Restoring stashed changes from other folders (stash pop)...");
                        GitResult popRes = runGit(repoDir, "stash", "pop");
                        if (popRes.exitCode != 0) {
                            MultiBranchLog.warn("Stash pop encountered a conflict: " + popRes.stderr);
                            notifyError(project, "Stash pop encountered a conflict. Your stashed changes are safely preserved in git stash list.\nError: " + popRes.stderr);
                        } else {
                            MultiBranchLog.info("Stash popped successfully.");
                        }

                        // Collect any files reported as dirty after stash pop, EXCLUDING any committed target files
                        GitResult postPopStatus = runGit(repoDir, "status", "--porcelain", "-uall");
                        if (postPopStatus.exitCode == 0) {
                            for (String line : postPopStatus.stdout.split("\\r?\\n")) {
                                if (line.isBlank() || line.length() <= 3) continue;
                                String statusFile = line.substring(3).trim();
                                if (statusFile.startsWith("\"") && statusFile.endsWith("\"")) {
                                    statusFile = statusFile.substring(1, statusFile.length() - 1);
                                }
                                if (statusFile.contains(" -> ")) {
                                    statusFile = statusFile.substring(statusFile.indexOf(" -> ") + 4).trim();
                                }
                                statusFile = statusFile.replace('\\', '/');
                                if (!anyCommitSucceeded || (!targetSet.contains(statusFile) && !isPathMatchingStash(statusFile, targetSet))) {
                                    stashedRelativePaths.add(statusFile);
                                }
                            }
                        }
                    }

                    // Safety net: ensure NO committed file is dirty after post-action checkout or stash pop
                    if (anyCommitSucceeded) {
                        GitResult postStatus = runGit(repoDir, "status", "--porcelain", "-uall");
                        if (postStatus.exitCode == 0 && !postStatus.stdout.isBlank()) {
                            for (String line : postStatus.stdout.split("\\r?\\n")) {
                                if (line.isBlank() || line.length() <= 3) continue;
                                String statusFile = line.substring(3).trim();
                                if (statusFile.startsWith("\"") && statusFile.endsWith("\"")) {
                                    statusFile = statusFile.substring(1, statusFile.length() - 1);
                                }
                                if (statusFile.contains(" -> ")) {
                                    statusFile = statusFile.substring(statusFile.indexOf(" -> ") + 4).trim();
                                }
                                statusFile = statusFile.replace('\\', '/');

                                if (targetSet.contains(statusFile) || isPathMatchingStash(statusFile, targetSet)) {
                                    MultiBranchLog.warn("Committed file '" + statusFile + "' was dirty in working tree after execution. Cleaning it up.");
                                    runGit(repoDir, "reset", "HEAD", "--", statusFile);
                                    runGit(repoDir, "checkout", "HEAD", "--", statusFile);
                                    runGit(repoDir, "clean", "-fd", "--", statusFile);
                                    try {
                                        File f = new File(repoDir, statusFile);
                                        if (f.exists()) {
                                            Files.deleteIfExists(f.toPath());
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }

                    // Log overall execution summary
                    long successCount = results.stream().filter(MultiBranchResultItem::isSuccess).count();
                    long pushedCount = results.stream().filter(MultiBranchResultItem::isPushed).count();
                    long mrCount = results.stream().filter(r -> r.getMrUrl() != null && !r.getMrUrl().isBlank()).count();
                    long failedCount = results.size() - successCount;
                    MultiBranchLog.finishExecution(String.format("Execution finished: %d/%d branches succeeded, %d pushed, %d MRs created, %d failed",
                            successCount, results.size(), pushedCount, mrCount, failedCount));

                    // Save commit message to IDEA VcsConfiguration for reuse
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project != null) {
                            VcsConfiguration vcsConfig = VcsConfiguration.getInstance(project);
                            vcsConfig.saveCommitMessage(config.getCommitMessage());
                            vcsConfig.saveCommitMessage(config.getFormattedCommitMessage());
                        }
                    });

                    // Refresh IntelliJ Git state and restore stashed changes to "Uncommitted changes" changelist
                    final boolean wasStashed = didStash;
                    final Set<String> committedFiles = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    if (anyCommitSucceeded) {
                        committedFiles.addAll(targetRelativePaths);
                    }
                    final Set<String> filesToRestoreToUncommitted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    filesToRestoreToUncommitted.addAll(stashedRelativePaths);
                    filesToRestoreToUncommitted.removeAll(committedFiles);

                    // 1. Safe to update GitRepository on current background thread (avoids EDT assertion)
                    if (repository != null) {
                        try {
                            repository.update();
                        } catch (Exception e) {
                            MultiBranchLog.warn("Failed to update git repository on background thread: " + e.getMessage());
                        }
                    }

                    // 2. Refresh VFS and dirty scopes
                    if (repository != null && repository.getRoot() != null) {
                        repository.getRoot().refresh(true, true, () -> {
                            // postRunnable runs on EDT: offload repository.update() to a pooled background thread
                            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                                if (repository != null) {
                                    try {
                                        repository.update();
                                    } catch (Exception ignored) {}
                                }
                            });
                            if (project != null && !project.isDisposed()) {
                                VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
                                if (wasStashed) {
                                    restoreChangesToUncommittedChangelist(project, repoDir, filesToRestoreToUncommitted, committedFiles);
                                }
                            }
                        });
                    } else if (project != null && !project.isDisposed()) {
                        VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
                        if (wasStashed) {
                            restoreChangesToUncommittedChangelist(project, repoDir, filesToRestoreToUncommitted, committedFiles);
                        }
                    }

                    // Show Result Dialog
                    ApplicationManager.getApplication().invokeLater(() -> {
                        new MultiBranchResultDialog(project, results).show();
                    });
                }
            }
        });
    }

    public static void restoreChangesToUncommittedChangelist(@Nullable Project project, @Nullable File repoDir, @Nullable Set<String> stashedRelativePaths) {
        restoreChangesToUncommittedChangelist(project, repoDir, stashedRelativePaths, null);
    }

    public static void restoreChangesToUncommittedChangelist(
            @Nullable Project project,
            @Nullable File repoDir,
            @Nullable Set<String> stashedRelativePaths,
            @Nullable Set<String> committedRelativePaths) {
        if (project == null || project.isDisposed()) return;
        if (stashedRelativePaths == null || stashedRelativePaths.isEmpty()) return;

        ChangeListManager clm = ChangeListManager.getInstance(project);
        clm.invokeAfterUpdate(true, () -> {
            if (project.isDisposed()) return;
            try {
                LocalChangeList targetList = null;
                for (LocalChangeList cl : clm.getChangeLists()) {
                    if (UNCOMMITTED_CHANGES_CHANGELIST_NAME.equalsIgnoreCase(cl.getName())) {
                        targetList = cl;
                        break;
                    }
                }
                if (targetList == null) {
                    targetList = clm.addChangeList(UNCOMMITTED_CHANGES_CHANGELIST_NAME, "Restored uncommitted changes from stash");
                    MultiBranchLog.info("Created changelist '" + UNCOMMITTED_CHANGES_CHANGELIST_NAME + "' for restored uncommitted changes.");
                }

                List<Change> changesToMove = new ArrayList<>();
                for (Change change : clm.getAllChanges()) {
                    ContentRevision rev = change.getAfterRevision() != null ? change.getAfterRevision() : change.getBeforeRevision();
                    if (rev != null) {
                        String fullPathStr = rev.getFile().getPath();
                        String relPath = getRelativePath(repoDir, fullPathStr);
                        if (relPath == null) {
                            relPath = rev.getFile().getName();
                        }

                        // Never restore files that have been committed
                        if (committedRelativePaths != null && !committedRelativePaths.isEmpty() && isPathMatchingStash(relPath, committedRelativePaths)) {
                            continue;
                        }

                        boolean match = isPathMatchingStash(relPath, stashedRelativePaths);
                        if (match && !targetList.getChanges().contains(change)) {
                            changesToMove.add(change);
                        }
                    }
                }

                if (!changesToMove.isEmpty()) {
                    MultiBranchLog.info("Moving " + changesToMove.size() + " restored change(s) to changelist '" + UNCOMMITTED_CHANGES_CHANGELIST_NAME + "'");
                    clm.moveChangesTo(targetList, changesToMove);
                } else {
                    MultiBranchLog.info("No matching changes found to move to changelist '" + UNCOMMITTED_CHANGES_CHANGELIST_NAME + "'.");
                }
            } catch (Exception e) {
                MultiBranchLog.warn("Failed to restore changes to '" + UNCOMMITTED_CHANGES_CHANGELIST_NAME + "': " + e.getMessage());
            }
        });
    }

    public static String getRelativePath(@Nullable File repoDir, @Nullable String fullPath) {
        if (repoDir == null || fullPath == null) return fullPath;
        try {
            Path repoPath = repoDir.getCanonicalFile().toPath();
            Path filePath = new File(fullPath).getCanonicalFile().toPath();
            if (filePath.startsWith(repoPath)) {
                return repoPath.relativize(filePath).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {}
        try {
            Path repoPath = repoDir.toPath().toAbsolutePath().normalize();
            Path filePath = Paths.get(fullPath).toAbsolutePath().normalize();
            if (filePath.startsWith(repoPath)) {
                return repoPath.relativize(filePath).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {}
        String normRepo = repoDir.getAbsolutePath().replace('\\', '/').replaceAll("/+$", "");
        String normFull = fullPath.replace('\\', '/');
        if (normFull.toLowerCase().startsWith(normRepo.toLowerCase() + "/")) {
            return normFull.substring(normRepo.length() + 1);
        }
        return normFull;
    }

    public static boolean isPathMatchingStash(String relPath, @Nullable Set<String> stashedRelativePaths) {
        if (stashedRelativePaths == null || stashedRelativePaths.isEmpty()) {
            return true;
        }
        String normRel = relPath.replace('\\', '/');
        if (normRel.startsWith("./")) normRel = normRel.substring(2);
        if (normRel.startsWith("/")) normRel = normRel.substring(1);

        for (String stashedRel : stashedRelativePaths) {
            String normStashed = stashedRel.replace('\\', '/');
            if (normStashed.startsWith("./")) normStashed = normStashed.substring(2);
            if (normStashed.startsWith("/")) normStashed = normStashed.substring(1);

            if (normStashed.equalsIgnoreCase(normRel)) {
                return true;
            }
            if (normStashed.endsWith("/" + normRel) || normRel.endsWith("/" + normStashed)) {
                return true;
            }
        }
        return false;
    }

    public static void revertWorktreeAndLocalBranch(File worktreeDir, File repoDir, String branchName) {
        try {
            if (worktreeDir != null && worktreeDir.exists()) {
                runGit(worktreeDir, "checkout", "--detach");
                runGit(worktreeDir, "reset", "--hard");
                runGit(worktreeDir, "clean", "-fd");
                if (branchName != null && !branchName.isBlank()) {
                    GitResult remoteCheck = runGit(worktreeDir, "rev-parse", "--verify", "origin/" + branchName);
                    if (remoteCheck.exitCode == 0) {
                        runGit(worktreeDir, "branch", "-f", branchName, "origin/" + branchName);
                    } else {
                        runGit(worktreeDir, "branch", "-D", branchName);
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            if (repoDir != null && repoDir.exists() && branchName != null && !branchName.isBlank()) {
                GitResult remoteCheck = runGit(repoDir, "rev-parse", "--verify", "origin/" + branchName);
                if (remoteCheck.exitCode == 0) {
                    runGit(repoDir, "branch", "-f", branchName, "origin/" + branchName);
                } else {
                    runGit(repoDir, "branch", "-D", branchName);
                }
            }
        } catch (Exception ignored) {}
    }

    public static String getRepositoryWebUrl(File repoDir) {
        String originUrl = GitLabApiService.getOriginRemoteUrl(repoDir);
        GitLabApiService.GitLabProjectInfo info = GitLabApiService.parseProjectInfo(originUrl, null);
        return info != null ? info.getWebProjectUrl() : null;
    }

    public static String generateReviewUrl(String webUrl, String sourceBranch, String targetBranch) {
        if (webUrl == null || webUrl.isBlank()) return null;
        boolean isGitHub = webUrl.contains("github.com");

        if (isGitHub) {
            return webUrl + "/compare/" +
                    encodeGitHubBranch(targetBranch) + "..." + encodeGitHubBranch(sourceBranch) + "?expand=1";
        } else {
            return webUrl + "/-/merge_requests/new?merge_request[source_branch]=" +
                    URLEncoder.encode(sourceBranch, StandardCharsets.UTF_8) +
                    "&merge_request[target_branch]=" +
                    URLEncoder.encode(targetBranch, StandardCharsets.UTF_8);
        }
    }

    private static String encodeGitHubBranch(String branch) {
        return URLEncoder.encode(branch, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    private static void notifyError(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("MultiBranchPlugin")
                    .createNotification("Multi-Branch Workflow", message, NotificationType.ERROR)
                    .notify(project);
        });
    }

    public static GitResult runGit(File workingDir, String... args) {
        long startTime = System.currentTimeMillis();
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            for (String a : args) cmd.add(a);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDir);
            pb.redirectErrorStream(false);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process p = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread outThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            outThread.start();
            errThread.start();

            boolean finished = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                outThread.interrupt();
                errThread.interrupt();
                long duration = System.currentTimeMillis() - startTime;
                MultiBranchLog.logGitCommand(workingDir, args, -1, duration, stdout.toString(), "Git command timed out after 120 seconds");
                return new GitResult(-1, stdout.toString(), "Git command timed out after 120 seconds: git " + String.join(" ", args));
            }

            outThread.join(2000);
            errThread.join(2000);

            long duration = System.currentTimeMillis() - startTime;
            MultiBranchLog.logGitCommand(workingDir, args, p.exitValue(), duration, stdout.toString(), stderr.toString());
            return new GitResult(p.exitValue(), stdout.toString(), stderr.toString());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            MultiBranchLog.logGitCommand(workingDir, args, -1, duration, "", e.getMessage());
            return new GitResult(-1, "", e.getMessage());
        }
    }

    public static class GitResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public GitResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    public static class BranchBehindStatus {
        private final boolean remoteExists;
        private final int behindCount;
        private final int aheadCount;

        public BranchBehindStatus(boolean remoteExists, int behindCount, int aheadCount) {
            this.remoteExists = remoteExists;
            this.behindCount = behindCount;
            this.aheadCount = aheadCount;
        }

        public boolean isRemoteExists() { return remoteExists; }
        public int getBehindCount() { return behindCount; }
        public int getAheadCount() { return aheadCount; }
        public boolean isBehind() { return remoteExists && behindCount > 0; }
        public boolean isAhead() { return remoteExists && behindCount == 0 && aheadCount > 0; }
        public boolean isUpToDate() { return remoteExists && behindCount == 0 && aheadCount == 0; }
        public boolean isDiverged() { return remoteExists && behindCount > 0 && aheadCount > 0; }
    }

    public static BranchBehindStatus checkBranchBehindStatus(File dir, String branchName) {
        return checkBranchBehindStatus(dir, "HEAD", branchName);
    }

    public static BranchBehindStatus checkBranchBehindStatus(File dir, String localRef, String branchName) {
        if (dir == null || branchName == null || branchName.isBlank()) {
            return new BranchBehindStatus(false, 0, 0);
        }
        String ref = (localRef != null && !localRef.isBlank()) ? localRef : "HEAD";
        GitResult checkRef = runGit(dir, "rev-parse", "--verify", "origin/" + branchName);
        if (checkRef.exitCode != 0) {
            return new BranchBehindStatus(false, 0, 0);
        }

        GitResult revList = runGit(dir, "rev-list", "--left-right", "--count", ref + "...origin/" + branchName);
        if (revList.exitCode == 0) {
            String[] parts = revList.stdout.trim().split("\\s+");
            if (parts.length >= 2) {
                try {
                    int ahead = Integer.parseInt(parts[0]);
                    int behind = Integer.parseInt(parts[1]);
                    return new BranchBehindStatus(true, behind, ahead);
                } catch (NumberFormatException ignored) {}
            }
        }
        return new BranchBehindStatus(true, 0, 0);
    }

    public static class BranchStartPoint {
        private final String ref;
        private final boolean existing;
        private final String description;

        public BranchStartPoint(String ref, boolean existing, String description) {
            this.ref = ref;
            this.existing = existing;
            this.description = description;
        }

        public String getRef() { return ref; }
        public boolean isExisting() { return existing; }
        public String getDescription() { return description; }
    }

    public static BranchStartPoint resolveBranchStartPoint(File gitDir, String branchName, String defaultSourceOriginBranch) {
        if (gitDir == null || branchName == null || branchName.isBlank()) {
            return new BranchStartPoint(defaultSourceOriginBranch != null ? defaultSourceOriginBranch : "", false, "Source origin branch " + defaultSourceOriginBranch);
        }

        String sourceRef = defaultSourceOriginBranch != null ? defaultSourceOriginBranch.trim() : "";
        if (!sourceRef.isEmpty()) {
            GitResult checkSource = runGit(gitDir, "rev-parse", "--verify", sourceRef);
            if (checkSource.exitCode != 0 && !sourceRef.startsWith("origin/")) {
                GitResult checkOriginSource = runGit(gitDir, "rev-parse", "--verify", "origin/" + sourceRef);
                if (checkOriginSource.exitCode == 0) {
                    sourceRef = "origin/" + sourceRef;
                }
            }
        }

        // 1. Check if remote tracking branch origin/<branchName> exists
        GitResult remoteCheck = runGit(gitDir, "rev-parse", "--verify", "origin/" + branchName);
        boolean remoteExists = (remoteCheck.exitCode == 0);

        if (remoteExists) {
            // Check if origin/<branchName> has already been merged into source branch
            boolean isMergedIntoSource = false;
            if (!sourceRef.isEmpty()) {
                GitResult ancestorCheck = runGit(gitDir, "merge-base", "--is-ancestor", "origin/" + branchName, sourceRef);
                isMergedIntoSource = (ancestorCheck.exitCode == 0);
            }

            if (!isMergedIntoSource) {
                // Remote branch has unmerged work!
                // Check if local branch exists and is ahead of origin/<branchName>
                GitResult localCheck = runGit(gitDir, "rev-parse", "--verify", "refs/heads/" + branchName);
                if (localCheck.exitCode == 0) {
                    BranchBehindStatus behindStatus = checkBranchBehindStatus(gitDir, branchName, branchName);
                    if (behindStatus.getAheadCount() > 0 && behindStatus.getBehindCount() == 0) {
                        return new BranchStartPoint(branchName, true, "Local branch '" + branchName + "' (ahead of origin by " + behindStatus.getAheadCount() + " commit(s))");
                    }
                }
                return new BranchStartPoint("origin/" + branchName, true, "Existing remote branch 'origin/" + branchName + "' (unmerged)");
            }
        }

        // 2. If not on remote (or already merged on remote), check if local branch exists with unmerged commits
        GitResult localCheck = runGit(gitDir, "rev-parse", "--verify", "refs/heads/" + branchName);
        if (localCheck.exitCode == 0) {
            boolean localMerged = false;
            if (!sourceRef.isEmpty()) {
                GitResult ancestorCheck = runGit(gitDir, "merge-base", "--is-ancestor", branchName, sourceRef);
                localMerged = (ancestorCheck.exitCode == 0);
            }
            if (!localMerged) {
                return new BranchStartPoint(branchName, true, "Local branch '" + branchName + "' (unmerged commits)");
            }
        }

        // 3. Fresh branch starting from source origin branch
        return new BranchStartPoint(defaultSourceOriginBranch != null ? defaultSourceOriginBranch : "", false, "Source origin branch '" + defaultSourceOriginBranch + "'");
    }

    public static boolean isBranchExisting(File gitDir, String branchName) {
        if (gitDir == null || branchName == null || branchName.isBlank()) return false;
        GitResult remoteCheck = runGit(gitDir, "rev-parse", "--verify", "origin/" + branchName);
        if (remoteCheck.exitCode == 0) return true;
        GitResult localCheck = runGit(gitDir, "rev-parse", "--verify", "refs/heads/" + branchName);
        return localCheck.exitCode == 0;
    }

    public static String resolveTargetBranchRef(File gitDir, String targetBranchName) {
        if (gitDir == null || targetBranchName == null || targetBranchName.isBlank()) {
            return null;
        }
        String target = targetBranchName.trim();
        if (target.startsWith("origin/")) {
            GitResult res = runGit(gitDir, "rev-parse", "--verify", target);
            if (res.exitCode == 0) return target;
            String local = target.substring("origin/".length());
            GitResult localRes = runGit(gitDir, "rev-parse", "--verify", local);
            if (localRes.exitCode == 0) return local;
        } else {
            GitResult res = runGit(gitDir, "rev-parse", "--verify", "origin/" + target);
            if (res.exitCode == 0) return "origin/" + target;
            GitResult localRes = runGit(gitDir, "rev-parse", "--verify", target);
            if (localRes.exitCode == 0) return target;
        }
        return null;
    }
}
