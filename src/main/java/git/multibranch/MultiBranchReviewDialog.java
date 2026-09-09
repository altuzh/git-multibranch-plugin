package git.multibranch;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Dialog displaying review and summary after multi-branch operations complete.
 */
public class MultiBranchReviewDialog extends MultiBranchResultDialog {
    public MultiBranchReviewDialog(@Nullable Project project, List<MultiBranchResultItem> results) {
        super(project, results);
    }
}
