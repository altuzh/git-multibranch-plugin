package git.multibranch;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;

public class BranchEditDialog extends DialogWrapper {
    private final BranchMapping initial;
    private JBTextField sourceBranchField;
    private JBTextField targetBranchField;
    private JBTextField suffixField;
    private JBCheckBox enabledCheckbox;
    private JLabel previewLabel;
    private BranchMapping result;

    public BranchEditDialog(@Nullable Component parent, @Nullable BranchMapping initial) {
        super(parent, true);
        this.initial = initial;
        setTitle(initial == null ? "Add Target Branch Mapping" : "Edit Target Branch Mapping");
        init();
    }

    public BranchEditDialog(@Nullable Project project, @Nullable BranchMapping initial) {
        super(project, true);
        this.initial = initial;
        setTitle(initial == null ? "Add Target Branch Mapping" : "Edit Target Branch Mapping");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(JBUI.scale(480), JBUI.scale(220)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;

        // 1. Source Origin Branch
        JPanel sourcePanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JBLabel sourceLabel = new JBLabel("Source Origin Branch:");
        sourceLabel.setPreferredSize(new Dimension(JBUI.scale(170), JBUI.scale(24)));
        sourcePanel.add(sourceLabel, BorderLayout.WEST);
        sourceBranchField = new JBTextField(initial != null ? initial.getSourceOriginBranch() : "origin/deploy/dev");
        sourceBranchField.getEmptyText().setText("e.g. origin/deploy/dev or origin/main");
        sourcePanel.add(sourceBranchField, BorderLayout.CENTER);
        panel.add(sourcePanel, gbc);

        // 2. Target Origin Branch
        gbc.gridy++;
        JPanel targetPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JBLabel targetLabel = new JBLabel("Merge Target Branch:");
        targetLabel.setPreferredSize(new Dimension(JBUI.scale(170), JBUI.scale(24)));
        targetPanel.add(targetLabel, BorderLayout.WEST);
        targetBranchField = new JBTextField(initial != null ? initial.getTargetOriginBranchName() : "deploy/dev");
        targetBranchField.getEmptyText().setText("e.g. deploy/dev or main");
        targetPanel.add(targetBranchField, BorderLayout.CENTER);
        panel.add(targetPanel, gbc);

        // 3. Branch Suffix
        gbc.gridy++;
        JPanel suffixPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JBLabel suffixLabel = new JBLabel("Local Branch Suffix:");
        suffixLabel.setPreferredSize(new Dimension(JBUI.scale(170), JBUI.scale(24)));
        suffixPanel.add(suffixLabel, BorderLayout.WEST);
        suffixField = new JBTextField(initial != null ? initial.getBranchSuffix() : "-dev");
        suffixField.getEmptyText().setText("e.g. -dev, -test, -uat, -prod");
        suffixPanel.add(suffixField, BorderLayout.CENTER);
        panel.add(suffixPanel, gbc);

        // 4. Enabled checkbox
        gbc.gridy++;
        enabledCheckbox = new JBCheckBox("Enabled by default", initial == null || initial.isEnabled());
        panel.add(enabledCheckbox, gbc);

        // 5. Preview Label
        gbc.gridy++;
        JPanel previewBox = new JPanel(new BorderLayout());
        previewBox.setBorder(BorderFactory.createTitledBorder("Branch Preview (with sample task prefix TASK-101)"));
        previewLabel = new JLabel();
        previewLabel.setBorder(JBUI.Borders.empty(4, 8));
        updatePreview();
        previewBox.add(previewLabel, BorderLayout.CENTER);
        panel.add(previewBox, gbc);

        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updatePreview(); }
            @Override public void removeUpdate(DocumentEvent e) { updatePreview(); }
            @Override public void changedUpdate(DocumentEvent e) { updatePreview(); }
        };
        sourceBranchField.getDocument().addDocumentListener(listener);
        targetBranchField.getDocument().addDocumentListener(listener);
        suffixField.getDocument().addDocumentListener(listener);

        return panel;
    }

    private void updatePreview() {
        String suffix = suffixField.getText().trim();
        String target = targetBranchField.getText().trim();
        String source = sourceBranchField.getText().trim();
        previewLabel.setText("<html>From <b>" + (source.isEmpty() ? "&lt;source&gt;" : source) +
                "</b> ➔ <b>TASK-101" + suffix + "</b> (MR target: <b>" +
                (target.isEmpty() ? "&lt;target&gt;" : target) + "</b>)</html>");
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String source = sourceBranchField.getText().trim();
        if (source.isEmpty()) {
            return new ValidationInfo("Source tracking branch cannot be empty (e.g. origin/deploy/dev)", sourceBranchField);
        }
        String target = targetBranchField.getText().trim();
        if (target.isEmpty()) {
            return new ValidationInfo("Merge Request target branch cannot be empty (e.g. deploy/dev)", targetBranchField);
        }
        String suffix = suffixField.getText().trim();
        if (suffix.isEmpty()) {
            return new ValidationInfo("Branch suffix cannot be empty (e.g. -dev)", suffixField);
        }
        if (suffix.contains(" ") || suffix.contains("..") || suffix.contains("~") ||
                suffix.contains("^") || suffix.contains(":") || suffix.contains("?") ||
                suffix.contains("*") || suffix.contains("[") || suffix.contains("\\")) {
            return new ValidationInfo("Branch suffix contains invalid Git branch characters", suffixField);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        result = new BranchMapping(
                sourceBranchField.getText().trim(),
                targetBranchField.getText().trim(),
                suffixField.getText().trim(),
                enabledCheckbox.isSelected()
        );
        super.doOKAction();
    }

    public @Nullable BranchMapping getBranchMapping() {
        return result;
    }
}
