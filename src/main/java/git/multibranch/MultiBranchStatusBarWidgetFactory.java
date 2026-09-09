package git.multibranch;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class MultiBranchStatusBarWidgetFactory implements StatusBarWidgetFactory {
    @Override
    public @NotNull @NonNls String getId() {
        return MultiBranchStatusBarWidget.ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Multi-Branch Git Actions";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return !GitRepositoryManager.getInstance(project).getRepositories().isEmpty();
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new MultiBranchStatusBarWidget(project);
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        widget.dispose();
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }
}
