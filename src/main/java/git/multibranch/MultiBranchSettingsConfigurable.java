package git.multibranch;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Objects;

public class MultiBranchSettingsConfigurable implements SearchableConfigurable {
    public static final String ID = "git.multibranch.MultiBranchSettingsConfigurable";
    private final Project project;

    private JPanel rootPanel;
    private BranchMappingsTablePanel tablePanel;
    private JBTextField checkoutBranchField;
    private JBTextField changelistField;
    private JBCheckBox fetchOriginCheckbox;
    private JBCheckBox pushAfterCommitCheckbox;
    private JBCheckBox premergeTargetBranchCheckbox;
    private JBCheckBox generateMrLinksCheckbox;
    private JBCheckBox openMrLinksCheckbox;
    private JBCheckBox stashOtherChangesCheckbox;
    private JBCheckBox checkoutTestCheckbox;
    private JBCheckBox prefixMessageCheckbox;

    // GitLab API Configuration
    private JBCheckBox gitLabCreateMrCheckbox;
    private JBLabel gitLabTokenLabel;
    private JPasswordField gitLabTokenField;
    private JButton testConnectionBtn;
    private JLabel testStatusLabel;
    private JBCheckBox gitLabAssignToMeCheckbox;
    private JBCheckBox gitLabDeleteSourceBranchCheckbox;
    private JBCheckBox gitLabSquashCommitsCheckbox;
    private JBTextField gitLabHostField;
    private String currentLoadedHost = "";

    public MultiBranchSettingsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Multi-Branch Workflow";
    }

    @Override
    public @Nullable JComponent createComponent() {
        rootPanel = new JPanel(new BorderLayout(0, JBUI.scale(12)));

        // 1. Branch Mappings section
        JPanel branchSection = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        branchSection.setBorder(BorderFactory.createTitledBorder("Target Branch Mappings"));
        tablePanel = new BranchMappingsTablePanel(project);
        branchSection.add(tablePanel, BorderLayout.CENTER);
        rootPanel.add(branchSection, BorderLayout.CENTER);

        // 2. Bottom Container for Settings Panels
        JPanel bottomContainer = new JPanel();
        bottomContainer.setLayout(new BoxLayout(bottomContainer, BoxLayout.Y_AXIS));

        // GitLab API Settings
        JPanel gitLabPanel = new JPanel(new GridBagLayout());
        gitLabPanel.setBorder(BorderFactory.createTitledBorder("GitLab API & Merge Request Settings"));
        GridBagConstraints glGbc = new GridBagConstraints();
        glGbc.insets = JBUI.insets(4);
        glGbc.fill = GridBagConstraints.HORIZONTAL;
        glGbc.weightx = 1.0;
        glGbc.gridx = 0;
        glGbc.gridy = 0;

        gitLabCreateMrCheckbox = new JBCheckBox("Create Merge Requests automatically via GitLab API after push");
        gitLabPanel.add(gitLabCreateMrCheckbox, glGbc);

        glGbc.gridy++;
        JPanel tokenRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        gitLabTokenLabel = new JBLabel("GitLab Personal Access Token:");
        tokenRow.add(gitLabTokenLabel, BorderLayout.WEST);
        gitLabTokenField = new JPasswordField();
        tokenRow.add(gitLabTokenField, BorderLayout.CENTER);

        testConnectionBtn = new JButton("Test Connection");
        testConnectionBtn.addActionListener(e -> runTestConnection());
        tokenRow.add(testConnectionBtn, BorderLayout.EAST);
        gitLabPanel.add(tokenRow, glGbc);

        glGbc.gridy++;
        testStatusLabel = new JLabel();
        testStatusLabel.setFont(testStatusLabel.getFont().deriveFont(Font.ITALIC));
        gitLabPanel.add(testStatusLabel, glGbc);

        glGbc.gridy++;
        JPanel glOptionsPanel = new JPanel(new GridLayout(3, 1, 0, JBUI.scale(2)));
        gitLabAssignToMeCheckbox = new JBCheckBox("Assign Merge Request to authenticated user (login person)");
        gitLabDeleteSourceBranchCheckbox = new JBCheckBox("Delete source branch when merge request is accepted");
        gitLabSquashCommitsCheckbox = new JBCheckBox("Squash commits when merge request is accepted");

        glOptionsPanel.add(gitLabAssignToMeCheckbox);
        glOptionsPanel.add(gitLabDeleteSourceBranchCheckbox);
        glOptionsPanel.add(gitLabSquashCommitsCheckbox);
        gitLabPanel.add(glOptionsPanel, glGbc);

        glGbc.gridy++;
        JPanel hostRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        hostRow.add(new JBLabel("GitLab Host (optional override):"), BorderLayout.WEST);
        gitLabHostField = new JBTextField();
        gitLabHostField.getEmptyText().setText("e.g. https://gitlab.example.com (auto-detected from origin remote if blank)");
        gitLabHostField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                updateHostUI(false);
            }
        });
        gitLabHostField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateHostLabelOnly(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateHostLabelOnly(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateHostLabelOnly(); }
        });
        hostRow.add(gitLabHostField, BorderLayout.CENTER);
        gitLabPanel.add(hostRow, glGbc);

        gitLabCreateMrCheckbox.addActionListener(e -> {
            boolean enabled = gitLabCreateMrCheckbox.isSelected();
            gitLabTokenField.setEnabled(enabled);
            testConnectionBtn.setEnabled(enabled);
            gitLabAssignToMeCheckbox.setEnabled(enabled);
            gitLabDeleteSourceBranchCheckbox.setEnabled(enabled);
            gitLabSquashCommitsCheckbox.setEnabled(enabled);
            gitLabHostField.setEnabled(enabled);
        });

        bottomContainer.add(gitLabPanel);
        bottomContainer.add(Box.createVerticalStrut(JBUI.scale(8)));

        // Default Commit & Push Options
        JPanel optionsPanel = new JPanel(new GridBagLayout());
        optionsPanel.setBorder(BorderFactory.createTitledBorder("Default Commit & Push Options"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;

        JPanel fieldsPanel = new JPanel(new GridLayout(2, 2, JBUI.scale(12), JBUI.scale(4)));
        fieldsPanel.add(new JBLabel("Default Changelist Name:"));
        changelistField = new JBTextField("Changes");
        fieldsPanel.add(changelistField);

        fieldsPanel.add(new JBLabel("Default Post-Action Checkout Branch:"));
        checkoutBranchField = new JBTextField("deploy/test");
        fieldsPanel.add(checkoutBranchField);
        optionsPanel.add(fieldsPanel, gbc);

        gbc.gridy++;
        JPanel checkPanel = new JPanel(new GridLayout(4, 2, JBUI.scale(12), JBUI.scale(2)));
        fetchOriginCheckbox = new JBCheckBox("Fetch origin before operations");
        pushAfterCommitCheckbox = new JBCheckBox("Push branches to origin by default");
        premergeTargetBranchCheckbox = new JBCheckBox("Premerge target branch if it exists");
        premergeTargetBranchCheckbox.setToolTipText("If branch exists, target branch will be premerged first before committing changes");
        generateMrLinksCheckbox = new JBCheckBox("Generate MR links by default");
        openMrLinksCheckbox = new JBCheckBox("Open created MRs automatically in browser");
        stashOtherChangesCheckbox = new JBCheckBox("Stash uncommitted changes in other folders (restore to 'Uncommitted changes')");
        stashOtherChangesCheckbox.setToolTipText("If uncommitted changes exist in other folders, stash before commit and restore to 'Uncommitted changes' changelist");
        checkoutTestCheckbox = new JBCheckBox("Checkout target branch on finish");
        prefixMessageCheckbox = new JBCheckBox("Prepend task prefix [PREFIX] to commit message");

        pushAfterCommitCheckbox.addActionListener(e -> {
            generateMrLinksCheckbox.setEnabled(pushAfterCommitCheckbox.isSelected());
            openMrLinksCheckbox.setEnabled(pushAfterCommitCheckbox.isSelected());
        });

        checkoutTestCheckbox.addActionListener(e -> {
            checkoutBranchField.setEnabled(checkoutTestCheckbox.isSelected());
        });

        checkPanel.add(fetchOriginCheckbox);
        checkPanel.add(pushAfterCommitCheckbox);
        checkPanel.add(premergeTargetBranchCheckbox);
        checkPanel.add(generateMrLinksCheckbox);
        checkPanel.add(openMrLinksCheckbox);
        checkPanel.add(stashOtherChangesCheckbox);
        checkPanel.add(checkoutTestCheckbox);
        checkPanel.add(prefixMessageCheckbox);
        optionsPanel.add(checkPanel, gbc);

        bottomContainer.add(optionsPanel);

        // Diagnostics & Logs section
        JPanel logsPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        logsPanel.setBorder(BorderFactory.createTitledBorder("Plugin Logs & Diagnostics"));

        JPanel logInfoPanel = new JPanel(new GridLayout(2, 1, 0, JBUI.scale(2)));
        JBLabel logPathLabel = new JBLabel("Log folder: " + MultiBranchLog.getLogDirectory().getAbsolutePath());
        logPathLabel.setCopyable(true);
        logInfoPanel.add(logPathLabel);

        JLabel logDescLabel = new JLabel("Stores operational and diagnostic logs for git operations, branch creation, commits, pushes, and GitLab API requests.");
        logDescLabel.setFont(logDescLabel.getFont().deriveFont(Font.ITALIC, JBUI.scaleFontSize(11)));
        logDescLabel.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        logInfoPanel.add(logDescLabel);

        logsPanel.add(logInfoPanel, BorderLayout.CENTER);

        JPanel logActionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        JButton openLogFolderBtn = new JButton("Open Log Folder");
        openLogFolderBtn.setToolTipText("Open the plugin log folder in system file explorer");
        openLogFolderBtn.addActionListener(e -> {
            MultiBranchLog.info("Settings: Opening log directory from Settings UI...");
            boolean opened = MultiBranchLog.openLogDirectory();
            if (!opened) {
                Messages.showInfoMessage(
                        project,
                        "Log directory: " + MultiBranchLog.getLogDirectory().getAbsolutePath(),
                        "Multi-Branch Log Folder"
                );
            }
        });
        logActionsPanel.add(openLogFolderBtn);
        logsPanel.add(logActionsPanel, BorderLayout.EAST);

        bottomContainer.add(Box.createVerticalStrut(JBUI.scale(8)));
        bottomContainer.add(logsPanel);

        rootPanel.add(bottomContainer, BorderLayout.SOUTH);

        return rootPanel;
    }

    private void updateHostLabelOnly() {
        if (gitLabTokenLabel == null || gitLabHostField == null) return;
        String rawHost = gitLabHostField.getText().trim();
        String effectiveHost = GitLabTokenManager.detectHost(project, rawHost);
        String normalized = GitLabTokenManager.normalizeHost(effectiveHost);
        if (!normalized.isEmpty()) {
            gitLabTokenLabel.setText("GitLab Personal Access Token (" + normalized + "):");
        } else {
            gitLabTokenLabel.setText("GitLab Personal Access Token:");
        }
    }

    private void updateHostUI(boolean forceReloadToken) {
        updateHostLabelOnly();
        if (gitLabHostField == null || gitLabTokenField == null) return;
        String rawHost = gitLabHostField.getText().trim();
        String effectiveHost = GitLabTokenManager.detectHost(project, rawHost);

        String autoDetected = GitLabTokenManager.detectHost(project, null);
        if (!autoDetected.isBlank()) {
            gitLabHostField.getEmptyText().setText("Auto-detected: " + autoDetected + " (leave blank to use, or enter override)");
        } else {
            gitLabHostField.getEmptyText().setText("e.g. https://gitlab.example.com (auto-detected from origin remote if blank)");
        }

        if (forceReloadToken || !Objects.equals(effectiveHost, currentLoadedHost)) {
            String curPassword = new String(gitLabTokenField.getPassword()).trim();
            String oldSaved = GitLabTokenManager.getToken(currentLoadedHost);
            if (forceReloadToken || curPassword.isEmpty() || Objects.equals(curPassword, oldSaved != null ? oldSaved : "")) {
                String newToken = GitLabTokenManager.getToken(effectiveHost);
                gitLabTokenField.setText(newToken != null ? newToken : "");
            }
            currentLoadedHost = effectiveHost;
        }
    }

    private void runTestConnection() {
        String host = gitLabHostField.getText().trim();
        String effectiveHost = GitLabTokenManager.detectHost(project, host);
        String token = new String(gitLabTokenField.getPassword()).trim();
        if (token.isBlank()) {
            token = GitLabTokenManager.getToken(effectiveHost);
        }
        if (token == null || token.isBlank()) {
            testStatusLabel.setText("Error: GitLab API Token is empty.");
            testStatusLabel.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
            return;
        }
        testStatusLabel.setText("Testing connection...");
        testStatusLabel.setForeground(JBUI.CurrentTheme.Label.foreground());

        final String finalToken = token;
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                String apiUrl = null;
                if (!host.isBlank()) {
                    String h = host;
                    if (h.endsWith("/")) h = h.substring(0, h.length() - 1);
                    if (!h.startsWith("http://") && !h.startsWith("https://")) {
                        h = "https://" + h;
                    }
                    apiUrl = h + "/api/v4";
                } else {
                    String detected = GitLabTokenManager.detectHost(project, null);
                    if (!detected.isBlank()) {
                        if (!detected.startsWith("http://") && !detected.startsWith("https://")) {
                            detected = "https://" + detected;
                        }
                        apiUrl = detected + "/api/v4";
                    }
                }
                if (apiUrl == null) {
                    return "Error: Cannot determine GitLab API URL. Please specify GitLab Host.";
                }
                return GitLabApiService.testConnection(apiUrl, finalToken);
            }

            @Override
            protected void done() {
                try {
                    String res = get();
                    testStatusLabel.setText(res);
                    if (res.startsWith("Connection successful")) {
                        testStatusLabel.setForeground(new Color(0x388E3C));
                    } else {
                        testStatusLabel.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
                    }
                } catch (Exception ex) {
                    testStatusLabel.setText("Error: " + ex.getMessage());
                    testStatusLabel.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
                }
            }
        };
        worker.execute();
    }

    @Override
    public boolean isModified() {
        MultiBranchSettings settings = MultiBranchSettings.getInstance(project);
        if (settings == null) return false;
        MultiBranchSettings.State state = settings.getState();

        if (tablePanel.isModified(state.branchMappings)) return true;
        if (fetchOriginCheckbox.isSelected() != state.fetchOriginFirst) return true;
        if (pushAfterCommitCheckbox.isSelected() != state.pushAfterCommit) return true;
        if (premergeTargetBranchCheckbox.isSelected() != state.premergeTargetBranch) return true;
        if (generateMrLinksCheckbox.isSelected() != state.generateMrLinks) return true;
        if (openMrLinksCheckbox.isSelected() != state.openMrLinksInBrowser) return true;
        if (stashOtherChangesCheckbox.isSelected() != state.stashOtherChanges) return true;
        if (checkoutTestCheckbox.isSelected() != state.checkoutTestAfter) return true;
        if (prefixMessageCheckbox.isSelected() != state.prefixMessageWithTask) return true;
        if (!Objects.equals(checkoutBranchField.getText().trim(), state.checkoutBranch)) return true;
        if (!Objects.equals(changelistField.getText().trim(), state.defaultChangelistName)) return true;

        if (gitLabCreateMrCheckbox.isSelected() != state.gitLabCreateMr) return true;
        if (gitLabAssignToMeCheckbox.isSelected() != state.gitLabAssignToMe) return true;
        if (gitLabDeleteSourceBranchCheckbox.isSelected() != state.gitLabDeleteSourceBranch) return true;
        if (gitLabSquashCommitsCheckbox.isSelected() != state.gitLabSquashCommits) return true;
        if (!Objects.equals(gitLabHostField.getText().trim(), state.gitLabHost != null ? state.gitLabHost : "")) return true;

        String curToken = new String(gitLabTokenField.getPassword()).trim();
        String effectiveHost = GitLabTokenManager.detectHost(project, gitLabHostField.getText().trim());
        String savedToken = GitLabTokenManager.getToken(effectiveHost);
        if (savedToken == null || savedToken.isBlank()) {
            savedToken = state.gitLabApiToken != null ? state.gitLabApiToken : "";
        }
        return !Objects.equals(curToken, savedToken);
    }

    @Override
    public void apply() throws ConfigurationException {
        if (tablePanel.getMappings().isEmpty()) {
            throw new ConfigurationException("At least one target branch mapping must be configured.");
        }
        if (checkoutTestCheckbox.isSelected() && checkoutBranchField.getText().trim().isEmpty()) {
            throw new ConfigurationException("Post-action checkout branch cannot be empty when enabled.");
        }

        MultiBranchSettings settings = MultiBranchSettings.getInstance(project);
        if (settings != null) {
            MultiBranchSettings.State state = settings.getState();
            state.branchMappings.clear();
            for (BranchMapping m : tablePanel.getMappings()) {
                state.branchMappings.add(m.copy());
            }
            state.checkoutBranch = checkoutBranchField.getText().trim();
            state.defaultChangelistName = changelistField.getText().trim();
            state.fetchOriginFirst = fetchOriginCheckbox.isSelected();
            state.pushAfterCommit = pushAfterCommitCheckbox.isSelected();
            state.premergeTargetBranch = premergeTargetBranchCheckbox.isSelected();
            state.generateMrLinks = generateMrLinksCheckbox.isSelected();
            state.openMrLinksInBrowser = openMrLinksCheckbox.isSelected();
            state.stashOtherChanges = stashOtherChangesCheckbox.isSelected();
            state.checkoutTestAfter = checkoutTestCheckbox.isSelected();
            state.prefixMessageWithTask = prefixMessageCheckbox.isSelected();

            state.gitLabCreateMr = gitLabCreateMrCheckbox.isSelected();
            state.gitLabAssignToMe = gitLabAssignToMeCheckbox.isSelected();
            state.gitLabDeleteSourceBranch = gitLabDeleteSourceBranchCheckbox.isSelected();
            state.gitLabSquashCommits = gitLabSquashCommitsCheckbox.isSelected();
            state.gitLabHost = gitLabHostField.getText().trim();

            String effectiveHost = GitLabTokenManager.detectHost(project, state.gitLabHost);
            String token = new String(gitLabTokenField.getPassword()).trim();
            GitLabTokenManager.setToken(effectiveHost, token);
            state.gitLabApiToken = token;
            currentLoadedHost = effectiveHost;
        }
    }

    @Override
    public void reset() {
        MultiBranchSettings settings = MultiBranchSettings.getInstance(project);
        MultiBranchSettings.State state = (settings != null) ? settings.getState() : new MultiBranchSettings.State();

        tablePanel.setMappings(state.branchMappings);
        checkoutBranchField.setText(state.checkoutBranch != null ? state.checkoutBranch : "deploy/test");
        changelistField.setText(state.defaultChangelistName != null ? state.defaultChangelistName : "Changes");
        fetchOriginCheckbox.setSelected(state.fetchOriginFirst);
        pushAfterCommitCheckbox.setSelected(state.pushAfterCommit);
        premergeTargetBranchCheckbox.setSelected(state.premergeTargetBranch);
        generateMrLinksCheckbox.setSelected(state.generateMrLinks);
        openMrLinksCheckbox.setSelected(state.openMrLinksInBrowser);
        stashOtherChangesCheckbox.setSelected(state.stashOtherChanges);
        checkoutTestCheckbox.setSelected(state.checkoutTestAfter);
        prefixMessageCheckbox.setSelected(state.prefixMessageWithTask);

        gitLabCreateMrCheckbox.setSelected(state.gitLabCreateMr);
        gitLabAssignToMeCheckbox.setSelected(state.gitLabAssignToMe);
        gitLabDeleteSourceBranchCheckbox.setSelected(state.gitLabDeleteSourceBranch);
        gitLabSquashCommitsCheckbox.setSelected(state.gitLabSquashCommits);
        gitLabHostField.setText(state.gitLabHost != null ? state.gitLabHost : "");

        updateHostUI(true);
        String curToken = new String(gitLabTokenField.getPassword()).trim();
        if (curToken.isEmpty() && state.gitLabApiToken != null && !state.gitLabApiToken.isBlank()) {
            gitLabTokenField.setText(state.gitLabApiToken);
        }
        testStatusLabel.setText("");

        generateMrLinksCheckbox.setEnabled(state.pushAfterCommit);
        openMrLinksCheckbox.setEnabled(state.pushAfterCommit);
        checkoutBranchField.setEnabled(state.checkoutTestAfter);

        boolean glEnabled = state.gitLabCreateMr;
        gitLabTokenField.setEnabled(glEnabled);
        testConnectionBtn.setEnabled(glEnabled);
        gitLabAssignToMeCheckbox.setEnabled(glEnabled);
        gitLabDeleteSourceBranchCheckbox.setEnabled(glEnabled);
        gitLabSquashCommitsCheckbox.setEnabled(glEnabled);
        gitLabHostField.setEnabled(glEnabled);
    }

    @Override
    public void disposeUIResources() {
        rootPanel = null;
        tablePanel = null;
        checkoutBranchField = null;
        changelistField = null;
        fetchOriginCheckbox = null;
        pushAfterCommitCheckbox = null;
        generateMrLinksCheckbox = null;
        openMrLinksCheckbox = null;
        stashOtherChangesCheckbox = null;
        checkoutTestCheckbox = null;
        prefixMessageCheckbox = null;
        gitLabCreateMrCheckbox = null;
        gitLabTokenLabel = null;
        gitLabTokenField = null;
        testConnectionBtn = null;
        testStatusLabel = null;
        gitLabAssignToMeCheckbox = null;
        gitLabDeleteSourceBranchCheckbox = null;
        gitLabSquashCommitsCheckbox = null;
        gitLabHostField = null;
    }
}
