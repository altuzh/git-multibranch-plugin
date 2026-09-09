package git.multibranch;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vcs.VcsConfiguration;
import git4idea.GitRemoteBranch;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MultiBranchCommitDialog extends DialogWrapper {
    private final Project project;
    private final MultiBranchConfig config;
    private final MultiBranchCommitAction.BranchDetectionResult detection;

    private JBTextField prefixField;
    private JBTextArea messageArea;
    private JBCheckBox prefixMessageCheckbox;
    private ComboBox<LocalChangeList> changelistCombo;
    private JLabel changelistSummaryLabel;

    private JPanel branchesRowsPanel;
    private final List<JCheckBox> mappingCheckboxes = new ArrayList<>();
    private final List<JCheckBox> allowMergeCheckboxes = new ArrayList<>();
    private final List<JLabel> previewLabels = new ArrayList<>();

    private JBCheckBox fetchCheckbox;
    private JBCheckBox pushCheckbox;
    private JBCheckBox mrLinksCheckbox;
    private JBCheckBox openMrCheckbox;
    private JBCheckBox mergeMrCheckbox;
    private JBCheckBox stashCheckbox;
    private JBCheckBox checkoutTestCheckbox;

    public MultiBranchCommitDialog(@Nullable Project project, MultiBranchConfig initialConfig, String defaultPrefix) {
        this(project, initialConfig, defaultPrefix, null);
    }

    public MultiBranchCommitDialog(@Nullable Project project, MultiBranchConfig initialConfig, String defaultPrefix, @Nullable MultiBranchCommitAction.BranchDetectionResult detection) {
        super(project, true);
        this.project = project;
        this.config = initialConfig;
        this.detection = detection;
        if (defaultPrefix != null && !defaultPrefix.isBlank()) {
            config.setTaskPrefix(defaultPrefix);
        }
        setTitle("Multi-Branch Commit & Push");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;

        // 1. Task Prefix
        JPanel prefixPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        prefixPanel.add(new JBLabel("Task / Branch Prefix:"), BorderLayout.WEST);
        prefixField = new JBTextField(config.getTaskPrefix(), 20);
        prefixPanel.add(prefixField, BorderLayout.CENTER);
        root.add(prefixPanel, gbc);

        // 2. Commit Message
        gbc.gridy++;
        JPanel msgPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel msgHeader = new JPanel(new BorderLayout());
        msgHeader.add(new JBLabel("Commit Message:"), BorderLayout.WEST);

        JButton historyBtn = new JButton("Recent Messages (Ctrl+M)...");
        historyBtn.setFocusable(false);
        historyBtn.setToolTipText("Reuse recent commit messages from IDEA history");
        historyBtn.addActionListener(e -> showRecentMessagesPopup(historyBtn));
        msgHeader.add(historyBtn, BorderLayout.EAST);
        msgPanel.add(msgHeader, BorderLayout.NORTH);

        messageArea = new JBTextArea(config.getCommitMessage(), 3, 50);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);

        // Register Ctrl+M on messageArea
        messageArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK), "showRecentMessages");
        messageArea.getActionMap().put("showRecentMessages", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showRecentMessagesPopup(historyBtn);
            }
        });

        msgPanel.add(new JBScrollPane(messageArea), BorderLayout.CENTER);

        prefixMessageCheckbox = new JBCheckBox("Prepend prefix as [PREFIX] to commit message", config.isPrefixMessageWithTask());
        msgPanel.add(prefixMessageCheckbox, BorderLayout.SOUTH);
        root.add(msgPanel, gbc);

        // 3. Changelist selection
        gbc.gridy++;
        JPanel clPanel = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(2)));
        clPanel.add(new JBLabel("Local Changes folder:"), BorderLayout.WEST);

        ChangeListManager clm = project != null ? ChangeListManager.getInstance(project) : null;
        List<LocalChangeList> changeLists = clm != null ? clm.getChangeLists() : List.of();
        changelistCombo = new ComboBox<>(changeLists.toArray(new LocalChangeList[0]));
        changelistCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof LocalChangeList cl) {
                    setText(cl.getName() + " (" + cl.getChanges().size() + " files)");
                }
                return this;
            }
        });

        LocalChangeList selectedList = null;
        String desiredCl = config.getChangelistName();
        for (LocalChangeList cl : changeLists) {
            if (desiredCl != null && desiredCl.equalsIgnoreCase(cl.getName())) {
                selectedList = cl;
                break;
            }
        }
        if (selectedList == null) {
            for (LocalChangeList cl : changeLists) {
                if ("Changes".equalsIgnoreCase(cl.getName())) {
                    selectedList = cl;
                    break;
                }
            }
        }
        if (selectedList == null && !changeLists.isEmpty()) {
            selectedList = changeLists.get(0);
        }
        if (selectedList != null) {
            changelistCombo.setSelectedItem(selectedList);
        }
        clPanel.add(changelistCombo, BorderLayout.CENTER);

        changelistSummaryLabel = new JLabel();
        updateChangelistSummary();
        changelistCombo.addActionListener(e -> updateChangelistSummary());
        clPanel.add(changelistSummaryLabel, BorderLayout.SOUTH);
        root.add(clPanel, gbc);

        // 4. Target Branches Mapping Section with Configure button
        gbc.gridy++;
        JPanel branchesBox = new JPanel();
        branchesBox.setLayout(new BoxLayout(branchesBox, BoxLayout.Y_AXIS));
        branchesBox.setBorder(BorderFactory.createTitledBorder("Target Branches"));

        JPanel headerPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        headerPanel.add(new JBLabel("Selected branches for this commit:"), BorderLayout.WEST);

        JButton configureBtn = new JButton("Configure Branches...");
        configureBtn.setFocusable(false);
        configureBtn.setToolTipText("Add, edit, remove, or reorder target branch mappings");
        configureBtn.addActionListener(e -> openConfigureBranchesDialog());
        headerPanel.add(configureBtn, BorderLayout.EAST);
        branchesBox.add(headerPanel);

        if (detection != null && detection.isOnDesignatedBranch()) {
            JPanel infoBanner = new JPanel(new BorderLayout(JBUI.scale(6), 0));
            infoBanner.setBorder(JBUI.Borders.empty(4, 2, 6, 2));
            JLabel infoLabel = new JLabel("● You are currently on designated branch: " + detection.getCurrentBranch());
            infoLabel.setFont(infoLabel.getFont().deriveFont(Font.BOLD));
            infoLabel.setForeground(JBUI.CurrentTheme.Link.Foreground.ENABLED);
            infoBanner.add(infoLabel, BorderLayout.WEST);
            branchesBox.add(infoBanner);
        }

        branchesBox.add(Box.createVerticalStrut(JBUI.scale(4)));

        branchesRowsPanel = new JPanel();
        branchesRowsPanel.setLayout(new BoxLayout(branchesRowsPanel, BoxLayout.Y_AXIS));
        branchesBox.add(branchesRowsPanel);

        rebuildBranchRows();
        root.add(branchesBox, gbc);

        // 5. Options
        gbc.gridy++;
        JPanel optionsBox = new JPanel(new GridLayout(4, 2, JBUI.scale(8), JBUI.scale(4)));
        optionsBox.setBorder(BorderFactory.createTitledBorder("Options"));

        fetchCheckbox = new JBCheckBox("Fetch origin first", config.isFetchOriginFirst());
        pushCheckbox = new JBCheckBox("Push branches to origin", config.isPushAfterCommit());
        mrLinksCheckbox = new JBCheckBox("Generate MR links", config.isGenerateMrLinks());
        openMrCheckbox = new JBCheckBox("Open created MRs in browser", config.isOpenMrLinksInBrowser());
        mergeMrCheckbox = new JBCheckBox("Merge MR (auto-merge after creation)", config.isGitLabMergeMr());
        mergeMrCheckbox.setToolTipText("Automatically merge created MRs via GitLab API if allowed for the target branch");

        stashCheckbox = new JBCheckBox("Stash other folders & pop after", config.isStashOtherChanges());
        stashCheckbox.setToolTipText("If uncommitted changes exist in other folders, stash before commit and pop after checkout " + config.getCheckoutBranch());

        checkoutTestCheckbox = new JBCheckBox("Checkout " + config.getCheckoutBranch() + " on finish", config.isCheckoutTestAfter());
        checkoutTestCheckbox.setToolTipText("Checkout " + config.getCheckoutBranch() + " after commits/pushes are complete");

        mrLinksCheckbox.setEnabled(pushCheckbox.isSelected());
        openMrCheckbox.setEnabled(pushCheckbox.isSelected());
        mergeMrCheckbox.setEnabled(pushCheckbox.isSelected());

        pushCheckbox.addActionListener(e -> {
            boolean p = pushCheckbox.isSelected();
            mrLinksCheckbox.setEnabled(p);
            openMrCheckbox.setEnabled(p);
            mergeMrCheckbox.setEnabled(p);
        });

        optionsBox.add(fetchCheckbox);
        optionsBox.add(pushCheckbox);
        optionsBox.add(mrLinksCheckbox);
        optionsBox.add(openMrCheckbox);
        optionsBox.add(mergeMrCheckbox);
        optionsBox.add(stashCheckbox);
        optionsBox.add(checkoutTestCheckbox);
        root.add(optionsBox, gbc);

        // Dynamic prefix update
        prefixField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateBranchPreviews(); }
            public void removeUpdate(DocumentEvent e) { updateBranchPreviews(); }
            public void changedUpdate(DocumentEvent e) { updateBranchPreviews(); }
        });

        root.setPreferredSize(new Dimension(JBUI.scale(660), JBUI.scale(500)));
        return root;
    }

    private void openConfigureBranchesDialog() {
        ConfigureBranchesDialog dlg = new ConfigureBranchesDialog(project);
        if (dlg.showAndGet()) {
            MultiBranchSettings settings = MultiBranchSettings.getInstance(project);
            if (settings != null) {
                config.getBranchMappings().clear();
                for (BranchMapping m : settings.getState().branchMappings) {
                    config.getBranchMappings().add(m.copy());
                }
                config.setCheckoutBranch(settings.getState().checkoutBranch);
                config.setCheckoutTestAfter(settings.getState().checkoutTestAfter);
                config.setGitLabCreateMr(settings.getState().gitLabCreateMr);
                config.setGitLabAssignToMe(settings.getState().gitLabAssignToMe);
                config.setGitLabDeleteSourceBranch(settings.getState().gitLabDeleteSourceBranch);
                config.setGitLabSquashCommits(settings.getState().gitLabSquashCommits);
                config.setGitLabMergeMr(settings.getState().gitLabMergeMr);
                config.setGitLabHost(settings.getState().gitLabHost);
                String detectedHost = GitLabTokenManager.detectHost(project, settings.getState().gitLabHost);
                String token = GitLabTokenManager.getToken(detectedHost);
                if (token != null && !token.isBlank()) {
                    config.setGitLabApiToken(token);
                } else {
                    config.setGitLabApiToken(settings.getState().gitLabApiToken);
                }
                if (mergeMrCheckbox != null) {
                    mergeMrCheckbox.setSelected(config.isGitLabMergeMr());
                }
                if (checkoutTestCheckbox != null) {
                    checkoutTestCheckbox.setText("Checkout " + config.getCheckoutBranch() + " on finish");
                    checkoutTestCheckbox.setSelected(config.isCheckoutTestAfter());
                }
                rebuildBranchRows();
                updateBranchPreviews();
                if (getContentPanel() != null) {
                    getContentPanel().revalidate();
                    getContentPanel().repaint();
                }
            }
        }
    }

    private void rebuildBranchRows() {
        branchesRowsPanel.removeAll();
        mappingCheckboxes.clear();
        allowMergeCheckboxes.clear();
        previewLabels.clear();

        String currentPrefix = (prefixField != null) ? prefixField.getText().trim() : config.getTaskPrefix();
        String curBranch = (detection != null) ? detection.getCurrentBranch() : "";

        for (BranchMapping mapping : config.getBranchMappings()) {
            JPanel row = new JPanel(new BorderLayout(JBUI.scale(8), 0));
            JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));

            JCheckBox cb = new JCheckBox("From " + mapping.getSourceOriginBranch(), mapping.isEnabled());
            JCheckBox allowMergeCb = new JCheckBox("Allow merge", mapping.isAllowMerge());
            allowMergeCb.setToolTipText("If checked and 'Merge MR' is active, created MR for this branch will be merged automatically");
            allowMergeCb.setEnabled(cb.isSelected());

            cb.addActionListener(e -> {
                mapping.setEnabled(cb.isSelected());
                allowMergeCb.setEnabled(cb.isSelected());
            });
            allowMergeCb.addActionListener(e -> mapping.setAllowMerge(allowMergeCb.isSelected()));

            leftPanel.add(cb);
            leftPanel.add(allowMergeCb);

            String localBranch = mapping.getLocalBranchName(currentPrefix);
            boolean isCurrent = !curBranch.isEmpty() && curBranch.equalsIgnoreCase(localBranch);
            boolean onRemote = isRemoteBranchPresent(localBranch);

            String previewText = "➔ " + localBranch + " (Merge target: " + mapping.getTargetOriginBranchName() + ")";
            if (isCurrent) {
                previewText += "  [CURRENT BRANCH]";
            }
            if (onRemote) {
                previewText += "  [ON REMOTE]";
            }
            JLabel preview = new JLabel(previewText);
            if (isCurrent) {
                preview.setFont(preview.getFont().deriveFont(Font.BOLD));
                preview.setForeground(JBUI.CurrentTheme.Label.foreground());
            } else {
                preview.setFont(preview.getFont().deriveFont(Font.PLAIN));
                preview.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
            }

            mappingCheckboxes.add(cb);
            allowMergeCheckboxes.add(allowMergeCb);
            previewLabels.add(preview);

            row.add(leftPanel, BorderLayout.WEST);
            row.add(preview, BorderLayout.CENTER);
            branchesRowsPanel.add(row);
        }
        branchesRowsPanel.revalidate();
        branchesRowsPanel.repaint();
    }

    private void showRecentMessagesPopup(Component anchor) {
        if (project == null) return;
        List<String> messages = VcsConfiguration.getInstance(project).getRecentMessages();
        if (messages == null || messages.isEmpty()) {
            Messages.showInfoMessage(project, "No recent commit messages found in history.", "Commit Message History");
            return;
        }

        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(messages)
                .setTitle("Recent Commit Messages")
                .setItemChosenCallback(selected -> {
                    if (selected != null && !selected.isBlank()) {
                        String cleanMsg = selected;
                        String p = prefixField.getText().trim();
                        if (!p.isEmpty() && cleanMsg.startsWith("[" + p + "] ")) {
                            cleanMsg = cleanMsg.substring(("[" + p + "] ").length());
                        }
                        messageArea.setText(cleanMsg);
                    }
                })
                .createPopup()
                .showUnderneathOf(anchor);
    }

    private void updateBranchPreviews() {
        String p = prefixField != null ? prefixField.getText().trim() : "";
        String curBranch = (detection != null) ? detection.getCurrentBranch() : "";
        List<BranchMapping> mappings = config.getBranchMappings();
        for (int i = 0; i < mappings.size() && i < previewLabels.size(); i++) {
            BranchMapping m = mappings.get(i);
            String localBranch = m.getLocalBranchName(p);
            boolean isCurrent = !curBranch.isEmpty() && curBranch.equalsIgnoreCase(localBranch);
            boolean onRemote = isRemoteBranchPresent(localBranch);

            String previewText = "➔ " + localBranch + " (Merge target: " + m.getTargetOriginBranchName() + ")";
            if (isCurrent) {
                previewText += "  [CURRENT BRANCH]";
            }
            if (onRemote) {
                previewText += "  [ON REMOTE]";
            }
            if (isCurrent) {
                previewLabels.get(i).setFont(previewLabels.get(i).getFont().deriveFont(Font.BOLD));
                previewLabels.get(i).setForeground(JBUI.CurrentTheme.Label.foreground());
            } else {
                previewLabels.get(i).setFont(previewLabels.get(i).getFont().deriveFont(Font.PLAIN));
                previewLabels.get(i).setForeground(JBUI.CurrentTheme.Label.disabledForeground());
            }
            previewLabels.get(i).setText(previewText);
        }
    }

    private boolean isRemoteBranchPresent(String branchName) {
        if (project == null || branchName == null || branchName.isBlank()) return false;
        try {
            Collection<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
            if (!repos.isEmpty()) {
                GitRepository repo = repos.iterator().next();
                for (GitRemoteBranch rb : repo.getBranches().getRemoteBranches()) {
                    if (rb.getNameForRemoteOperations().equalsIgnoreCase(branchName)
                            || rb.getName().equalsIgnoreCase("origin/" + branchName)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void updateChangelistSummary() {
        LocalChangeList cl = (LocalChangeList) changelistCombo.getSelectedItem();
        if (cl != null) {
            Collection<Change> changes = cl.getChanges();
            changelistSummaryLabel.setText(changes.size() + " file(s) will be committed. Other changelists are ignored.");
        } else {
            changelistSummaryLabel.setText("No changelist selected.");
        }
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (prefixField.getText().trim().isEmpty()) {
            return new ValidationInfo("Task / Branch Prefix cannot be empty", prefixField);
        }
        if (messageArea.getText().trim().isEmpty()) {
            return new ValidationInfo("Commit message cannot be empty", messageArea);
        }
        boolean anyBranch = false;
        for (JCheckBox cb : mappingCheckboxes) {
            if (cb.isSelected()) {
                anyBranch = true;
                break;
            }
        }
        if (!anyBranch) {
            return new ValidationInfo("At least one target branch must be selected");
        }
        LocalChangeList cl = (LocalChangeList) changelistCombo.getSelectedItem();
        if (cl == null || cl.getChanges().isEmpty()) {
            return new ValidationInfo("Selected changelist has no changes to commit", changelistCombo);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        config.setTaskPrefix(prefixField.getText().trim());
        config.setCommitMessage(messageArea.getText().trim());
        config.setPrefixMessageWithTask(prefixMessageCheckbox.isSelected());
        LocalChangeList cl = (LocalChangeList) changelistCombo.getSelectedItem();
        if (cl != null) {
            config.setChangelistName(cl.getName());
        }
        for (int i = 0; i < config.getBranchMappings().size(); i++) {
            if (i < mappingCheckboxes.size()) {
                config.getBranchMappings().get(i).setEnabled(mappingCheckboxes.get(i).isSelected());
            }
            if (i < allowMergeCheckboxes.size()) {
                config.getBranchMappings().get(i).setAllowMerge(allowMergeCheckboxes.get(i).isSelected());
            }
        }
        config.setFetchOriginFirst(fetchCheckbox.isSelected());
        config.setPushAfterCommit(pushCheckbox.isSelected());
        config.setGenerateMrLinks(mrLinksCheckbox.isSelected());
        config.setOpenMrLinksInBrowser(openMrCheckbox.isSelected());
        config.setGitLabMergeMr(mergeMrCheckbox.isSelected());
        config.setStashOtherChanges(stashCheckbox.isSelected());
        config.setCheckoutTestAfter(checkoutTestCheckbox.isSelected());

        if (project != null) {
            VcsConfiguration.getInstance(project).saveCommitMessage(config.getCommitMessage());
            VcsConfiguration.getInstance(project).saveCommitMessage(config.getFormattedCommitMessage());
        }

        super.doOKAction();
    }

    public MultiBranchConfig getConfig() {
        return config;
    }
}
