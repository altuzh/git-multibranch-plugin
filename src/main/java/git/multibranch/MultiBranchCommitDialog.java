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
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final List<JLabel> previewLabels = new ArrayList<>();

    private JBCheckBox fetchCheckbox;
    private JBCheckBox pushCheckbox;
    private JBCheckBox mrLinksCheckbox;
    private JBCheckBox openMrCheckbox;
    private JBCheckBox stashCheckbox;
    private JBCheckBox checkoutTestCheckbox;

    public static class InitialCommitInfo {
        private final String prefix;
        private final String commitMessage;

        public InitialCommitInfo(String prefix, String commitMessage) {
            this.prefix = prefix != null ? prefix : "";
            this.commitMessage = commitMessage != null ? commitMessage : "";
        }

        public String getPrefix() { return prefix; }
        public String getCommitMessage() { return commitMessage; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InitialCommitInfo that = (InitialCommitInfo) o;
            return Objects.equals(prefix, that.prefix) && Objects.equals(commitMessage, that.commitMessage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(prefix, commitMessage);
        }

        @Override
        public String toString() {
            return "InitialCommitInfo{prefix='" + prefix + "', commitMessage='" + commitMessage + "'}";
        }
    }

    public static class ParsedCommitMessage {
        private final String prefix;
        private final String message;

        public ParsedCommitMessage(String prefix, String message) {
            this.prefix = prefix != null ? prefix.trim() : "";
            this.message = message != null ? message.trim() : "";
        }

        public String getPrefix() { return prefix; }
        public String getMessage() { return message; }
    }

    public static ParsedCommitMessage extractPrefixAndMessage(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedCommitMessage("", "");
        }
        String text = raw.trim();

        // 1. Bracketed prefix: [PREFIX] message
        Matcher bracketMatcher = Pattern.compile("^\\[([^\\]\\r\\n]+)\\]\\s*(.*)$", Pattern.DOTALL).matcher(text);
        if (bracketMatcher.find()) {
            String p = bracketMatcher.group(1).trim();
            String m = bracketMatcher.group(2).trim();
            return new ParsedCommitMessage(p, m);
        }

        // 2. Issue key prefix with colon/dash: ABC-123: message or ABC-123 - message
        Matcher keySepMatcher = Pattern.compile("^([A-Za-z]+-[0-9]+)\\s*[:\\-]\\s*(.*)$", Pattern.DOTALL).matcher(text);
        if (keySepMatcher.find()) {
            String p = keySepMatcher.group(1).trim();
            String m = keySepMatcher.group(2).trim();
            return new ParsedCommitMessage(p, m);
        }

        // 3. Issue key prefix with whitespace: ABC-123 message
        Matcher keySpaceMatcher = Pattern.compile("^([A-Za-z]+-[0-9]+)\\s+(.*)$", Pattern.DOTALL).matcher(text);
        if (keySpaceMatcher.find()) {
            String p = keySpaceMatcher.group(1).trim();
            String m = keySpaceMatcher.group(2).trim();
            return new ParsedCommitMessage(p, m);
        }

        // 4. Issue key alone: ABC-123
        Matcher keyOnlyMatcher = Pattern.compile("^([A-Za-z]+-[0-9]+)$").matcher(text);
        if (keyOnlyMatcher.find()) {
            return new ParsedCommitMessage(keyOnlyMatcher.group(1).trim(), "");
        }

        // 5. Generic token with colon: feat-login: message
        Matcher tokenColonMatcher = Pattern.compile("^([A-Za-z0-9_-]+)\\s*:\\s+(.*)$", Pattern.DOTALL).matcher(text);
        if (tokenColonMatcher.find()) {
            String p = tokenColonMatcher.group(1).trim();
            String m = tokenColonMatcher.group(2).trim();
            return new ParsedCommitMessage(p, m);
        }

        // 6. No prefix recognized at start
        return new ParsedCommitMessage("", text);
    }

    public static String cleanCommitMessage(@Nullable String rawMsg, @Nullable String currentPrefix) {
        if (rawMsg == null || rawMsg.isBlank()) {
            return "";
        }
        String msg = rawMsg.trim();

        // If currentPrefix is provided, try stripping it first
        if (currentPrefix != null && !currentPrefix.isBlank()) {
            String cp = currentPrefix.trim().replaceAll("^\\[|\\]$", "").trim();
            if (!cp.isEmpty()) {
                if (msg.equalsIgnoreCase(cp) || msg.equalsIgnoreCase("[" + cp + "]")) {
                    return "";
                }
                Pattern bracketP = Pattern.compile("^\\[" + Pattern.quote(cp) + "\\]\\s*", Pattern.CASE_INSENSITIVE);
                Matcher m = bracketP.matcher(msg);
                if (m.find()) {
                    return msg.substring(m.end()).trim();
                }
                Pattern sepP = Pattern.compile("^" + Pattern.quote(cp) + "(?:\\s*[:\\-]\\s*|\\s+)", Pattern.CASE_INSENSITIVE);
                m = sepP.matcher(msg);
                if (m.find()) {
                    return msg.substring(m.end()).trim();
                }
            }
        }

        // If message starts with any bracketed tag [TAG], strip it
        Matcher bracketMatcher = Pattern.compile("^\\[[^\\]\\r\\n]+\\]\\s*", Pattern.DOTALL).matcher(msg);
        if (bracketMatcher.find()) {
            String stripped = msg.substring(bracketMatcher.end()).trim();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }

        // If message starts with standard issue key like ABC-123: or ABC-123 - or ABC-123 space
        Matcher keyMatcher = Pattern.compile("^[A-Za-z]+-[0-9]+(?:\\s*[:\\-]\\s*|\\s+)", Pattern.DOTALL).matcher(msg);
        if (keyMatcher.find()) {
            String stripped = msg.substring(keyMatcher.end()).trim();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }

        return msg;
    }

    public static boolean matchesPrefix(@Nullable String message, @Nullable String prefix) {
        if (message == null || message.isBlank() || prefix == null || prefix.isBlank()) {
            return false;
        }
        String cleanPrefix = prefix.trim().replaceAll("^\\[|\\]$", "").trim();
        if (cleanPrefix.isEmpty()) {
            return false;
        }
        String trimmed = message.trim();
        Pattern bracketPattern = Pattern.compile("^\\[" + Pattern.quote(cleanPrefix) + "\\]", Pattern.CASE_INSENSITIVE);
        if (bracketPattern.matcher(trimmed).find()) {
            return true;
        }
        Pattern sepPattern = Pattern.compile("^" + Pattern.quote(cleanPrefix) + "(?:\\s*[:\\-]\\s*|\\s+|$)", Pattern.CASE_INSENSITIVE);
        if (sepPattern.matcher(trimmed).find()) {
            return true;
        }
        return MultiBranchConfig.isPrefixAlreadyInMessage(trimmed, cleanPrefix);
    }

    public static String findLatestCommitMessageForPrefix(@Nullable List<String> historyMessages, @Nullable String prefix) {
        if (historyMessages == null || historyMessages.isEmpty() || prefix == null || prefix.isBlank()) {
            return "";
        }
        String cleanPrefix = prefix.trim().replaceAll("^\\[|\\]$", "").trim();
        if (cleanPrefix.isEmpty()) {
            return "";
        }
        for (int i = historyMessages.size() - 1; i >= 0; i--) {
            String m = historyMessages.get(i);
            if (m != null && !m.isBlank() && matchesPrefix(m, cleanPrefix)) {
                return cleanCommitMessage(m, cleanPrefix);
            }
        }
        return "";
    }

    public static List<String> sortRecentMessagesLatestTop(@Nullable List<String> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sorted = new ArrayList<>();
        for (int i = rawMessages.size() - 1; i >= 0; i--) {
            String m = rawMessages.get(i);
            if (m != null && !m.isBlank()) {
                String trimmed = m.trim();
                if (!sorted.contains(trimmed)) {
                    sorted.add(trimmed);
                }
            }
        }
        return sorted;
    }

    public static InitialCommitInfo resolveInitialCommitInfo(
            @Nullable MultiBranchCommitAction.BranchDetectionResult detection,
            @Nullable String lastTaskPrefix,
            @Nullable List<String> historyMessages) {

        boolean onPrefixBranch = detection != null
                && detection.isOnDesignatedBranch()
                && detection.getDetectedPrefix() != null
                && !detection.getDetectedPrefix().isBlank();

        if (onPrefixBranch) {
            String prefix = detection.getDetectedPrefix().trim();
            String commitMsg = findLatestCommitMessageForPrefix(historyMessages, prefix);
            return new InitialCommitInfo(prefix, commitMsg);
        } else {
            // Not on a prefix branch (one from preconfigured branches)
            // Init prefix and commit message from latest in history
            String prefix = "";
            String commitMsg = "";

            if (historyMessages != null && !historyMessages.isEmpty()) {
                // Find latest non-empty message in history
                for (int i = historyMessages.size() - 1; i >= 0; i--) {
                    String raw = historyMessages.get(i);
                    if (raw != null && !raw.isBlank()) {
                        ParsedCommitMessage parsed = extractPrefixAndMessage(raw);
                        commitMsg = parsed.getMessage();
                        prefix = parsed.getPrefix();
                        break;
                    }
                }

                // If latest message didn't specify a prefix, search history for most recent prefix
                if (prefix.isBlank()) {
                    for (int i = historyMessages.size() - 1; i >= 0; i--) {
                        String raw = historyMessages.get(i);
                        if (raw != null && !raw.isBlank()) {
                            ParsedCommitMessage parsed = extractPrefixAndMessage(raw);
                            if (!parsed.getPrefix().isBlank()) {
                                prefix = parsed.getPrefix();
                                break;
                            }
                        }
                    }
                }
            }

            // Fallback for prefix if history had none
            if (prefix.isBlank()) {
                if (lastTaskPrefix != null && !lastTaskPrefix.isBlank()) {
                    prefix = lastTaskPrefix.trim();
                } else if (detection != null && !detection.getDetectedPrefix().isBlank()) {
                    prefix = detection.getDetectedPrefix().trim();
                }
            }

            return new InitialCommitInfo(prefix, commitMsg);
        }
    }

    public static List<String> getCommitMessageHistory(@Nullable Project project) {
        if (project == null) return Collections.emptyList();
        try {
            VcsConfiguration vcsConfig = VcsConfiguration.getInstance(project);
            if (vcsConfig != null) {
                List<String> messages = vcsConfig.getRecentMessages();
                if (messages != null && !messages.isEmpty()) {
                    return new ArrayList<>(messages);
                }
            }
        } catch (Exception ignored) {}

        try {
            Collection<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
            if (!repos.isEmpty()) {
                GitRepository repo = repos.iterator().next();
                File repoDir = new File(repo.getRoot().getPath());
                MultiBranchService.GitResult res = MultiBranchService.runGit(repoDir, "log", "-n", "30", "--pretty=format:%B%x1e");
                if (res.exitCode == 0 && !res.stdout.isBlank()) {
                    String[] entries = res.stdout.split("\u001e");
                    List<String> list = new ArrayList<>();
                    for (String entry : entries) {
                        if (entry != null && !entry.isBlank()) {
                            list.add(entry.trim());
                        }
                    }
                    Collections.reverse(list);
                    return list;
                }
            }
        } catch (Exception ignored) {}

        return Collections.emptyList();
    }

    public MultiBranchCommitDialog(@Nullable Project project, MultiBranchConfig initialConfig, String defaultPrefix) {
        this(project, initialConfig, defaultPrefix, null);
    }

    public MultiBranchCommitDialog(@Nullable Project project, MultiBranchConfig initialConfig, String defaultPrefix, @Nullable MultiBranchCommitAction.BranchDetectionResult detection) {
        super(project, true);
        this.project = project;
        this.config = initialConfig;
        this.detection = detection;

        MultiBranchSettings settings = project != null ? MultiBranchSettings.getInstance(project) : null;
        String lastTaskPrefix = (settings != null && settings.getState().lastTaskPrefix != null) ? settings.getState().lastTaskPrefix : "";
        List<String> history = getCommitMessageHistory(project);

        InitialCommitInfo initialInfo = resolveInitialCommitInfo(detection, lastTaskPrefix, history);
        if (initialInfo.getPrefix() != null && !initialInfo.getPrefix().isBlank()) {
            config.setTaskPrefix(initialInfo.getPrefix());
        } else if (defaultPrefix != null && !defaultPrefix.isBlank()) {
            config.setTaskPrefix(defaultPrefix);
        }
        if (initialInfo.getCommitMessage() != null) {
            config.setCommitMessage(initialInfo.getCommitMessage());
        }

        setTitle("Multi-Branch Commit & Push (v" + MultiBranchReloadAction.getRunningVersion() + ")");
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
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 0.5;

        JPanel msgPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel msgHeader = new JPanel(new BorderLayout());
        msgHeader.add(new JBLabel("Commit Message:"), BorderLayout.WEST);

        JButton historyBtn = new JButton("Recent Messages (Ctrl+M)...");
        historyBtn.setFocusable(false);
        historyBtn.setToolTipText("Reuse recent commit messages from IDEA history");
        historyBtn.addActionListener(e -> showRecentMessagesPopup(historyBtn));
        msgHeader.add(historyBtn, BorderLayout.EAST);
        msgPanel.add(msgHeader, BorderLayout.NORTH);

        messageArea = new JBTextArea(config.getCommitMessage(), 6, 50);
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

        // Register Ctrl+Enter / Cmd+Enter to commit
        messageArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "submitCommit");
        messageArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), "submitCommit");
        messageArea.getActionMap().put("submitCommit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (getOKAction().isEnabled()) {
                    doOKAction();
                }
            }
        });

        JBScrollPane msgScrollPane = new JBScrollPane(messageArea);
        msgScrollPane.setPreferredSize(new Dimension(JBUI.scale(620), JBUI.scale(120)));
        msgScrollPane.setMinimumSize(new Dimension(JBUI.scale(400), JBUI.scale(90)));
        msgPanel.add(msgScrollPane, BorderLayout.CENTER);

        prefixMessageCheckbox = new JBCheckBox("Prepend prefix as [PREFIX] to commit message", config.isPrefixMessageWithTask());
        msgPanel.add(prefixMessageCheckbox, BorderLayout.SOUTH);
        root.add(msgPanel, gbc);

        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;

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

        JButton settingsBtn = new JButton("Settings...");
        settingsBtn.setFocusable(false);
        settingsBtn.setToolTipText("Configure branch mappings and target settings");
        settingsBtn.addActionListener(e -> openSettingsDialog());
        headerPanel.add(settingsBtn, BorderLayout.EAST);
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
        JPanel optionsBox = new JPanel(new GridLayout(3, 2, JBUI.scale(8), JBUI.scale(4)));
        optionsBox.setBorder(BorderFactory.createTitledBorder("Options"));

        fetchCheckbox = new JBCheckBox("Fetch origin first", config.isFetchOriginFirst());
        pushCheckbox = new JBCheckBox("Push branches to origin", config.isPushAfterCommit());
        mrLinksCheckbox = new JBCheckBox("Generate MR links", config.isGenerateMrLinks());
        openMrCheckbox = new JBCheckBox("Open created MRs in browser", config.isOpenMrLinksInBrowser());

        stashCheckbox = new JBCheckBox("Stash other folders & pop after", config.isStashOtherChanges());
        stashCheckbox.setToolTipText("If uncommitted changes exist in other folders, stash before commit and pop after checkout " + config.getCheckoutBranch());

        checkoutTestCheckbox = new JBCheckBox("Checkout " + config.getCheckoutBranch() + " on finish", config.isCheckoutTestAfter());
        checkoutTestCheckbox.setToolTipText("Checkout " + config.getCheckoutBranch() + " after commits/pushes are complete");

        mrLinksCheckbox.setEnabled(pushCheckbox.isSelected());
        openMrCheckbox.setEnabled(pushCheckbox.isSelected());

        pushCheckbox.addActionListener(e -> {
            boolean p = pushCheckbox.isSelected();
            mrLinksCheckbox.setEnabled(p);
            openMrCheckbox.setEnabled(p);
        });

        optionsBox.add(fetchCheckbox);
        optionsBox.add(pushCheckbox);
        optionsBox.add(mrLinksCheckbox);
        optionsBox.add(openMrCheckbox);
        optionsBox.add(stashCheckbox);
        optionsBox.add(checkoutTestCheckbox);
        root.add(optionsBox, gbc);

        // Dynamic prefix update
        prefixField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateBranchPreviews(); }
            public void removeUpdate(DocumentEvent e) { updateBranchPreviews(); }
            public void changedUpdate(DocumentEvent e) { updateBranchPreviews(); }
        });

        root.setPreferredSize(new Dimension(JBUI.scale(680), JBUI.scale(580)));
        return root;
    }

    private void openSettingsDialog() {
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
                config.setGitLabHost(settings.getState().gitLabHost);
                String detectedHost = GitLabTokenManager.detectHost(project, settings.getState().gitLabHost);
                String token = GitLabTokenManager.getToken(detectedHost);
                if (token != null && !token.isBlank()) {
                    config.setGitLabApiToken(token);
                } else {
                    config.setGitLabApiToken(settings.getState().gitLabApiToken);
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
        previewLabels.clear();

        String currentPrefix = (prefixField != null) ? prefixField.getText().trim() : config.getTaskPrefix();
        String curBranch = (detection != null) ? detection.getCurrentBranch() : "";

        for (BranchMapping mapping : config.getBranchMappings()) {
            JPanel row = new JPanel(new BorderLayout(JBUI.scale(8), 0));
            JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));

            String localBranch = mapping.getLocalBranchName(currentPrefix);
            boolean isCurrent = !curBranch.isEmpty() && curBranch.equalsIgnoreCase(localBranch);
            boolean onRemote = isRemoteBranchPresent(localBranch);

            String cbLabel = onRemote ? "Update origin/" + localBranch : "From " + mapping.getSourceOriginBranch();
            JCheckBox cb = new JCheckBox(cbLabel, mapping.isEnabled());
            cb.addActionListener(e -> mapping.setEnabled(cb.isSelected()));

            leftPanel.add(cb);

            String previewText = "➔ " + localBranch + " (Merge target: " + mapping.getTargetOriginBranchName() + ")";
            if (isCurrent) {
                previewText += "  [CURRENT BRANCH]";
            }
            if (onRemote) {
                previewText += "  [ON REMOTE - UPDATING]";
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
        List<String> rawMessages = getCommitMessageHistory(project);
        if (rawMessages == null || rawMessages.isEmpty()) {
            Messages.showInfoMessage(project, "No recent commit messages found in history.", "Commit Message History");
            return;
        }

        List<String> sortedMessages = sortRecentMessagesLatestTop(rawMessages);
        if (sortedMessages.isEmpty()) {
            Messages.showInfoMessage(project, "No recent commit messages found in history.", "Commit Message History");
            return;
        }

        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(sortedMessages)
                .setTitle("Recent Commit Messages")
                .setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                        if (value instanceof String s) {
                            String firstLine = s.lines().findFirst().orElse("");
                            if (s.contains("\n")) {
                                setText(firstLine + " ...");
                            } else {
                                setText(firstLine);
                            }
                        }
                        return this;
                    }
                })
                .setItemChosenCallback(selected -> {
                    if (selected != null && !selected.isBlank()) {
                        String currentPrefix = (prefixField != null) ? prefixField.getText().trim() : "";
                        String cleanMsg = cleanCommitMessage(selected, currentPrefix);
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
            if (i < mappingCheckboxes.size()) {
                mappingCheckboxes.get(i).setText(onRemote ? "Update origin/" + localBranch : "From " + m.getSourceOriginBranch());
            }

            String previewText = "➔ " + localBranch + " (Merge target: " + m.getTargetOriginBranchName() + ")";
            if (isCurrent) {
                previewText += "  [CURRENT BRANCH]";
            }
            if (onRemote) {
                previewText += "  [ON REMOTE - UPDATING]";
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
        }
        config.setFetchOriginFirst(fetchCheckbox.isSelected());
        config.setPushAfterCommit(pushCheckbox.isSelected());
        config.setGenerateMrLinks(mrLinksCheckbox.isSelected());
        config.setOpenMrLinksInBrowser(openMrCheckbox.isSelected());
        config.setStashOtherChanges(stashCheckbox.isSelected());
        config.setCheckoutTestAfter(checkoutTestCheckbox.isSelected());

        if (project != null) {
            MultiBranchSettings settings = MultiBranchSettings.getInstance(project);
            if (settings != null) {
                MultiBranchSettings.State state = settings.getState();
                state.lastTaskPrefix = config.getTaskPrefix();
                state.prefixMessageWithTask = config.isPrefixMessageWithTask();
                state.defaultChangelistName = config.getChangelistName();
                state.fetchOriginFirst = config.isFetchOriginFirst();
                state.pushAfterCommit = config.isPushAfterCommit();
                state.generateMrLinks = config.isGenerateMrLinks();
                state.openMrLinksInBrowser = config.isOpenMrLinksInBrowser();
                state.stashOtherChanges = config.isStashOtherChanges();
                state.checkoutTestAfter = config.isCheckoutTestAfter();
                for (BranchMapping cm : config.getBranchMappings()) {
                    for (BranchMapping sm : state.branchMappings) {
                        if (java.util.Objects.equals(cm.getSourceOriginBranch(), sm.getSourceOriginBranch()) &&
                                java.util.Objects.equals(cm.getTargetOriginBranchName(), sm.getTargetOriginBranchName())) {
                            sm.setEnabled(cm.isEnabled());
                            break;
                        }
                    }
                }
            }
            VcsConfiguration.getInstance(project).saveCommitMessage(config.getCommitMessage());
            VcsConfiguration.getInstance(project).saveCommitMessage(config.getFormattedCommitMessage());
        }

        super.doOKAction();
    }

    public MultiBranchConfig getConfig() {
        return config;
    }
}
