package git.multibranch;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;

public class MultiBranchResultDialog extends DialogWrapper {
    private final List<MultiBranchResultItem> results;

    public MultiBranchResultDialog(@Nullable Project project, List<MultiBranchResultItem> results) {
        super(project, true);
        this.results = results != null ? results : List.of();
        setTitle("Multi-Branch Review & Execution Summary");
        setOKButtonText("Close");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(10)));
        panel.setPreferredSize(new Dimension(JBUI.scale(740), JBUI.scale(440)));

        // Top Summary Banner
        long successCount = results.stream().filter(MultiBranchResultItem::isSuccess).count();
        long pushedCount = results.stream().filter(MultiBranchResultItem::isPushed).count();
        long mrCount = results.stream().filter(r -> r.getMrUrl() != null && !r.getMrUrl().isBlank()).count();
        long mergedCount = results.stream().filter(MultiBranchResultItem::isMrMerged).count();
        long mergeErrorsCount = results.stream().filter(MultiBranchResultItem::isMrMergeError).count();

        JPanel topBanner = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(4)));
        topBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                JBUI.Borders.empty(4, 4, 8, 4)
        ));

        JLabel titleLbl = new JLabel("Workflow Execution Review (" + successCount + "/" + results.size() + " branches succeeded)");
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(14)));
        if (successCount == results.size()) {
            titleLbl.setForeground(new Color(0x2E7D32));
        } else {
            titleLbl.setForeground(JBUI.CurrentTheme.NotificationWarning.foregroundColor());
        }
        topBanner.add(titleLbl, BorderLayout.NORTH);

        StringBuilder stats = new StringBuilder();
        stats.append("Pushed: ").append(pushedCount).append("/").append(results.size());
        if (mrCount > 0) {
            stats.append("  |  MRs Created: ").append(mrCount);
        }
        if (mergedCount > 0) {
            stats.append("  |  Auto-Merged: ").append(mergedCount);
        }
        if (mergeErrorsCount > 0) {
            stats.append("  |  Merge Errors: ").append(mergeErrorsCount);
        }
        JLabel statsLbl = new JLabel(stats.toString());
        statsLbl.setForeground(JBUI.CurrentTheme.Label.foreground());
        topBanner.add(statsLbl, BorderLayout.CENTER);
        panel.add(topBanner, BorderLayout.NORTH);

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        for (MultiBranchResultItem item : results) {
            JPanel card = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(4)));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                    JBUI.Borders.empty(8)
            ));

            String statusIcon = item.isSuccess() ? "✓ " : "✗ ";
            String titleText = statusIcon + item.getBranchName() + "  ➔  " + item.getTargetBranch();
            JLabel titleLabel = new JLabel(titleText);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
            if (!item.isSuccess()) {
                titleLabel.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
            }

            JPanel detailsPanel = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(3)));
            if (item.isSuccess()) {
                detailsPanel.add(new JBLabel("Commit: " + (item.getCommitHash() != null ? item.getCommitHash() : "N/A")));
                JLabel pushLbl = new JBLabel("Push: " + item.getPushDetails());
                if (item.getPushDetails().contains("Failed") || item.getPushDetails().contains("Rejected")) {
                    pushLbl.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
                } else if (item.getPushDetails().contains("Skipped")) {
                    pushLbl.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
                }
                detailsPanel.add(pushLbl);

                if (item.getMrUrl() != null && !item.getMrUrl().isEmpty()) {
                    JTextField urlField = new JTextField(item.getMrUrl());
                    urlField.setEditable(false);
                    urlField.setBorder(JBUI.Borders.empty(2));

                    JPanel mrRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
                    boolean isGitHub = item.getMrUrl().contains("github.com");
                    boolean isCreatedMr = item.getMrUrl().contains("/merge_requests/") && !item.getMrUrl().contains("/new?");
                    String labelPrefix = isCreatedMr ? "Merge Request: " : (isGitHub ? "PR Link: " : "MR Link: ");
                    mrRow.add(new JBLabel(labelPrefix), BorderLayout.WEST);
                    mrRow.add(urlField, BorderLayout.CENTER);

                    JButton openBtn = new JButton(isCreatedMr ? "View MR" : (isGitHub ? "Open PR" : "Open MR"));
                    openBtn.addActionListener(e -> BrowserUtil.browse(item.getMrUrl()));
                    JButton copyBtn = new JButton("Copy");
                    copyBtn.addActionListener(e -> {
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(item.getMrUrl()), null);
                    });

                    JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
                    btnRow.add(copyBtn);
                    btnRow.add(openBtn);
                    mrRow.add(btnRow, BorderLayout.EAST);
                    detailsPanel.add(mrRow);
                }

                // MR Merge Status row
                if (item.getMrMergeStatus() != null && !item.getMrMergeStatus().isBlank()) {
                    JLabel mergeLbl = new JLabel();
                    if (item.isMrMerged()) {
                        mergeLbl.setText("✓ Auto-merge: " + item.getMrMergeStatus());
                        mergeLbl.setFont(mergeLbl.getFont().deriveFont(Font.BOLD));
                        mergeLbl.setForeground(new Color(0x2E7D32));
                    } else if (item.isMrMergeError()) {
                        mergeLbl.setText("✗ Auto-merge: " + item.getMrMergeStatus());
                        mergeLbl.setFont(mergeLbl.getFont().deriveFont(Font.BOLD));
                        mergeLbl.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
                    } else {
                        mergeLbl.setText("● Auto-merge: " + item.getMrMergeStatus());
                        mergeLbl.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
                    }
                    detailsPanel.add(mergeLbl);
                }
            } else {
                detailsPanel.add(new JBLabel("Error: " + item.getErrorMessage()));
            }

            card.add(titleLabel, BorderLayout.NORTH);
            card.add(detailsPanel, BorderLayout.CENTER);
            listPanel.add(card);
        }

        JBScrollPane scrollPane = new JBScrollPane(listPanel);
        panel.add(scrollPane, BorderLayout.CENTER);

        boolean anyGitHub = results.stream().anyMatch(r -> r.getMrUrl() != null && r.getMrUrl().contains("github.com"));
        boolean anyGitLab = results.stream().anyMatch(r -> r.getMrUrl() != null && !r.getMrUrl().contains("github.com"));
        String typeLabel = (anyGitHub && !anyGitLab) ? "PR" : (anyGitLab && !anyGitHub) ? "MR" : "PR / MR";

        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton copyAllBtn = new JButton("Copy All " + typeLabel + " Links");
        copyAllBtn.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            for (MultiBranchResultItem item : results) {
                if (item.getMrUrl() != null && !item.getMrUrl().isEmpty()) {
                    sb.append(item.getBranchName()).append(" -> ").append(item.getTargetBranch()).append("\n");
                    sb.append(item.getMrUrl()).append("\n\n");
                }
            }
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString().trim()), null);
        });
        JButton openAllBtn = new JButton("Open All " + typeLabel + "s in Browser");
        openAllBtn.addActionListener(e -> {
            for (MultiBranchResultItem item : results) {
                if (item.getMrUrl() != null && !item.getMrUrl().isEmpty()) {
                    BrowserUtil.browse(item.getMrUrl());
                }
            }
        });
        bottomBar.add(copyAllBtn);
        bottomBar.add(openAllBtn);
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }
}
