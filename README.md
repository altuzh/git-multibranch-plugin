# Git Multi-Branch Plugin for IntelliJ IDEA

An IntelliJ IDEA plugin for automating multi-branch Git workflows across target environments (`dev`, `test`, `uat`, `prod`).

## Features

- **Multi-Branch Target Commit & Push**:
  - Automatically isolates commits into a temporary detached Git worktree (`.git/temp_mb_worktree_*`).
  - Commits changes to each configured branch (`{taskPrefix}-dev`, `{taskPrefix}-test`, `{taskPrefix}-uat`, `{taskPrefix}-prod`) starting from their corresponding origin tracking branches.
  - Automatically pushes branches to `origin` with tracking (`-u`).
  - **Behind-Remote & Force-with-Lease Protection**: Automatically inspects whether the branch already exists on remote and is behind/diverged from remote HEAD. Prompts the developer with an interactive confirmation dialog to push safely with `--force-with-lease` (or force-push all / skip / cancel).
- **Designated Branch Context Detection**:
  - Automatically identifies whether you are currently on one of the target task branches (e.g. `TASK-101-dev`), extracts task prefix cleanly, and highlights the current branch in the dialog UI with `[CURRENT BRANCH]`.
  - Tags branches already existing on origin with `[ON REMOTE]`.
- **Configurable Branch Mappings**:
  - Configure target branches via **Settings -> Version Control -> Multi-Branch Workflow** or via the **Configure Branches...** button in the commit dialog.
  - Add, edit, remove, reorder, or reset branch mappings (source tracking branch, MR merge target branch, local branch suffix, enabled/disabled by default).
  - **"Allow merge" Column**: Configure which branches allow automated MR merging. If unchecked, the Merge MR option will not be applied to this branch.
  - Customize post-action checkout branch (defaults to `deploy/test`).
- **Confirmation Window with Planned Actions**:
  - Displays an interactive confirmation window before executing multi-branch operations, detailing every upcoming action: worktree checkouts, patch applications, commits, pushes, MR creations, and auto-merge actions.
- **Targeted Changelist Filtering**:
  - Extracts only modified files from the chosen IntelliJ changelist (e.g. `Changes`), completely ignoring other folders/changelists.
- **Commit Message History**:
  - Reuses and syncs with IntelliJ IDEA's native commit message history via `Ctrl+M` or the **Recent Messages...** button.
  - Saves all commit messages into IDEA's recent messages list.
- **Working Tree Protection (Stash, Fetch & Checkout)**:
  - If uncommitted changes exist in other folders/changelists (e.g. `pom.xml`, local configs), the plugin safely stashes them before commit actions.
  - **Fetch Post-Action Checkout Branch**: Automatically fetches `origin/<checkoutBranch>` to update remote refs with the latest commits (including newly merged MR commits).
  - Automatically checks out the configured branch (`deploy/test` by default) after actions complete and fast-forwards to latest origin.
  - Restores the stashed changes via `git stash pop` on top of the checkout branch.
- **Automated GitLab Merge Requests & Auto-Merge (Merge MR)**:
  - Automatically creates Merge Requests directly via GitLab API upon successful push.
  - **Merge MR Option**: Automatically merges/accepts created MRs via GitLab API (`PUT /api/v4/projects/:id/merge_requests/:iid/merge`) for branches with "Allow merge" enabled.
  - **Smart Browser Management**: If MR merge succeeds with no errors, opening the success MR in the browser is suppressed. If an error occurs during merge (e.g. conflict, discussions required), the MR is opened in the browser for developer inspection.
  - Automatically assigns created MRs to the authenticated user (login person) by querying `/api/v4/user`.
  - Configurable GitLab options:
    - **Delete source branch** when merge request is accepted (`remove_source_branch = true`).
    - **Squash commits** when merge request is accepted (`squash = true`).
    - Reuses existing open MRs (handles HTTP 409 Conflict gracefully) instead of erroring on duplicate runs.
  - **Per-Host Token Storage**:
    - GitLab Personal Access Tokens are stored securely in IntelliJ IDEA's native `PasswordSafe` (Credential Store / OS Keychain) **per detected GitLab host** (e.g. `GitLabToken_gitlab.company.com`, `GitLabToken_gitlab.com`).
    - Working on multiple repositories across different GitLab servers (corporate self-hosted, client instances, or gitlab.com) works seamlessly without token collision or having to re-authenticate.
    - Repositories sharing the same GitLab host automatically reuse the token saved for that host.
    - Backward-compatible: existing single-host tokens are preserved and migrated cleanly without leaking to other hosts.
  - Automatic repository origin detection (no manual repository URL configuration needed).
  - Dynamic Settings UI displaying the active host in the token label (e.g. `GitLab Personal Access Token (gitlab.company.com):`) and showing the auto-detected origin URL in the host field placeholder.
  - Built-in "Test Connection" button verifying token validity and API reachability directly from the Settings dialog.
  - Graceful fallback to pre-filled web MR creation links when API token is not configured or on network failures.
- **Execution Review Window**:
  - Displays a comprehensive review window upon completion of all multi-branch operations, detailing branch status, commit hashes, push details, MR links, and auto-merge statuses.
- **Convenient UI Entry Points**:
  - Status Bar Widget: dynamically shows branch count (e.g. `Multi-Branch (4)`) on the bottom status bar.
  - Bottom Git Branch Popup: Pinned at the top of the branch menu.
  - Bottom Git / Local Changes Tool Window: Action button on the toolbar and in the context menu.
  - Keyboard Shortcut: `Ctrl+Alt+M`.
  - Settings: `File | Settings | Version Control | Multi-Branch Workflow`.

## Configuring GitLab API Token

To allow the plugin to automatically create Merge Requests, assign them to you, and set options (Delete source branch, Squash commits), follow these steps:

### 1. Generate a Personal Access Token in GitLab

1. Log in to your GitLab instance (e.g. `https://gitlab.com` or your company's self-hosted GitLab).
2. Click your **User Avatar** (top-right or left sidebar) and select **Preferences** or **Edit profile**.
3. In the left navigation menu, click **Access Tokens** (or **Personal access tokens**).
   - Direct URL path: `https://<your-gitlab-host>/-/user_settings/personal_access_tokens` (GitLab 16+) or `https://<your-gitlab-host>/-/profile/personal_access_tokens`.
4. Click **Add new token**:
   - **Token name**: Enter an identifiable name, e.g. `git-multibranch-plugin`.
   - **Expiration date**: Set according to your organization's policy.
   - **Select scopes**: Check the **`api`** scope (required to create Merge Requests via `/api/v4/projects/:id/merge_requests` and fetch user info from `/api/v4/user`).
5. Click **Create personal access token**.
6. **Copy the generated token** (it begins with `glpat-...`). *GitLab will only display this token once.*

### 2. Configure the Token in IntelliJ IDEA / WebStorm

1. In your IDE, open Settings:
   - Windows/Linux: `File | Settings` (`Ctrl+Alt+S`)
   - macOS: `Preferences / Settings` (`Cmd+,`)
2. Navigate to **Version Control** -> **Multi-Branch Workflow**.
3. Under the **GitLab API & Merge Request Settings** section:
   - Check **"Create Merge Requests automatically via GitLab API after push"**.
   - Notice the token label automatically displays your active GitLab host (e.g. `GitLab Personal Access Token (gitlab.company.com):`).
   - Paste your copied token into the **GitLab Personal Access Token** field.
     > **Per-Host Storage**: The token is stored securely using IntelliJ IDEA's native `PasswordSafe` (Credential Store / OS Keychain) keyed to the detected GitLab host. If you work across multiple GitLab servers, each server keeps its own independent token. Projects on the same GitLab host share the token automatically.
   - *(Optional)* **GitLab Host (optional override)**: The text field placeholder indicates the auto-detected origin host (e.g. `Auto-detected: https://gitlab.company.com`). You only need to enter a host override if your Git remote URL differs from the web/API base URL (e.g. internal SSH host alias). Switching hosts will automatically switch to the saved token for that target host.
   - Configure MR behavior:
     - **Assign Merge Request to authenticated user (login person)**: Automatically assigns the created MR to your account.
     - **Delete source branch when merge request is accepted**: Sets `remove_source_branch = true`.
     - **Squash commits when merge request is accepted**: Sets `squash = true`.
     - **Merge MR automatically after creation via GitLab API (Merge MR)**: Automatically accepts/merges created MRs for branches with "Allow merge" enabled.
4. Click the **Test Connection** button:
   - A green confirmation message will display: `Connection successful! Connected as: Full Name (@username)`.
5. Under **Default Commit & Push Options**:
   - Ensure **"Open created MRs automatically in browser"** is checked if you want your browser to open created MRs (note: automatically suppressed on successful merge when Merge MR is enabled).
6. Click **Apply** and **OK**.

## Building the Plugin

```bash
./gradlew buildPlugin
```

Built artifacts will be located in:
- `build/distributions/git-multibranch-plugin-<version>.zip`
- `build/libs/git-multibranch-plugin-<version>.jar`

## CI/CD Harness

Includes GitHub Actions workflow (`.github/workflows/ci.yml`) that:
1. Builds the plugin when pushing a version tag (e.g. `v1.1.0`).
2. Uploads the distribution ZIP artifact.
3. Automatically creates a GitHub Release with the plugin ZIP and JAR assets.
