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
        setTitle("Multi-Branch Review & Execution Summary (v" + MultiBranchReloadAction.getRunningVersion() + ")");
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
        long failedCount = results.size() - successCount;

        JPanel topBanner = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(4)));
        topBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                JBUI.Borders.empty(4, 4, 8, 4)
        ));

        StringBuilder titleText = new StringBuilder();
        titleText.append("Workflow Execution Review (").append(successCount).append("/").append(results.size()).append(" branches succeeded");
        if (failedCount > 0) {
            titleText.append(", ").append(failedCount).append(" failed");
        }
        titleText.append(")");

        JLabel titleLbl = new JLabel(titleText.toString());
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(14)));
        if (failedCount == 0 && successCount == results.size()) {
            titleLbl.setForeground(new Color(0x2E7D32));
        } else {
            titleLbl.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
        }
        topBanner.add(titleLbl, BorderLayout.NORTH);

        StringBuilder stats = new StringBuilder();
        stats.append("Pushed: ").append(pushedCount).append("/").append(results.size());
        if (mrCount > 0) {
            stats.append("  |  MRs Created: ").append(mrCount);
        }
        if (failedCount > 0) {
            stats.append("  |  Failed Branches: ").append(failedCount);
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

            boolean isFailed = !item.isSuccess();

            String statusIcon = isFailed ? "✗ " : "✓ ";
            String cardTitle = statusIcon + item.getBranchName() + "  ➔  " + item.getTargetBranch();
            if (isFailed) {
                cardTitle += "  [FAILED]";
            }

            JLabel titleLabel = new JLabel(cardTitle);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
            if (isFailed) {
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
            } else {
                if (item.getCommitHash() != null && !item.getCommitHash().isBlank()) {
                    detailsPanel.add(new JBLabel("Commit: " + item.getCommitHash()));
                }
                if (item.getPushDetails() != null && !item.getPushDetails().equals("Local only")) {
                    JLabel pushLbl = new JBLabel("Push: " + item.getPushDetails());
                    pushLbl.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
                    detailsPanel.add(pushLbl);
                }
                String err = item.getErrorMessage();
                if (err != null && !err.isBlank()) {
                    JPanel errBox = new JPanel(new BorderLayout(0, JBUI.scale(2)));
                    errBox.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(JBUI.CurrentTheme.NotificationError.borderColor(), 1),
                            JBUI.Borders.empty(6, 8)
                    ));
                    errBox.setBackground(JBUI.CurrentTheme.NotificationError.backgroundColor());

                    JLabel errTitle = new JLabel("Problem Details:");
                    errTitle.setFont(errTitle.getFont().deriveFont(Font.BOLD));
                    errTitle.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
                    errBox.add(errTitle, BorderLayout.NORTH);

                    JTextArea errText = new JTextArea(err);
                    errText.setEditable(false);
                    errText.setLineWrap(true);
                    errText.setWrapStyleWord(true);
                    errText.setBackground(JBUI.CurrentTheme.NotificationError.backgroundColor());
                    errText.setForeground(JBUI.CurrentTheme.NotificationError.foregroundColor());
                    errText.setBorder(JBUI.Borders.empty(2));
                    errBox.add(errText, BorderLayout.CENTER);

                    detailsPanel.add(errBox);
                } else {
                    detailsPanel.add(new JBLabel("Operation failed."));
                }
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
