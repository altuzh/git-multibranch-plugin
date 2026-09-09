package git.multibranch;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.event.MouseEvent;

public class MultiBranchStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation, StatusBarWidget.Multiframe {
    public static final String ID = "git.multibranch.widget";
    private final Project project;
    private StatusBar statusBar;

    public MultiBranchStatusBarWidget(Project project) {
        this.project = project;
    }

    @Override
    public @NotNull @NonNls String ID() {
        return ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @NotNull String getText() {
        MultiBranchSettings settings = MultiBranchSettings.getInstance(project);
        int count = (settings != null && settings.getState().branchMappings != null)
                ? settings.getState().branchMappings.size()
                : 4;
        return "Multi-Branch (" + count + ")";
    }

    @Override
    public @Nullable String getTooltipText() {
        return "Multi-Branch Commit & Push across configured branches";
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return mouseEvent -> MultiBranchCommitAction.trigger(project);
    }

    @Override
    public float getAlignment() {
        return Component.CENTER_ALIGNMENT;
    }

    @Override
    public @NotNull StatusBarWidget copy() {
        return new MultiBranchStatusBarWidget(project);
    }

    @Override
    public void dispose() {
        statusBar = null;
    }
}
