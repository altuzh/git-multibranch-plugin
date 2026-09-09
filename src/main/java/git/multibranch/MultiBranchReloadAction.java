package git.multibranch;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Action to dynamically reload the Multi-Branch Git plugin from a built distribution zip or jar
 * without restarting IntelliJ IDEA.
 */
public class MultiBranchReloadAction extends AnAction {
    public static final String PLUGIN_ID_STRING = "git.multibranch";
    public static final long PLUGIN_LOAD_TIME = System.currentTimeMillis();

    public static class VersionCheckResult {
        private final boolean newer;
        private final String runningVersion;
        private final String diskVersion;
        private final File diskFile;
        private final String reason;

        public VersionCheckResult(boolean newer, String runningVersion, String diskVersion, File diskFile, String reason) {
            this.newer = newer;
            this.runningVersion = runningVersion;
            this.diskVersion = diskVersion;
            this.diskFile = diskFile;
            this.reason = reason;
        }

        public boolean isNewer() { return newer; }
        public String getRunningVersion() { return runningVersion; }
        public String getDiskVersion() { return diskVersion; }
        public File getDiskFile() { return diskFile; }
        public String getReason() { return reason; }
    }

    public static String getRunningVersion() {
        try {
            PluginId pluginId = PluginId.getId(PLUGIN_ID_STRING);
            IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(pluginId);
            if (descriptor != null && descriptor.getVersion() != null) {
                return descriptor.getVersion().trim();
            }
        } catch (Throwable ignored) {}

        try (java.io.InputStream is = MultiBranchReloadAction.class.getResourceAsStream("/META-INF/plugin.xml")) {
            if (is != null) {
                String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(content);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        } catch (Throwable ignored) {}

        return "1.1.5";
    }

    public static String getVersionFromZip(@Nullable File zipFile) {
        if (zipFile == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("git-multibranch-plugin-([0-9]+(\\.[0-9]+)*[^.]*)\\.zip").matcher(zipFile.getName());
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    public static int compareVersions(@Nullable String v1, @Nullable String v2) {
        if (v1 == null) v1 = "0";
        if (v2 == null) v2 = "0";
        String[] parts1 = v1.split("[.\\-]");
        String[] parts2 = v2.split("[.\\-]");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int p1 = 0;
            int p2 = 0;
            if (i < parts1.length) {
                try { p1 = Integer.parseInt(parts1[i].replaceAll("\\D", "")); } catch (Exception ignored) {}
            }
            if (i < parts2.length) {
                try { p2 = Integer.parseInt(parts2[i].replaceAll("\\D", "")); } catch (Exception ignored) {}
            }
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }
        return 0;
    }

    public static VersionCheckResult checkDiskVersion(@Nullable Project project) {
        File zipFile = findLatestDistributionZip(project);
        if (zipFile == null || !zipFile.exists()) {
            return new VersionCheckResult(false, getRunningVersion(), null, null, "No distribution zip found on disk");
        }

        String runningVer = getRunningVersion();
        String diskVer = getVersionFromZip(zipFile);

        if (diskVer != null) {
            int cmp = compareVersions(diskVer, runningVer);
            if (cmp > 0) {
                return new VersionCheckResult(true, runningVer, diskVer, zipFile,
                        "Disk version (" + diskVer + ") is newer than running version (" + runningVer + ")");
            }
            if (cmp == 0) {
                // Same version number: check if disk file timestamp is newer than when plugin loaded
                if (zipFile.lastModified() > PLUGIN_LOAD_TIME + 2000) {
                    return new VersionCheckResult(true, runningVer, diskVer, zipFile,
                            "Disk build (" + zipFile.getName() + ") has newer timestamp than running plugin session");
                }
            }
        } else {
            if (zipFile.lastModified() > PLUGIN_LOAD_TIME + 2000) {
                return new VersionCheckResult(true, runningVer, null, zipFile,
                        "Disk build timestamp is newer than running plugin session");
            }
        }

        return new VersionCheckResult(false, runningVer, diskVer, zipFile,
                "Running plugin (" + runningVer + ") is already up to date with disk");
    }

    public static boolean reloadIfNewer(@Nullable Project project, @Nullable JLabel statusLabel) {
        VersionCheckResult check = checkDiskVersion(project);
        if (check.isNewer() && check.getDiskFile() != null) {
            notify(project, statusLabel, "Newer build detected on disk (" + check.getReason() + "). Reloading...", NotificationType.INFORMATION);
            performReload(project, statusLabel);
            return true;
        }
        return false;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        performReload(project, null);
    }

    /**
     * Performs dynamic reload of the plugin from the latest build distribution or file chooser.
     *
     * @param project     current IntelliJ project (can be null)
     * @param statusLabel optional JLabel to report status in settings UI
     */
    public static void performReload(@Nullable Project project, @Nullable JLabel statusLabel) {
        File zipFile = findLatestDistributionZip(project);

        if (zipFile == null || !zipFile.exists()) {
            // Prompt user with file chooser
            FileChooserDescriptor fileDesc = FileChooserDescriptorFactory.createSingleFileDescriptor("zip");
            fileDesc.setTitle("Select Multi-Branch Plugin Distribution");
            fileDesc.setDescription("Select git-multibranch-plugin-*.zip to install and dynamically reload");
            VirtualFile vf = FileChooser.chooseFile(fileDesc, project, null);
            if (vf != null) {
                zipFile = new File(vf.getPath());
            }
        }

        if (zipFile == null || !zipFile.exists()) {
            notify(project, statusLabel, "No distribution zip found. Please run './gradlew buildPlugin' first.", NotificationType.WARNING);
            return;
        }

        final File targetZip = zipFile;
        notify(project, statusLabel, "Reloading from " + targetZip.getName() + "...", NotificationType.INFORMATION);

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                boolean success = reloadDynamically(targetZip);
                if (success) {
                    notify(project, statusLabel, "Multi-Branch plugin dynamically reloaded from " + targetZip.getName() + " without restart!", NotificationType.INFORMATION);
                } else {
                    notify(project, statusLabel, "Plugin reload returned false. An IDE restart may be required.", NotificationType.WARNING);
                }
            } catch (Throwable t) {
                notify(project, statusLabel, "Dynamic reload failed: " + (t.getMessage() != null ? t.getMessage() : t.toString()), NotificationType.ERROR);
            }
        });
    }

    /**
     * Calls IntelliJ's internal PluginInstaller.installAndLoadDynamicPlugin to install and reload
     * the plugin dynamically without restarting the IDE.
     */
    public static boolean reloadDynamically(@NotNull File zipOrJarFile) throws Exception {
        PluginId pluginId = PluginId.getId(PLUGIN_ID_STRING);

        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(pluginId);
        if (descriptor == null) {
            try {
                Method findPlugin = PluginManagerCore.class.getMethod("findPlugin", PluginId.class);
                descriptor = (IdeaPluginDescriptor) findPlugin.invoke(null, pluginId);
            } catch (Throwable ignored) {
            }
        }

        if (descriptor == null) {
            throw new IllegalStateException("Plugin '" + PLUGIN_ID_STRING + "' is not registered in PluginManager.");
        }

        Class<?> installerClass = Class.forName("com.intellij.ide.plugins.PluginInstaller");
        Path filePath = zipOrJarFile.toPath();

        // Search for installAndLoadDynamicPlugin methods
        for (Method m : installerClass.getMethods()) {
            if ("installAndLoadDynamicPlugin".equals(m.getName())) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2 && Path.class.isAssignableFrom(params[0])) {
                    Object result = m.invoke(null, filePath, descriptor);
                    return Boolean.TRUE.equals(result);
                } else if (params.length == 3 && Path.class.isAssignableFrom(params[0])) {
                    Object result = m.invoke(null, filePath, null, descriptor);
                    return Boolean.TRUE.equals(result);
                }
            }
        }

        throw new UnsupportedOperationException("installAndLoadDynamicPlugin method not found in PluginInstaller.");
    }

    /**
     * Searches common build output locations for the latest git-multibranch-plugin-*.zip
     */
    @Nullable
    public static File findLatestDistributionZip(@Nullable Project project) {
        List<File> searchDirs = new ArrayList<>();
        if (project != null && project.getBasePath() != null) {
            searchDirs.add(new File(project.getBasePath(), "build/distributions"));
            searchDirs.add(new File(project.getBasePath(), "git-multibranch-plugin/build/distributions"));
        }
        searchDirs.add(new File("C:/Users/al/projects/git-multibranch-plugin/build/distributions"));

        File newest = null;
        long newestTime = -1;

        for (File dir : searchDirs) {
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles((d, name) -> name.startsWith("git-multibranch-plugin") && name.endsWith(".zip"));
                if (files != null) {
                    for (File f : files) {
                        if (f.lastModified() > newestTime) {
                            newestTime = f.lastModified();
                            newest = f;
                        }
                    }
                }
            }
        }
        return newest;
    }

    private static void notify(@Nullable Project project, @Nullable JLabel statusLabel, String message, NotificationType type) {
        if (statusLabel != null) {
            statusLabel.setText(message);
        }
        try {
            Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup("MultiBranchPlugin")
                    .createNotification(message, type);
            if (type == NotificationType.WARNING && message.toLowerCase().contains("restart")) {
                notification.addAction(NotificationAction.createSimpleExpiring("Restart IDE", () -> {
                    try {
                        Class<?> appExClass = Class.forName("com.intellij.openapi.application.ex.ApplicationManagerEx");
                        Object appEx = appExClass.getMethod("getApplicationEx").invoke(null);
                        appEx.getClass().getMethod("restart", boolean.class).invoke(appEx, true);
                    } catch (Throwable t) {
                        try {
                            ApplicationManager.getApplication().getClass().getMethod("restart").invoke(ApplicationManager.getApplication());
                        } catch (Throwable ignored) {}
                    }
                }));
            }
            notification.notify(project);
        } catch (Throwable ignored) {
        }
    }
}
