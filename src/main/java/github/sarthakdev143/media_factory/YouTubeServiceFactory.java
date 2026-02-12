package github.sarthakdev143.media_factory;

import com.google.api.client.auth.oauth2.AuthorizationCodeResponseUrl;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.YouTubeScopes;
import com.sun.net.httpserver.HttpServer;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class YouTubeServiceFactory {

    private static final String APPLICATION_NAME = "MyVideoUploader";
    private static final String CREDENTIALS_PATH_ENV = "YOUTUBE_CREDENTIALS_PATH";
    private static final Path DEFAULT_CREDENTIALS_PATH = Path.of("credentials.json");
    private static final Path TOKENS_DIR_PATH = Path.of(".youtube-tokens");
    private static final int OAUTH_CALLBACK_PORT = 8888;
    private static final String OAUTH_CALLBACK_PATH = "/oauth2callback";
    private static final String OAUTH_USER_ID = "default-user";
    private static final String OAUTH_CALLBACK_URI = "http://localhost:" + OAUTH_CALLBACK_PORT + OAUTH_CALLBACK_PATH;
    private static final List<String> SCOPES = List.of(YouTubeScopes.YOUTUBE_UPLOAD);

    public static YouTube getService() throws GeneralSecurityException, IOException {
        Path credentialsPath = resolveCredentialsPath();
        HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
        Credential credential = authorize(credentialsPath, httpTransport, jsonFactory);

        return new YouTube.Builder(httpTransport, jsonFactory, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private static Credential authorize(Path credentialsPath, HttpTransport httpTransport, JsonFactory jsonFactory)
            throws IOException {
        GoogleClientSecrets clientSecrets;

        try (InputStream credentialsStream = Files.newInputStream(credentialsPath);
             InputStreamReader reader = new InputStreamReader(credentialsStream, StandardCharsets.UTF_8)) {
            clientSecrets = GoogleClientSecrets.load(jsonFactory, reader);
        }

        if (clientSecrets.getDetails() == null
                || clientSecrets.getDetails().getClientId() == null
                || clientSecrets.getDetails().getClientSecret() == null) {
            throw new IOException(
                    "Invalid credentials JSON at " + credentialsPath.toAbsolutePath()
                            + ". Use an OAuth client secret file downloaded from Google Cloud Console "
                            + "(contains top-level 'installed' or 'web').");
        }

        Files.createDirectories(TOKENS_DIR_PATH);

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport,
                jsonFactory,
                clientSecrets,
                SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(TOKENS_DIR_PATH.toFile()))
                .setAccessType("offline")
                .build();

        Credential existingCredential = flow.loadCredential(OAUTH_USER_ID);
        if (existingCredential != null) {
            return existingCredential;
        }

        String authorizationUrl = flow.newAuthorizationUrl()
                .setRedirectUri(OAUTH_CALLBACK_URI)
                .build();

        String authorizationCode = waitForAuthorizationCode(authorizationUrl);
        GoogleTokenResponse tokenResponse = flow.newTokenRequest(authorizationCode)
                .setRedirectUri(OAUTH_CALLBACK_URI)
                .execute();

        return flow.createAndStoreCredential(tokenResponse, OAUTH_USER_ID);
    }

    private static String waitForAuthorizationCode(String authorizationUrl) throws IOException {
        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", OAUTH_CALLBACK_PORT), 0);

        server.createContext(OAUTH_CALLBACK_PATH, exchange -> {
            String requestUrl = OAUTH_CALLBACK_URI + "?" + exchange.getRequestURI().getRawQuery();
            AuthorizationCodeResponseUrl responseUrl = new AuthorizationCodeResponseUrl(requestUrl);

            String responseBody;
            if (responseUrl.getError() != null) {
                codeFuture.completeExceptionally(
                        new IOException("Authorization failed: " + responseUrl.getError()));
                responseBody = "Authorization failed. You can close this window.";
            } else if (responseUrl.getCode() != null) {
                codeFuture.complete(responseUrl.getCode());
                responseBody = "Authorization successful. You can close this window.";
            } else {
                codeFuture.completeExceptionally(
                        new IOException("Authorization code not found in callback request."));
                responseBody = "Authorization code missing. You can close this window.";
            }

            byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bodyBytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bodyBytes);
            }
        });

        server.start();

        try {
            openBrowser(authorizationUrl);
            return codeFuture.get(3, TimeUnit.MINUTES);
        } catch (Exception e) {
            if (e instanceof IOException ioException) {
                throw ioException;
            }

            throw new IOException(
                    "OAuth authorization did not complete. Open this URL in a browser and try again: "
                            + authorizationUrl,
                    e);
        } finally {
            server.stop(0);
        }
    }

    private static void openBrowser(String authorizationUrl) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(URI.create(authorizationUrl));
                return;
            }
        } catch (Exception ignored) {
            // Fall through to console output.
        }

        System.out.println("Open this URL to authorize YouTube upload access:");
        System.out.println(authorizationUrl);
    }

    private static Path resolveCredentialsPath() throws FileNotFoundException {
        String configuredPath = System.getenv(CREDENTIALS_PATH_ENV);
        Path credentialsPath = (configuredPath == null || configuredPath.isBlank())
                ? DEFAULT_CREDENTIALS_PATH
                : Path.of(configuredPath);

        if (!Files.exists(credentialsPath)) {
            throw new FileNotFoundException(
                    "Credentials file not found at " + credentialsPath.toAbsolutePath()
                            + ". Set " + CREDENTIALS_PATH_ENV + " or place credentials.json in the app working directory.");
        }

        return credentialsPath;
    }
}
