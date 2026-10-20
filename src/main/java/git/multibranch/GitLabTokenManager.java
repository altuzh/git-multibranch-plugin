package git.multibranch;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GitLabTokenManager {
    public static final String SERVICE_NAME = "git.multibranch.GitLab";
    public static final String LEGACY_ACCOUNT_NAME = "GitLabToken";

    // In-memory fallback used only when PasswordSafe is unavailable (e.g. during unit tests)
    static final Map<String, String> testFallbackStorage = new ConcurrentHashMap<>();

    public static String normalizeHost(@Nullable String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return "";
        }
        String s = rawHost.trim().toLowerCase(Locale.ROOT);

        // Git SSH URL: git@gitlab.example.com:group/project.git
        if (s.startsWith("git@") || (s.contains("@") && !s.startsWith("http://") && !s.startsWith("https://") && !s.startsWith("ssh://"))) {
            int atIdx = s.indexOf('@');
            int colonIdx = s.indexOf(':', atIdx);
            if (colonIdx > atIdx) {
                s = s.substring(atIdx + 1, colonIdx);
            } else {
                s = s.substring(atIdx + 1);
                int slashIdx = s.indexOf('/');
                if (slashIdx > 0) {
                    s = s.substring(0, slashIdx);
                }
            }
        } else if (s.startsWith("ssh://")) {
            String withoutScheme = s.substring(6);
            int slashIdx = withoutScheme.indexOf('/');
            if (slashIdx > 0) {
                withoutScheme = withoutScheme.substring(0, slashIdx);
            }
            if (withoutScheme.contains("@")) {
                withoutScheme = withoutScheme.substring(withoutScheme.indexOf('@') + 1);
            }
            if (withoutScheme.contains(":")) {
                withoutScheme = withoutScheme.substring(0, withoutScheme.indexOf(':'));
            }
            s = withoutScheme;
        } else if (s.startsWith("http://") || s.startsWith("https://")) {
            try {
                URI uri = URI.create(s);
                String h = uri.getHost();
                if (h != null) {
                    if (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443) {
                        s = h + ":" + uri.getPort();
                    } else {
                        s = h;
                    }
                } else {
                    int slashSlash = s.indexOf("://");
                    s = s.substring(slashSlash + 3);
                    int slashIdx = s.indexOf('/');
                    if (slashIdx > 0) s = s.substring(0, slashIdx);
                }
            } catch (Exception ignored) {
                int slashSlash = s.indexOf("://");
                s = s.substring(slashSlash + 3);
                int slashIdx = s.indexOf('/');
                if (slashIdx > 0) s = s.substring(0, slashIdx);
            }
        } else {
            int slashIdx = s.indexOf('/');
            if (slashIdx > 0) {
                s = s.substring(0, slashIdx);
            }
        }

        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.trim();
    }

    public static String getAccountName(@Nullable String host) {
        String normalized = normalizeHost(host);
        if (normalized.isEmpty()) {
            return LEGACY_ACCOUNT_NAME;
        }
        return "GitLabToken_" + normalized;
    }

    private static CredentialAttributes createCredentialAttributes(String accountName) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SERVICE_NAME, accountName));
    }

    public static String detectHost(@Nullable Project project, @Nullable String hostOverride) {
        if (hostOverride != null && !hostOverride.isBlank()) {
            return hostOverride.trim();
        }
        if (project != null) {
            try {
                Collection<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
                if (repos != null && !repos.isEmpty()) {
                    GitRepository repo = repos.iterator().next();
                    for (GitRemote remote : repo.getRemotes()) {
                        if ("origin".equalsIgnoreCase(remote.getName())) {
                            String url = remote.getFirstUrl();
                            if (url != null && !url.isBlank()) {
                                GitLabApiService.GitLabProjectInfo info = GitLabApiService.parseProjectInfo(url, null);
                                if (info != null && info.getHostUrl() != null) {
                                    return info.getHostUrl();
                                }
                            }
                        }
                    }
                    for (GitRemote remote : repo.getRemotes()) {
                        String url = remote.getFirstUrl();
                        if (url != null && !url.isBlank()) {
                            GitLabApiService.GitLabProjectInfo info = GitLabApiService.parseProjectInfo(url, null);
                            if (info != null && info.getHostUrl() != null) {
                                return info.getHostUrl();
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            if (project.getBasePath() != null) {
                return detectHost(new File(project.getBasePath()), null);
            }
        }
        return "";
    }

    public static String detectHost(@Nullable File repoDir, @Nullable String hostOverride) {
        if (hostOverride != null && !hostOverride.isBlank()) {
            return hostOverride.trim();
        }
        if (repoDir != null && repoDir.exists()) {
            String remoteUrl = GitLabApiService.getOriginRemoteUrl(repoDir);
            GitLabApiService.GitLabProjectInfo info = GitLabApiService.parseProjectInfo(remoteUrl, null);
            if (info != null && info.getHostUrl() != null) {
                return info.getHostUrl();
            }
        }
        return "";
    }

    public static String getToken(@Nullable String host) {
        String normalized = normalizeHost(host);
        try {
            PasswordSafe safe = PasswordSafe.getInstance();
            if (!normalized.isEmpty()) {
                String token = safe.getPassword(createCredentialAttributes("GitLabToken_" + normalized));
                if (token != null && !token.isBlank()) {
                    return token.trim();
                }
            }
            // Fallback to legacy single-token storage
            String legacy = safe.getPassword(createCredentialAttributes(LEGACY_ACCOUNT_NAME));
            if (legacy != null && !legacy.isBlank()) {
                return legacy.trim();
            }
        } catch (Throwable t) {
            // PasswordSafe not available (e.g. running in unit tests)
            if (!normalized.isEmpty()) {
                String token = testFallbackStorage.get("GitLabToken_" + normalized);
                if (token != null && !token.isBlank()) {
                    return token.trim();
                }
            }
            String legacy = testFallbackStorage.get(LEGACY_ACCOUNT_NAME);
            if (legacy != null && !legacy.isBlank()) {
                return legacy.trim();
            }
        }
        return null;
    }

    public static String getToken() {
        return getToken(null);
    }

    public static void setToken(@Nullable String host, @Nullable String token) {
        String normalized = normalizeHost(host);
        String cleanToken = (token != null && !token.isBlank()) ? token.trim() : null;
        try {
            PasswordSafe safe = PasswordSafe.getInstance();
            if (!normalized.isEmpty()) {
                CredentialAttributes attributes = createCredentialAttributes("GitLabToken_" + normalized);
                safe.setPassword(attributes, cleanToken);
                // Clean up legacy token if it matches or if token is explicitly being removed,
                // preventing credentials from leaking to other hosts.
                try {
                    CredentialAttributes legacyAttr = createCredentialAttributes(LEGACY_ACCOUNT_NAME);
                    String legacy = safe.getPassword(legacyAttr);
                    if (legacy != null && (cleanToken == null || legacy.equals(cleanToken))) {
                        safe.setPassword(legacyAttr, null);
                    }
                } catch (Throwable ignored) {}
            } else {
                CredentialAttributes attributes = createCredentialAttributes(LEGACY_ACCOUNT_NAME);
                safe.setPassword(attributes, cleanToken);
            }
        } catch (Throwable t) {
            // PasswordSafe not available (e.g. running in unit tests)
            String account = normalized.isEmpty() ? LEGACY_ACCOUNT_NAME : "GitLabToken_" + normalized;
            if (cleanToken != null) {
                testFallbackStorage.put(account, cleanToken);
            } else {
                testFallbackStorage.remove(account);
            }
            String legacy = testFallbackStorage.get(LEGACY_ACCOUNT_NAME);
            if (legacy != null && (cleanToken == null || legacy.equals(cleanToken))) {
                testFallbackStorage.remove(LEGACY_ACCOUNT_NAME);
            }
        }
    }

    public static void setToken(@Nullable String token) {
        setToken(null, token);
    }
}
