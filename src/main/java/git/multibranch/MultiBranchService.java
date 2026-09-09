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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiBranchService {

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
                List<String> targetRelativePaths = new ArrayList<>();

                try {
                    indicator.setText("Preparing changes from changelist '" + config.getChangelistName() + "'...");

                    // 1. Fetch origin
                    if (config.isFetchOriginFirst()) {
                        indicator.setText("Fetching origin...");
                        runGit(repoDir, "fetch", "origin");
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
                        notifyError(project, "Changelist '" + config.getChangelistName() + "' not found.");
                        return;
                    }

                    for (Change change : targetList.getChanges()) {
                        ContentRevision rev = change.getAfterRevision() != null ? change.getAfterRevision() : change.getBeforeRevision();
                        if (rev != null) {
                            String fullPath = rev.getFile().getPath();
                            String rel = new File(repoDir, ".").toURI().relativize(new File(fullPath).toURI()).getPath();
                            targetRelativePaths.add(rel.replace('\\', '/'));
                        }
                    }

                    if (targetRelativePaths.isEmpty()) {
                        notifyError(project, "No modified files in changelist '" + config.getChangelistName() + "'.");
                        return;
                    }

                    // Stage intent for any new files in target list
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
                        notifyError(project, "No diff produced for the files in changelist '" + config.getChangelistName() + "'.");
                        return;
                    }
                    Files.writeString(tempPatch.toPath(), diffRes.stdout, StandardCharsets.UTF_8);

                    // 4. Check if uncommitted changes exist in other folders / working tree
                    boolean hasOtherChanges = false;
                    GitResult statusRes = runGit(repoDir, "status", "--porcelain");
                    if (statusRes.exitCode == 0) {
                        Set<String> targetSet = new HashSet<>(targetRelativePaths);
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
                            if (!targetSet.contains(statusFile)) {
                                hasOtherChanges = true;
                                break;
                            }
                        }
                    }

                    // 5. If uncommitted changes exist in other folders and stash is enabled:
                    // First, revert target files from workdir so they are not stashed into other changes!
                    if (hasOtherChanges && config.isStashOtherChanges()) {
                        indicator.setText("Reverting target changes locally before stashing other folders...");
                        List<String> coCmd = new ArrayList<>(List.of("checkout", "HEAD", "--"));
                        coCmd.addAll(targetRelativePaths);
                        runGit(repoDir, coCmd.toArray(new String[0]));

                        indicator.setText("Stashing uncommitted changes from other folders...");
                        GitResult stashRes = runGit(repoDir, "stash", "push", "--include-untracked", "-m", "multibranch_temp_stash_" + System.currentTimeMillis());
                        didStash = (stashRes.exitCode == 0 && !stashRes.stdout.contains("No local changes to save"));
                    } else {
                        // Revert target files from workdir since patch is safely stored
                        List<String> coCmd = new ArrayList<>(List.of("checkout", "HEAD", "--"));
                        coCmd.addAll(targetRelativePaths);
                        runGit(repoDir, coCmd.toArray(new String[0]));
                    }

                    // 6. Create detached worktree
                    indicator.setText("Setting up isolated git worktree...");
                    worktreeDir = new File(repoDir, ".git/temp_mb_worktree_" + System.currentTimeMillis());
                    GitResult wtRes = runGit(repoDir, "worktree", "add", "--detach", worktreeDir.getAbsolutePath());
                    if (wtRes.exitCode != 0) {
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

                        try {
                            // Ensure clean detached worktree before starting each branch
                            runGit(worktreeDir, "checkout", "--detach");
                            runGit(worktreeDir, "reset", "--hard");
                            runGit(worktreeDir, "clean", "-fd");

                            // Checkout branch starting from source origin branch (with --ignore-other-worktrees in case current worktree is on this branch)
                            GitResult coRes = runGit(worktreeDir, "checkout", "-B", branchName, "--ignore-other-worktrees", mapping.getSourceOriginBranch());
                            if (coRes.exitCode != 0) {
                                // Fallback without --ignore-other-worktrees if older git
                                coRes = runGit(worktreeDir, "checkout", "-B", branchName, mapping.getSourceOriginBranch());
                            }
                            if (coRes.exitCode != 0) {
                                revertWorktreeAndLocalBranch(worktreeDir, repoDir, branchName);
                                String errMsg = "Failed to checkout from " + mapping.getSourceOriginBranch() + ": " + (coRes.stderr.isBlank() ? coRes.stdout : coRes.stderr).trim();
                                results.add(new MultiBranchResultItem(branchName, targetBranch, null, false, "Not pushed (checkout failed)", null, errMsg));
                                continue;
                            }

                            // Apply patch
                            GitResult applyRes = runGit(worktreeDir, "apply", "--3way", "--binary", tempPatch.getAbsolutePath());
                            if (applyRes.exitCode != 0) {
                                revertWorktreeAndLocalBranch(worktreeDir, repoDir, branchName);
                                String errMsg = "Failed to apply changes: " + (applyRes.stderr.isBlank() ? applyRes.stdout : applyRes.stderr).trim();
                                results.add(new MultiBranchResultItem(branchName, targetBranch, null, false, "Not pushed (patch conflict)", null, errMsg));
                                continue;
                            }

                            // Stage and Commit
                            runGit(worktreeDir, "add", "-A");
                            GitResult commitRes = runGit(worktreeDir, "commit", "-m", commitMessage);
                            if (commitRes.exitCode != 0) {
                                revertWorktreeAndLocalBranch(worktreeDir, repoDir, branchName);
                                String errMsg = "Commit failed: " + (commitRes.stderr.isBlank() ? commitRes.stdout : commitRes.stderr).trim();
                                results.add(new MultiBranchResultItem(branchName, targetBranch, null, false, "Not pushed (commit failed)", null, errMsg));
                                continue;
                            }

                            // Get commit hash
                            GitResult revRes = runGit(worktreeDir, "rev-parse", "--short", "HEAD");
                            String commitHash = revRes.stdout.trim();

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
                                    } else {
                                        mrUrl = GitLabApiService.generateWebMrUrl(repoDir, config.getGitLabHost(), branchName, targetBranch);
                                    }
                                } else {
                                    mrUrl = GitLabApiService.generateWebMrUrl(repoDir, config.getGitLabHost(), branchName, targetBranch);
                                }
                            }

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
                    notifyError(project, "Error during multi-branch execution: " + ex.getMessage());
                } finally {
                    // Clean up worktree
                    if (worktreeDir != null && worktreeDir.exists()) {
                        indicator.setText("Cleaning up temporary worktree...");
                        runGit(repoDir, "worktree", "remove", "--force", worktreeDir.getAbsolutePath());
                    }
                    // Clean up patch
                    if (tempPatch != null && tempPatch.exists()) {
                        tempPatch.delete();
                    }

                    // If staying on current branch, sync working tree with HEAD in case current branch was committed to
                    if (!config.isCheckoutTestAfter()) {
                        runGit(repoDir, "reset", "--hard", "HEAD");
                    }

                    // Checkout configured branch after actions
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

                    if (config.isCheckoutTestAfter()) {
                        indicator.setText("Checking out " + targetBranch + "...");
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

                    // Stash pop if stashed
                    if (didStash) {
                        indicator.setText("Restoring stashed changes from other folders (stash pop)...");
                        GitResult popRes = runGit(repoDir, "stash", "pop");
                        if (popRes.exitCode != 0) {
                            notifyError(project, "Stash pop encountered a conflict. Your stashed changes are safely preserved in git stash list.\nError: " + popRes.stderr);
                        }
                    }

                    // Save commit message to IDEA VcsConfiguration for reuse
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project != null) {
                            VcsConfiguration vcsConfig = VcsConfiguration.getInstance(project);
                            vcsConfig.saveCommitMessage(config.getCommitMessage());
                            vcsConfig.saveCommitMessage(config.getFormattedCommitMessage());
                        }
                    });

                    // Refresh IntelliJ Git state
                    ApplicationManager.getApplication().invokeLater(() -> {
                        repository.update();
                        VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
                    });

                    // Show Result Dialog
                    ApplicationManager.getApplication().invokeLater(() -> {
                        new MultiBranchResultDialog(project, results).show();
                    });
                }
            }
        });
    }

    public static void revertWorktreeAndLocalBranch(File worktreeDir, File repoDir, String branchName) {
        try {
            if (worktreeDir != null && worktreeDir.exists()) {
                runGit(worktreeDir, "checkout", "--detach");
                runGit(worktreeDir, "reset", "--hard");
                runGit(worktreeDir, "clean", "-fd");
                if (branchName != null && !branchName.isBlank()) {
                    runGit(worktreeDir, "branch", "-D", branchName);
                }
            }
        } catch (Exception ignored) {}
        try {
            if (repoDir != null && repoDir.exists() && branchName != null && !branchName.isBlank()) {
                runGit(repoDir, "branch", "-D", branchName);
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
                return new GitResult(-1, stdout.toString(), "Git command timed out after 120 seconds: git " + String.join(" ", args));
            }

            outThread.join(2000);
            errThread.join(2000);

            return new GitResult(p.exitValue(), stdout.toString(), stderr.toString());
        } catch (Exception e) {
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
}
