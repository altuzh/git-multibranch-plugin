package git.multibranch;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated logger and log manager for the Multi-Branch Git plugin.
 * Provides persistent file logging for troubleshooting, in-memory execution capture,
 * and support for opening log directory and copying logs to clipboard.
 */
public class MultiBranchLog {
    public static final String LOG_DIR_NAME = "git-multibranch";
    public static final String LOG_FILE_NAME = "git-multibranch.log";
    private static final long MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final int MAX_ROTATED_FILES = 3;
    private static final int MAX_BUFFER_CHARS = 300_000;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final StringBuilder currentExecutionBuffer = new StringBuilder();
    private static long executionStartTime = 0;

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    /**
     * Sanitizes sensitive information such as GitLab personal access tokens or bearer tokens from log messages.
     */
    public static String sanitize(@Nullable String text) {
        if (text == null) return "";
        // Mask explicit GitLab personal access tokens
        String s = text.replaceAll("(?i)glpat-[a-zA-Z0-9_\\-]{6,}", "glpat-***MASKED***");
        // Mask Authorization Bearer tokens
        s = s.replaceAll("(?i)(Bearer\\s+)[a-zA-Z0-9_\\-\\.]+", "$1***MASKED***");
        // Mask PRIVATE-TOKEN headers
        s = s.replaceAll("(?i)(PRIVATE-TOKEN[:=]\\s*)[^\\s\\r\\n]+", "$1***MASKED***");
        // Mask basic auth or credentials in URLs
        s = s.replaceAll("(?i)(https?://[^:]+:)[^@]+(@)", "$1***MASKED***$2");
        return s;
    }

    private static File testLogDirectory = null;

    public static void setTestLogDirectory(@Nullable File dir) {
        testLogDirectory = dir;
    }

    /**
     * Returns the dedicated log directory for the plugin.
     * Uses IntelliJ's standard log directory (PathManager.getLogPath() / git-multibranch) when available,
     * or falls back to user home directory or tmpdir if running outside IDE runtime (e.g. unit tests).
     */
    @NotNull
    public static File getLogDirectory() {
        if (testLogDirectory != null) {
            if (!testLogDirectory.exists()) {
                testLogDirectory.mkdirs();
            }
            return testLogDirectory;
        }

        File dir = null;
        try {
            String ideaLog = PathManager.getLogPath();
            if (ideaLog != null && !ideaLog.isBlank()) {
                File candidate = new File(ideaLog, LOG_DIR_NAME);
                if (candidate.exists() || candidate.mkdirs()) {
                    dir = candidate;
                }
            }
        } catch (Throwable ignored) {}

        if (dir == null) {
            File userHome = new File(System.getProperty("user.home"), ".git-multibranch" + File.separator + "logs");
            if (userHome.exists() || userHome.mkdirs()) {
                dir = userHome;
            } else {
                dir = new File(System.getProperty("java.io.tmpdir"), "git-multibranch-logs");
                dir.mkdirs();
            }
        }

        return dir;
    }

    /**
     * Returns the active log file (git-multibranch.log).
     */
    @NotNull
    public static File getLogFile() {
        return new File(getLogDirectory(), LOG_FILE_NAME);
    }

    /**
     * Opens the log directory in the operating system's native file explorer.
     */
    public static boolean openLogDirectory() {
        File dir = getLogDirectory();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Try Desktop API
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir);
                return true;
            }
        } catch (Throwable ignored) {}

        // Try RevealFileAction if running inside IntelliJ
        try {
            Class<?> revealClass = Class.forName("com.intellij.ide.actions.RevealFileAction");
            revealClass.getMethod("openDirectory", File.class).invoke(null, dir);
            return true;
        } catch (Throwable ignored) {}

        // Fallback to platform-specific commands
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
                return true;
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", dir.getAbsolutePath()).start();
                return true;
            } else {
                new ProcessBuilder("xdg-open", dir.getAbsolutePath()).start();
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }

    public static void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    public static void info(String message) {
        log(Level.INFO, message, null);
    }

    public static void warn(String message) {
        log(Level.WARN, message, null);
    }

    public static void warn(String message, @Nullable Throwable t) {
        log(Level.WARN, message, t);
    }

    public static void error(String message) {
        log(Level.ERROR, message, null);
    }

    public static void error(String message, @Nullable Throwable t) {
        log(Level.ERROR, message, t);
    }

    /**
     * Specialized logging for executed Git commands.
     */
    public static void logGitCommand(@Nullable File workingDir, String[] args, int exitCode, long durationMs, String stdout, String stderr) {
        String cmdStr = "git " + String.join(" ", args);
        String cwd = workingDir != null ? workingDir.getName() : "null";

        if (exitCode == 0) {
            String outSnippet = stdout != null ? stdout.trim() : "";
            if (outSnippet.length() > 300) {
                outSnippet = outSnippet.substring(0, 300) + "... [" + outSnippet.length() + " chars total]";
            }
            String msg = String.format("Git [%s, %dms] %s%s", cwd, durationMs, cmdStr,
                    outSnippet.isEmpty() ? "" : " -> " + outSnippet.replace("\n", " | "));
            log(Level.INFO, msg, null);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Git FAILED (exit %d, %dms) [%s] %s\n", exitCode, durationMs, cwd, cmdStr));
            if (stderr != null && !stderr.isBlank()) {
                sb.append("  stderr: ").append(stderr.trim()).append("\n");
            }
            if (stdout != null && !stdout.isBlank()) {
                sb.append("  stdout: ").append(stdout.trim());
            }
            log(Level.WARN, sb.toString().trim(), null);
        }
    }

    /**
     * Specialized logging for GitLab API HTTP calls.
     */
    public static void logApiCall(String method, String url, int statusCode, @Nullable String responseSummary, long durationMs) {
        String sanitizedUrl = sanitize(url);
        String msg = String.format("GitLab API %s %s -> HTTP %d (%dms)%s",
                method, sanitizedUrl, statusCode, durationMs,
                responseSummary != null && !responseSummary.isBlank() ? " | " + sanitize(responseSummary) : "");
        if (statusCode >= 200 && statusCode < 300) {
            log(Level.INFO, msg, null);
        } else {
            log(Level.WARN, msg, null);
        }
    }

    /**
     * Signals the start of a Multi-Branch operation and resets the current execution buffer.
     */
    public static synchronized void startExecution(String title) {
        executionStartTime = System.currentTimeMillis();
        currentExecutionBuffer.setLength(0);

        String header = String.format(
                "================================================================================\n" +
                "=== Multi-Branch Execution Started: %s ===\n" +
                "=== Operation: %s\n" +
                "=== Plugin Version: %s | OS: %s (%s) | Java: %s\n" +
                "================================================================================",
                LocalDateTime.now().format(TIME_FORMATTER),
                title,
                MultiBranchReloadAction.getRunningVersion(),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getProperty("java.version")
        );
        info(header);
    }

    /**
     * Signals the completion of a Multi-Branch operation.
     */
    public static synchronized void finishExecution(String summary) {
        long elapsed = executionStartTime > 0 ? (System.currentTimeMillis() - executionStartTime) : 0;
        String footer = String.format(
                "================================================================================\n" +
                "=== Multi-Branch Execution Finished in %d ms ===\n" +
                "=== %s\n" +
                "================================================================================",
                elapsed, summary
        );
        info(footer);
    }

    /**
     * Dispatches a log entry to memory buffer, persistent log file, and IntelliJ diagnostic logger.
     */
    private static void log(Level level, String message, @Nullable Throwable t) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String threadName = Thread.currentThread().getName();
        String safeMsg = sanitize(message);

        StringBuilder lineBuilder = new StringBuilder();
        lineBuilder.append(timestamp)
                .append(" [")
                .append(String.format("%-5s", level.name()))
                .append("] [")
                .append(threadName)
                .append("] ")
                .append(safeMsg);

        if (t != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            lineBuilder.append("\n").append(sw);
        }

        String formattedLine = lineBuilder.toString();

        // 1. In-memory execution buffer
        appendToBuffer(formattedLine);

        // 2. Persistent file
        appendToFile(formattedLine);

        // 3. IntelliJ diagnostic Logger
        logToIdeaLogger(level, safeMsg, t);
    }

    private static synchronized void appendToBuffer(String line) {
        if (currentExecutionBuffer.length() > MAX_BUFFER_CHARS) {
            currentExecutionBuffer.delete(0, MAX_BUFFER_CHARS / 3);
            currentExecutionBuffer.insert(0, "[... earlier log buffer truncated ...]\n");
        }
        currentExecutionBuffer.append(line).append("\n");
    }

    private static synchronized void appendToFile(String formattedLine) {
        try {
            File logFile = getLogFile();
            rotateLogIfNeeded(logFile);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
                writer.write(formattedLine);
                writer.newLine();
                writer.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void rotateLogIfNeeded(File logFile) {
        if (!logFile.exists() || logFile.length() < MAX_LOG_SIZE_BYTES) {
            return;
        }
        try {
            for (int i = MAX_ROTATED_FILES - 1; i >= 1; i--) {
                File src = new File(logFile.getParentFile(), LOG_FILE_NAME + "." + i);
                File dest = new File(logFile.getParentFile(), LOG_FILE_NAME + "." + (i + 1));
                if (src.exists()) {
                    if (dest.exists()) dest.delete();
                    src.renameTo(dest);
                }
            }
            File firstBackup = new File(logFile.getParentFile(), LOG_FILE_NAME + ".1");
            if (firstBackup.exists()) firstBackup.delete();
            logFile.renameTo(firstBackup);
        } catch (Throwable ignored) {
        }
    }

    private static void logToIdeaLogger(Level level, String safeMsg, @Nullable Throwable t) {
        try {
            Logger ideaLogger = Logger.getInstance("git.multibranch.MultiBranchPlugin");
            if (ideaLogger != null) {
                switch (level) {
                    case ERROR -> {
                        if (t != null) ideaLogger.error(safeMsg, t);
                        else ideaLogger.error(safeMsg);
                    }
                    case WARN -> ideaLogger.warn(safeMsg);
                    case DEBUG -> ideaLogger.debug(safeMsg);
                    default -> ideaLogger.info(safeMsg);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Returns the formatted diagnostic text suitable for copying to clipboard.
     */
    public static synchronized String getClipboardLogText(@Nullable List<MultiBranchResultItem> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("================================================================================\n");
        sb.append("MULTI-BRANCH WORKFLOW EXECUTION LOG\n");
        sb.append("================================================================================\n");
        sb.append("Plugin Version: ").append(MultiBranchReloadAction.getRunningVersion()).append("\n");
        sb.append("Timestamp:      ").append(LocalDateTime.now().format(TIME_FORMATTER)).append("\n");
        sb.append("Log Directory:  ").append(getLogDirectory().getAbsolutePath()).append("\n");
        sb.append("Log File:       ").append(getLogFile().getAbsolutePath()).append("\n");
        sb.append("OS:             ").append(System.getProperty("os.name")).append(" ").append(System.getProperty("os.version")).append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("Java:           ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");

        if (results != null && !results.isEmpty()) {
            long succeeded = results.stream().filter(MultiBranchResultItem::isSuccess).count();
            long failed = results.size() - succeeded;
            long pushed = results.stream().filter(MultiBranchResultItem::isPushed).count();
            long mrs = results.stream().filter(r -> r.getMrUrl() != null && !r.getMrUrl().isBlank()).count();

            sb.append("\nExecution Results Summary:\n");
            sb.append("  Total Branches: ").append(results.size()).append("\n");
            sb.append("  Succeeded:      ").append(succeeded).append("\n");
            sb.append("  Failed:         ").append(failed).append("\n");
            sb.append("  Pushed:         ").append(pushed).append("\n");
            sb.append("  MRs / PRs:      ").append(mrs).append("\n");

            sb.append("\nBranch Details:\n");
            for (MultiBranchResultItem item : results) {
                sb.append("  * ").append(item.getBranchName()).append(" -> ").append(item.getTargetBranch());
                sb.append(" [").append(item.isSuccess() ? "SUCCESS" : "FAILED").append("]\n");
                if (item.getCommitHash() != null && !item.getCommitHash().isBlank()) {
                    sb.append("      Commit: ").append(item.getCommitHash()).append("\n");
                }
                if (item.getPushDetails() != null && !item.getPushDetails().isBlank()) {
                    sb.append("      Push:   ").append(item.getPushDetails()).append("\n");
                }
                if (item.getMrUrl() != null && !item.getMrUrl().isBlank()) {
                    sb.append("      MR URL: ").append(item.getMrUrl()).append("\n");
                }
                if (item.getErrorMessage() != null && !item.getErrorMessage().isBlank()) {
                    sb.append("      Error:  ").append(item.getErrorMessage().replace("\n", "\n              ")).append("\n");
                }
            }
        }

        sb.append("\n================================================================================\n");
        sb.append("DETAILED EXECUTION TRACE\n");
        sb.append("================================================================================\n");

        if (currentExecutionBuffer.length() > 0) {
            sb.append(currentExecutionBuffer);
        } else {
            sb.append(getRecentLogFileExcerpt(300));
        }

        return sb.toString();
    }

    /**
     * Reads the last N lines from the active log file if the in-memory execution buffer is empty.
     */
    public static String getRecentLogFileExcerpt(int maxLines) {
        File logFile = getLogFile();
        if (!logFile.exists()) {
            return "(No persistent log file entries found on disk yet.)\n";
        }
        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                    if (lines.size() > maxLines * 2) {
                        lines.subList(0, lines.size() - maxLines).clear();
                    }
                }
            }
            int start = Math.max(0, lines.size() - maxLines);
            return String.join("\n", lines.subList(start, lines.size())) + "\n";
        } catch (Exception e) {
            return "(Failed to read log file: " + e.getMessage() + ")\n";
        }
    }

    public static synchronized String getLastExecutionBuffer() {
        return currentExecutionBuffer.toString();
    }

    public static synchronized void clearExecutionBuffer() {
        currentExecutionBuffer.setLength(0);
    }
}
