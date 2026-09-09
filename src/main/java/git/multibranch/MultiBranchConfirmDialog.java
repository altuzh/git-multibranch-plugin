package git.multibranch;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class MultiBranchConfirmDialog extends DialogWrapper {
    private final MultiBranchConfig config;
    private final GitRepository repository;

    public MultiBranchConfirmDialog(@Nullable Project project, @NotNull GitRepository repository, @NotNull MultiBranchConfig config) {
        super(project, true);
        this.repository = repository;
        this.config = config;
        setTitle("Confirm Multi-Branch Execution (v" + MultiBranchReloadAction.getRunningVersion() + ")");
        setOKButtonText("Confirm & Execute");
        setCancelButtonText("Cancel");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(10)));
        root.setPreferredSize(new Dimension(JBUI.scale(720), JBUI.scale(480)));

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JLabel headerTitle = new JLabel("Review Planned Actions");
        headerTitle.setFont(headerTitle.getFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(14)));
        headerPanel.add(headerTitle, BorderLayout.NORTH);

        JLabel headerDesc = new JLabel("<html>The following multi-branch workflow actions will be performed. Please verify the steps below before proceeding.</html>");
        headerDesc.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        headerPanel.add(headerDesc, BorderLayout.CENTER);
        root.add(headerPanel, BorderLayout.NORTH);

        // Content
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

        // 1. Commit & Changelist Overview Box
        JPanel metaBox = new JPanel(new GridBagLayout());
        metaBox.setBorder(BorderFactory.createTitledBorder("Execution Overview"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(2, 6, 2, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;

        metaBox.add(new JBLabel("Task Prefix:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel prefixLbl = new JLabel(config.getTaskPrefix().isEmpty() ? "(none)" : config.getTaskPrefix());
        prefixLbl.setFont(prefixLbl.getFont().deriveFont(Font.BOLD));
        metaBox.add(prefixLbl, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        metaBox.add(new JBLabel("Changelist:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        metaBox.add(new JLabel(config.getChangelistName()), gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        metaBox.add(new JBLabel("Commit Message:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel msgLbl = new JLabel(config.getFormattedCommitMessage());
        msgLbl.setFont(msgLbl.getFont().deriveFont(Font.BOLD));
        metaBox.add(msgLbl, gbc);

        contentPanel.add(metaBox);
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)));

        // 2. Target Branches & Future Actions Box
        JPanel branchesBox = new JPanel();
        branchesBox.setLayout(new BoxLayout(branchesBox, BoxLayout.Y_AXIS));
        branchesBox.setBorder(BorderFactory.createTitledBorder("Target Branches & Planned Actions"));

        List<BranchMapping> mappings = config.getBranchMappings();
        int activeCount = 0;
        for (BranchMapping mapping : mappings) {
            if (!mapping.isEnabled()) continue;
            activeCount++;

            String localBranch = mapping.getLocalBranchName(config.getTaskPrefix());
            JPanel card = new JPanel(new BorderLayout(JBUI.scale(6), JBUI.scale(2)));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                    JBUI.Borders.empty(6, 4)
            ));

            String title = "● Branch: " + localBranch + " (Source: " + mapping.getSourceOriginBranch() + " ➔ Target: " + mapping.getTargetOriginBranchName() + ")";
            JLabel cardTitle = new JLabel(title);
            cardTitle.setFont(cardTitle.getFont().deriveFont(Font.BOLD));
            card.add(cardTitle, BorderLayout.NORTH);

            JPanel stepsPanel = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(2)));
            stepsPanel.setBorder(JBUI.Borders.emptyLeft(12));

            stepsPanel.add(new JLabel("1. Checkout isolated worktree from " + mapping.getSourceOriginBranch() + " as " + localBranch));
            stepsPanel.add(new JLabel("2. Apply patch from changelist '" + config.getChangelistName() + "' & commit"));

            if (config.isPushAfterCommit()) {
                stepsPanel.add(new JLabel("3. Push " + localBranch + " to origin (check behind-remote status)"));
            } else {
                stepsPanel.add(new JLabel("3. Skip push (local branch only)"));
            }

            if (config.isGenerateMrLinks()) {
                stepsPanel.add(new JLabel("4. Create GitLab Merge Request: " + localBranch + " ➔ " + mapping.getTargetOriginBranchName()));
            }

            card.add(stepsPanel, BorderLayout.CENTER);
            branchesBox.add(card);
        }

        if (activeCount == 0) {
            JLabel noBranch = new JLabel("No branches selected for this execution.");
            noBranch.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
            branchesBox.add(noBranch);
        }

        contentPanel.add(branchesBox);
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)));

        // 3. Pre & Post Execution Steps Box
        JPanel pipelineBox = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(4)));
        pipelineBox.setBorder(BorderFactory.createTitledBorder("Pre & Post Operations"));

        if (config.isFetchOriginFirst()) {
            pipelineBox.add(new JLabel("➔ Pre-action: Fetch origin before commencing branch operations"));
        }
        if (config.isStashOtherChanges()) {
            pipelineBox.add(new JLabel("➔ Pre-action: Stash uncommitted changes from other folders/changelists safely"));
        }

        String checkoutBranch = config.getCheckoutBranch();
        if (checkoutBranch == null || checkoutBranch.isBlank()) {
            checkoutBranch = "deploy/test";
        }
        pipelineBox.add(new JLabel("➔ Post-action: Fetch '" + checkoutBranch + "' from origin (fetch post-action checkout branch)"));

        if (config.isCheckoutTestAfter()) {
            pipelineBox.add(new JLabel("➔ Post-action: Checkout '" + checkoutBranch + "' on finish and fast-forward to latest origin"));
        }
        if (config.isStashOtherChanges()) {
            pipelineBox.add(new JLabel("➔ Post-action: Restore stashed changes (git stash pop)"));
        }
        pipelineBox.add(new JLabel("➔ Post-action: Display full execution review window upon completion"));

        contentPanel.add(pipelineBox);

        JBScrollPane scrollPane = new JBScrollPane(contentPanel);
        scrollPane.setBorder(JBUI.Borders.empty());
        root.add(scrollPane, BorderLayout.CENTER);

        return root;
    }
}
