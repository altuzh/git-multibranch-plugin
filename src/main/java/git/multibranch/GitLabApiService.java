package git.multibranch;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitLabApiService {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static class GitLabProjectInfo {
        private final String hostUrl;
        private final String projectPath;
        private final String encodedPath;
        private final String apiUrl;
        private final String webProjectUrl;

        public GitLabProjectInfo(String hostUrl, String projectPath, String encodedPath, String apiUrl, String webProjectUrl) {
            this.hostUrl = hostUrl;
            this.projectPath = projectPath;
            this.encodedPath = encodedPath;
            this.apiUrl = apiUrl;
            this.webProjectUrl = webProjectUrl;
        }

        public String getHostUrl() { return hostUrl; }
        public String getProjectPath() { return projectPath; }
        public String getEncodedPath() { return encodedPath; }
        public String getApiUrl() { return apiUrl; }
        public String getWebProjectUrl() { return webProjectUrl; }
    }

    public static class GitLabUser {
        private final int id;
        private final String username;
        private final String name;

        public GitLabUser(int id, String username, String name) {
            this.id = id;
            this.username = username;
            this.name = name;
        }

        public int getId() { return id; }
        public String getUsername() { return username; }
        public String getName() { return name; }
    }

    public static class MrResult {
        private final boolean success;
        private final String webUrl;
        private final int iid;
        private final String message;
        private final boolean existing;

        public MrResult(boolean success, String webUrl, int iid, String message, boolean existing) {
            this.success = success;
            this.webUrl = webUrl;
            this.iid = iid;
            this.message = message;
            this.existing = existing;
        }

        public static MrResult success(String webUrl, int iid, String message, boolean existing) {
            return new MrResult(true, webUrl, iid, message, existing);
        }

        public static MrResult error(String message) {
            return new MrResult(false, null, 0, message, false);
        }

        public boolean isSuccess() { return success; }
        public String getWebUrl() { return webUrl; }
        public int getIid() { return iid; }
        public String getMessage() { return message; }
        public boolean isExisting() { return existing; }
    }

    public static GitLabProjectInfo parseProjectInfo(String remoteUrl, String hostOverride) {
        if (remoteUrl == null || remoteUrl.isBlank()) return null;
        String raw = remoteUrl.trim();

        String host = null;
        String path = null;

        if (raw.startsWith("git@") || (raw.contains("@") && !raw.startsWith("http://") && !raw.startsWith("https://") && !raw.startsWith("ssh://"))) {
            // git@gitlab.example.com:group/project.git
            int atIdx = raw.indexOf("@");
            int colonIdx = raw.indexOf(":", atIdx);
            if (colonIdx > atIdx) {
                host = "https://" + raw.substring(atIdx + 1, colonIdx);
                path = raw.substring(colonIdx + 1);
            }
        } else if (raw.startsWith("ssh://")) {
            // ssh://git@gitlab.example.com:2222/group/project.git
            String withoutScheme = raw.substring(6);
            int slashIdx = withoutScheme.indexOf("/");
            if (slashIdx > 0) {
                String hostPart = withoutScheme.substring(0, slashIdx);
                if (hostPart.contains("@")) {
                    hostPart = hostPart.substring(hostPart.indexOf("@") + 1);
                }
                if (hostPart.contains(":")) {
                    hostPart = hostPart.substring(0, hostPart.indexOf(":"));
                }
                host = "https://" + hostPart;
                path = withoutScheme.substring(slashIdx + 1);
            }
        } else if (raw.startsWith("http://") || raw.startsWith("https://")) {
            // https://gitlab.example.com/group/project.git
            try {
                URI uri = URI.create(raw);
                host = uri.getScheme() + "://" + uri.getHost();
                if (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443) {
                    host += ":" + uri.getPort();
                }
                path = uri.getPath();
            } catch (Exception ignored) {}
        }

        if (host == null || path == null) return null;

        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
        path = path.trim();

        if (hostOverride != null && !hostOverride.isBlank()) {
            String ho = hostOverride.trim();
            if (ho.endsWith("/")) ho = ho.substring(0, ho.length() - 1);
            if (!ho.startsWith("http://") && !ho.startsWith("https://")) {
                ho = "https://" + ho;
            }
            host = ho;
        }

        String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8);
        String apiUrl = host + "/api/v4";
        String webProjectUrl = host + "/" + path;

        return new GitLabProjectInfo(host, path, encodedPath, apiUrl, webProjectUrl);
    }

    public static GitLabUser getCurrentUser(String apiUrl, String token) {
        if (apiUrl == null || token == null || token.isBlank()) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/user"))
                    .timeout(Duration.ofSeconds(10))
                    .header("PRIVATE-TOKEN", token.trim())
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                String body = response.body();
                int id = extractIntJson(body, "id");
                String username = extractStringJson(body, "username");
                String name = extractStringJson(body, "name");
                if (id > 0) {
                    return new GitLabUser(id, username, name);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static String testConnection(String apiUrl, String token) {
        if (apiUrl == null || apiUrl.isBlank()) return "GitLab API URL is required.";
        if (token == null || token.isBlank()) return "GitLab API Token is required.";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/user"))
                    .timeout(Duration.ofSeconds(10))
                    .header("PRIVATE-TOKEN", token.trim())
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                String body = response.body();
                String username = extractStringJson(body, "username");
                String name = extractStringJson(body, "name");
                int id = extractIntJson(body, "id");
                return "Connection successful! Authenticated as " + (name != null ? name : username) + " (@" + username + ", ID: " + id + ")";
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                return "Authentication failed (HTTP " + response.statusCode() + "): Invalid or expired Personal Access Token.";
            } else {
                return "GitLab server returned HTTP " + response.statusCode() + ": " + response.body();
            }
        } catch (Exception ex) {
            return "Connection error: " + ex.getMessage();
        }
    }

    public static MrResult createOrFindMergeRequest(
            File repoDir,
            String hostOverride,
            String token,
            String sourceBranch,
            String targetBranch,
            String title,
            String description,
            Integer assigneeId,
            boolean removeSourceBranch,
            boolean squash
    ) {
        String remoteUrl = getOriginRemoteUrl(repoDir);
        GitLabProjectInfo info = parseProjectInfo(remoteUrl, hostOverride);
        if (info == null) {
            return MrResult.error("Unable to parse GitLab project from git origin URL: " + remoteUrl);
        }

        // 1. Check if an open MR already exists for this source -> target branch
        try {
            String checkUrl = info.getApiUrl() + "/projects/" + info.getEncodedPath() + "/merge_requests"
                    + "?source_branch=" + URLEncoder.encode(sourceBranch, StandardCharsets.UTF_8)
                    + "&target_branch=" + URLEncoder.encode(targetBranch, StandardCharsets.UTF_8)
                    + "&state=opened";

            HttpRequest checkReq = HttpRequest.newBuilder()
                    .uri(URI.create(checkUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("PRIVATE-TOKEN", token.trim())
                    .GET()
                    .build();

            HttpResponse<String> checkResp = HTTP_CLIENT.send(checkReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (checkResp.statusCode() == 200) {
                String body = checkResp.body().trim();
                if (body.startsWith("[") && body.length() > 2) {
                    int iid = extractIntJson(body, "iid");
                    String webUrl = extractStringJson(body, "web_url");
                    if (webUrl != null && !webUrl.isBlank()) {
                        return MrResult.success(webUrl, iid, "Existing open MR", true);
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. Create new MR
        try {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"source_branch\":").append(escapeJson(sourceBranch)).append(",");
            json.append("\"target_branch\":").append(escapeJson(targetBranch)).append(",");
            json.append("\"title\":").append(escapeJson(title != null ? title : sourceBranch)).append(",");
            json.append("\"description\":").append(escapeJson(description != null ? description : "")).append(",");
            json.append("\"remove_source_branch\":").append(removeSourceBranch).append(",");
            json.append("\"squash\":").append(squash);
            if (assigneeId != null && assigneeId > 0) {
                json.append(",\"assignee_id\":").append(assigneeId);
            }
            json.append("}");

            String createUrl = info.getApiUrl() + "/projects/" + info.getEncodedPath() + "/merge_requests";
            HttpRequest postReq = HttpRequest.newBuilder()
                    .uri(URI.create(createUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("PRIVATE-TOKEN", token.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> postResp = HTTP_CLIENT.send(postReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (postResp.statusCode() == 201) {
                String body = postResp.body();
                int iid = extractIntJson(body, "iid");
                String webUrl = extractStringJson(body, "web_url");
                return MrResult.success(webUrl, iid, "Created via API", false);
            } else if (postResp.statusCode() == 409) {
                // Conflict - check again for existing MR
                String webUrl = extractStringJson(postResp.body(), "web_url");
                int iid = extractIntJson(postResp.body(), "iid");
                if (webUrl != null && !webUrl.isBlank()) {
                    return MrResult.success(webUrl, iid, "Existing MR", true);
                }
                return MrResult.error("Merge request already exists for " + sourceBranch + " -> " + targetBranch);
            } else {
                return MrResult.error("GitLab API error (HTTP " + postResp.statusCode() + "): " + postResp.body());
            }
        } catch (Exception ex) {
            return MrResult.error("Failed to create MR via API: " + ex.getMessage());
        }
    }

    public static String generateWebMrUrl(File repoDir, String hostOverride, String sourceBranch, String targetBranch) {
        String remoteUrl = getOriginRemoteUrl(repoDir);
        GitLabProjectInfo info = parseProjectInfo(remoteUrl, hostOverride);
        if (info != null) {
            String encSource = URLEncoder.encode(sourceBranch, StandardCharsets.UTF_8);
            String encTarget = URLEncoder.encode(targetBranch, StandardCharsets.UTF_8);
            if (info.getHostUrl().contains("github.com")) {
                return info.getWebProjectUrl() + "/compare/" + encTarget + "..." + encSource + "?expand=1";
            }
            return info.getWebProjectUrl() + "/-/merge_requests/new?merge_request[source_branch]=" + encSource + "&merge_request[target_branch]=" + encTarget;
        }
        return null;
    }

    public static String getOriginRemoteUrl(File repoDir) {
        if (repoDir == null || !repoDir.exists()) return "";
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "remote", "get-url", "origin");
            pb.directory(repoDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null) return line.trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static String escapeJson(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    public static String extractStringJson(String json, String key) {
        if (json == null || key == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1).replace("\\/", "/").replace("\\\"", "\"");
        }
        return null;
    }

    public static int extractIntJson(String json, String key) {
        if (json == null || key == null) return 0;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
