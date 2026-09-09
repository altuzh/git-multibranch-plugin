package git.multibranch;

import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ConfigureBranchesDialog extends DialogWrapper {
    private final Project project;
    private BranchMappingsTablePanel tablePanel;
    private JBTextField checkoutBranchField;
    private JBCheckBox checkoutCheckbox;

    public ConfigureBranchesDialog(@Nullable Project project) {
        super(project, true);
        this.project = project;
        setTitle("Settings (v" + MultiBranchReloadAction.getRunningVersion() + ")");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(10)));
        root.setPreferredSize(new Dimension(JBUI.scale(700), JBUI.scale(480)));

        // Description header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(new JBLabel("<html>Configure branch mappings across target environments.<br>" +
                "Each active mapping creates an isolated branch from its origin tracking branch, commits the changelist, and generates a GitLab MR or GitHub PR link.</html>"), BorderLayout.CENTER);

        JButton openSettingsBtn = new JButton("Open All Settings...");
        openSettingsBtn.setFocusable(false);
        openSettingsBtn.setToolTipText("Open the full Multi-Branch Workflow settings in IDE Preferences");
        openSettingsBtn.addActionListener(e -> {
            close(CANCEL_EXIT_CODE);
            if (project != null) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, MultiBranchSettingsConfigurable.class);
            }
        });
        headerPanel.add(openSettingsBtn, BorderLayout.EAST);
        root.add(headerPanel, BorderLayout.NORTH);

        // Center: Branch Mappings Table
        tablePanel = new BranchMappingsTablePanel(project);
        MultiBranchSettings settings = MultiBranchSettings.getInstance(project);
        if (settings != null) {
            tablePanel.setMappings(settings.getState().branchMappings);
        } else {
            tablePanel.setMappings(new MultiBranchSettings.State().branchMappings);
        }
        root.add(tablePanel, BorderLayout.CENTER);

        // Post-Action Checkout Branch Configuration
        JPanel bottomOptions = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        bottomOptions.setBorder(BorderFactory.createTitledBorder("Post-Action Settings"));

        JPanel checkoutRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        String currentCheckoutBranch = (settings != null && settings.getState().checkoutBranch != null)
                ? settings.getState().checkoutBranch
                : "deploy/test";
        boolean currentCheckoutOnFinish = (settings == null || settings.getState().checkoutTestAfter);

        checkoutCheckbox = new JBCheckBox("Checkout branch on finish:", currentCheckoutOnFinish);
        checkoutBranchField = new JBTextField(currentCheckoutBranch, 20);
        checkoutBranchField.getEmptyText().setText("e.g. deploy/test or main");
        checkoutCheckbox.addActionListener(e -> checkoutBranchField.setEnabled(checkoutCheckbox.isSelected()));
        checkoutBranchField.setEnabled(checkoutCheckbox.isSelected());

        checkoutRow.add(checkoutCheckbox, BorderLayout.WEST);
        checkoutRow.add(checkoutBranchField, BorderLayout.CENTER);
        bottomOptions.add(checkoutRow, BorderLayout.CENTER);

        root.add(bottomOptions, BorderLayout.SOUTH);

        return root;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        List<BranchMapping> mappings = tablePanel.getMappings();
        if (mappings.isEmpty()) {
            return new ValidationInfo("At least one target branch mapping must be defined", tablePanel);
        }
        if (checkoutCheckbox.isSelected() && checkoutBranchField.getText().trim().isEmpty()) {
            return new ValidationInfo("Checkout branch name cannot be empty when enabled", checkoutBranchField);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        if (project != null) {
            MultiBranchSettings settings = MultiBranchSettings.getInstance(project);
            if (settings != null) {
                MultiBranchSettings.State state = settings.getState();
                state.branchMappings.clear();
                for (BranchMapping m : tablePanel.getMappings()) {
                    state.branchMappings.add(m.copy());
                }
                state.checkoutBranch = checkoutBranchField.getText().trim();
                state.checkoutTestAfter = checkoutCheckbox.isSelected();
            }
        }
        super.doOKAction();
    }
}
