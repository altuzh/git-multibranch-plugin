package git.multibranch;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;

public class GitLabTokenManager {
    private static final String SERVICE_NAME = "git.multibranch.GitLab";
    private static final String ACCOUNT_NAME = "GitLabToken";

    private static CredentialAttributes createCredentialAttributes() {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SERVICE_NAME, ACCOUNT_NAME));
    }

    public static String getToken() {
        try {
            return PasswordSafe.getInstance().getPassword(createCredentialAttributes());
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setToken(String token) {
        try {
            CredentialAttributes attributes = createCredentialAttributes();
            PasswordSafe.getInstance().setPassword(attributes, (token != null && !token.isBlank()) ? token.trim() : null);
        } catch (Throwable ignored) {}
    }
}
