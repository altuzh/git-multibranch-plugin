package git.multibranch;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class BranchMappingsTablePanel extends JPanel {
    private final Project project;
    private final List<BranchMapping> mappings = new ArrayList<>();
    private final BranchTableModel tableModel;
    private final JBTable table;

    public BranchMappingsTablePanel(@Nullable Project project) {
        super(new BorderLayout(0, JBUI.scale(6)));
        this.project = project;

        tableModel = new BranchTableModel();
        table = new JBTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setRowHeight(JBUI.scale(24));

        // Adjust column widths
        TableColumn enabledCol = table.getColumnModel().getColumn(0);
        enabledCol.setPreferredWidth(JBUI.scale(60));
        enabledCol.setMaxWidth(JBUI.scale(80));
        enabledCol.setMinWidth(JBUI.scale(50));

        // Double click to edit row
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull MouseEvent event) {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    editRow(row);
                    return true;
                }
                return false;
            }
        }.installOn(table);

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction(button -> addRow())
                .setEditAction(button -> {
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        editRow(row);
                    }
                })
                .setRemoveAction(button -> removeSelectedRow())
                .setMoveUpAction(button -> moveRowUp())
                .setMoveDownAction(button -> moveRowDown());

        JPanel decoratedPanel = decorator.createPanel();
        add(decoratedPanel, BorderLayout.CENTER);

        // Bottom actions: Reset to Defaults
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        JButton resetBtn = new JButton("Reset to Defaults");
        resetBtn.setToolTipText("Restore standard branch mappings (dev, test, uat, prod)");
        resetBtn.addActionListener(e -> resetToDefaults());
        bottomPanel.add(resetBtn);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void addRow() {
        BranchEditDialog dlg = project != null
                ? new BranchEditDialog(project, null)
                : new BranchEditDialog(this, null);
        if (dlg.showAndGet()) {
            BranchMapping mapping = dlg.getBranchMapping();
            if (mapping != null) {
                mappings.add(mapping);
                tableModel.fireTableRowsInserted(mappings.size() - 1, mappings.size() - 1);
                int idx = mappings.size() - 1;
                table.setRowSelectionInterval(idx, idx);
            }
        }
    }

    private void editRow(int row) {
        if (row < 0 || row >= mappings.size()) return;
        BranchMapping current = mappings.get(row);
        BranchEditDialog dlg = project != null
                ? new BranchEditDialog(project, current)
                : new BranchEditDialog(this, current);
        if (dlg.showAndGet()) {
            BranchMapping updated = dlg.getBranchMapping();
            if (updated != null) {
                mappings.set(row, updated);
                tableModel.fireTableRowsUpdated(row, row);
            }
        }
    }

    private void removeSelectedRow() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= mappings.size()) return;
        if (mappings.size() <= 1) {
            Messages.showWarningDialog(this,
                    "At least one target branch mapping must remain.",
                    "Cannot Delete Branch");
            return;
        }
        mappings.remove(row);
        tableModel.fireTableRowsDeleted(row, row);
        int nextRow = Math.min(row, mappings.size() - 1);
        if (nextRow >= 0) {
            table.setRowSelectionInterval(nextRow, nextRow);
        }
    }

    private void moveRowUp() {
        int row = table.getSelectedRow();
        if (row > 0) {
            BranchMapping item = mappings.remove(row);
            mappings.add(row - 1, item);
            tableModel.fireTableDataChanged();
            table.setRowSelectionInterval(row - 1, row - 1);
        }
    }

    private void moveRowDown() {
        int row = table.getSelectedRow();
        if (row >= 0 && row < mappings.size() - 1) {
            BranchMapping item = mappings.remove(row);
            mappings.add(row + 1, item);
            tableModel.fireTableDataChanged();
            table.setRowSelectionInterval(row + 1, row + 1);
        }
    }

    private void resetToDefaults() {
        int confirm = Messages.showYesNoDialog(this,
                "Reset branch mappings to default (dev, test, uat, prod)?",
                "Reset Branch Mappings",
                Messages.getQuestionIcon());
        if (confirm == Messages.YES) {
            mappings.clear();
            mappings.add(new BranchMapping("origin/deploy/dev", "deploy/dev", "-dev", true));
            mappings.add(new BranchMapping("origin/deploy/test", "deploy/test", "-test", true));
            mappings.add(new BranchMapping("origin/merge_to_uat", "merge_to_uat", "-uat", true));
            mappings.add(new BranchMapping("origin/merge_to_prod", "merge_to_prod", "-prod", true));
            tableModel.fireTableDataChanged();
            if (!mappings.isEmpty()) {
                table.setRowSelectionInterval(0, 0);
            }
        }
    }

    public List<BranchMapping> getMappings() {
        List<BranchMapping> copy = new ArrayList<>();
        for (BranchMapping m : mappings) {
            copy.add(m.copy());
        }
        return copy;
    }

    public void setMappings(List<BranchMapping> newMappings) {
        mappings.clear();
        if (newMappings != null) {
            for (BranchMapping m : newMappings) {
                mappings.add(m.copy());
            }
        }
        tableModel.fireTableDataChanged();
        if (!mappings.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    public boolean isModified(List<BranchMapping> original) {
        if (original == null) return !mappings.isEmpty();
        if (mappings.size() != original.size()) return true;
        for (int i = 0; i < mappings.size(); i++) {
            if (!mappings.get(i).equals(original.get(i))) {
                return true;
            }
        }
        return false;
    }

    private class BranchTableModel extends AbstractTableModel {
        private final String[] columnNames = {"Enabled", "Source Origin Branch", "Merge Target Branch", "Branch Suffix"};

        @Override
        public int getRowCount() {
            return mappings.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == 0) return Boolean.class;
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            BranchMapping mapping = mappings.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> mapping.isEnabled();
                case 1 -> mapping.getSourceOriginBranch();
                case 2 -> mapping.getTargetOriginBranchName();
                case 3 -> mapping.getBranchSuffix();
                default -> null;
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= mappings.size()) return;
            BranchMapping mapping = mappings.get(rowIndex);
            if (columnIndex == 0 && aValue instanceof Boolean b) {
                mapping.setEnabled(b);
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }
}
