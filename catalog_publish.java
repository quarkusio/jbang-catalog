/// usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.7
//DEPS io.quarkus:quarkus-devtools-registry-client:3.20.1
//JAVA_OPTIONS "-Djava.util.logging.SimpleFormatter.format=%1$s [%4$s] %5$s%6$s%n"
//JAVA 17

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.registry.catalog.CatalogMapperHelper;
import io.quarkus.registry.catalog.ExtensionCatalog;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.jboss.logging.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Command(name = "catalog_publish", mixinStandardHelpOptions = true, version = "catalog_publish 0.1",
        description = "catalog_publish made with jbang")
class catalog_publish implements Callable<Integer> {

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    private static final Logger log = Logger.getLogger(catalog_publish.class);

    @Option(names = {"-w", "--working-directory"}, description = "The working directory", required = true)
    Path workingDirectory;

    @Option(names = {"-u",
            "--registry-url"}, description = "The Extension Registry URL", required = true, defaultValue = "${REGISTRY_URL}")
    URI registryURL;

    @Option(names = {"-t",
            "--token"}, description = "The token to use when authenticating to the admin endpoint", defaultValue = "${REGISTRY_TOKEN}")
    String token;

    @Option(names = {"-a", "--all"}, description = "Publish all versions? If false, just the latest is published")
    boolean all;

    private static Settings mavenSettings;

    private final ObjectMapper yamlMapper;

    public static void main(String... args) {
        int exitCode = new CommandLine(new catalog_publish()).execute(args);
        System.exit(exitCode);
    }

    public catalog_publish() {
        this.yamlMapper = new YAMLMapper();
        CatalogMapperHelper.initMapper(yamlMapper);
    }

    @Override
    public Integer call() throws Exception {
        boolean error1 = list(workingDirectory.resolve("platforms"), this::processCatalog);
        boolean error2 = list(workingDirectory.resolve("extensions"), this::processExtension);
        if (error1 || error2) {
            return 1;
        }
        return 0;
    }

    private boolean list(Path path, Function<Path, Boolean> consumer) throws IOException {
        boolean error;
        try (Stream<Path> files = Files.list(path)) {
            error = files.filter(file -> {
                        String fileName = file.getFileName().toString();
                        return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
                    })
                    .anyMatch(consumer::apply);
        }
        return error;
    }

    private boolean processCatalog(Path platformYaml) {
        try {
            log.infof("Processing platform %s", platformYaml);
            log.info("---------------------------------------------------------------");
            ObjectNode tree = (ObjectNode) yamlMapper.readTree(platformYaml.toFile());
            if (!tree.path("enabled").asBoolean(true)) {
                log.info("Platform is disabled. Skipping");
                return false;
            }
            List<String> repositories = null;
            // allow the maven-repository field to be either a string or an array of strings
            JsonNode repositoriesNode = tree.path("maven-repository");
            if (repositoriesNode.isArray()) {
                repositories = StreamSupport.stream(repositoriesNode.spliterator(), false)
                        .map(JsonNode::asText)
                        .collect(Collectors.toList());
            } else {
                repositories = Collections.singletonList(repositoriesNode.asText(MAVEN_CENTRAL));
            }
            String groupId = tree.get("group-id").asText();
            String artifactId = tree.get("artifact-id").asText();
            String platformKey = tree.get("platform-key").asText();
            String classifier = tree.path("classifier").asText();
            boolean classifierAsVersion = tree.path("classifier-as-version").asBoolean();
            // Used to find the credentials in the Maven settings
            String serverId = tree.path("server-id").asText();
            for (JsonNode node : tree.withArray("versions")) {
                String version;
                if (node.isObject()) {
                    version = node.fieldNames().next();
                } else {
                    version = node.asText();
                }
                if (classifierAsVersion) {
                    classifier = version;
                }
                // Get Extension YAML
                byte[] jsonPlatform = readCatalog(repositories, groupId, artifactId, version, classifier, serverId);
                // Publish
                log.infof("Publishing %s:%s:%s", groupId, artifactId, version);
                publishCatalog(platformKey, jsonPlatform, false, "C", ArtifactCoords.jar(groupId, artifactId, version));
                // Publish platform members
                publishCatalogMembers(jsonPlatform, repositories, serverId);
                if (!all) {
                    // Just publish the first one
                    break;
                }
            }
            for (JsonNode node : tree.withArray("pinned-versions")) {
                String version = node.asText();
                if (classifierAsVersion) {
                    classifier = version;
                }
                // Get Extension YAML
                byte[] jsonPlatform = readCatalog(repositories, groupId, artifactId, version, classifier, serverId);
                // Publish
                log.infof("Publishing %s:%s:%s", groupId, artifactId, version);
                publishCatalog(platformKey, jsonPlatform, true, "C", ArtifactCoords.jar(groupId, artifactId, version));
                // Publish platform members
                publishCatalogMembers(jsonPlatform, repositories, serverId);
                if (!all) {
                    // Just publish the first one
                    break;
                }
            }
            Set<String> pinnedStreams = toSet(tree.withArray("pinned-streams"));
            Set<String> unlistedStreams = toSet(tree.withArray("unlisted-streams"));
            Set<String> ltsStreams = toSet(tree.withArray("lts-streams"));

            Set<String> streams = new HashSet<>();
            streams.addAll(pinnedStreams);
            streams.addAll(unlistedStreams);
            streams.addAll(ltsStreams);

            for (String stream : streams) {
                // Publish
                log.infof("Patching stream %s for platform %s", stream, platformKey);
                patchPlatformStream(platformKey, stream, pinnedStreams.contains(stream), unlistedStreams.contains(stream), ltsStreams.contains(stream));
                if (!all) {
                    // Just publish the first one
                    break;
                }
            }

        } catch (IOException e) {
            log.error("Error while processing platform", e);
            return true;
        }
        log.info("---------------------------------------------------------------");
        return false;
    }

    private void publishCatalogMembers(byte[] parentPlatform, List<String> repositories, String serverId) throws IOException {
        ExtensionCatalog catalog = CatalogMapperHelper.deserialize(new ByteArrayInputStream(parentPlatform),
                io.quarkus.registry.catalog.ExtensionCatalogImpl.Builder.class);
        Map<String, Object> platformRelease = (Map<String, Object>) catalog.getMetadata().get("platform-release");
        List<String> members = (List<String>) platformRelease.get("members");
        for (String member : members) {
            // Skip this catalog
            if (!member.equals(catalog.getId())) {
                ArtifactCoords memberCoords = ArtifactCoords.fromString(member);
                String groupId = memberCoords.getGroupId();
                String artifactId = memberCoords.getArtifactId();
                String version = memberCoords.getVersion();
                String classifier = version;
                byte[] jsonPlatform = readCatalog(repositories, groupId, artifactId, version, classifier, serverId);
                // Publish
                log.infof("Publishing %s:%s:%s", memberCoords.getGroupId(), memberCoords.getArtifactId(), memberCoords.getVersion());
                String platformKey = memberCoords.getGroupId() + ":" + memberCoords.getArtifactId();
                publishCatalog(platformKey, jsonPlatform, false, "M", memberCoords);
            }
        }
    }

    boolean processExtension(Path extensionYaml) {
        try {
            log.infof("Processing extension %s", extensionYaml);
            log.info("---------------------------------------------------------------");
            // Read
            ObjectNode tree = (ObjectNode) yamlMapper.readTree(extensionYaml.toFile());
            if (!tree.path("enabled").asBoolean(true)) {
                log.info("Extension is disabled. Skipping");
                return false;
            }
            String repository = tree.path("maven-repository").asText(MAVEN_CENTRAL);
            String groupId = tree.get("group-id").asText();
            String artifactId = tree.get("artifact-id").asText();
            for (JsonNode node : tree.withArray("versions")) {
                String version;
                List<String> compatibleWithQuarkusVersions;
                if (node.isObject()) {
                    version = node.fieldNames().next();
                    JsonNode versionNode = node.path(version);
                    if (versionNode.isObject()) {
                        compatibleWithQuarkusVersions = StreamSupport.stream(
                                        versionNode.withArray("compatible-with-quarkus-core").spliterator(), false)
                                .map(JsonNode::asText)
                                .collect(Collectors.toList());
                    } else {
                        compatibleWithQuarkusVersions = Collections.emptyList();
                    }
                } else {
                    version = node.asText();
                    compatibleWithQuarkusVersions = Collections.emptyList();
                }
                // Get Extension YAML
                byte[] jsonExtension = readExtension(repository, groupId, artifactId, version);
                // Publish
                log.infof("Publishing %s:%s:%s", groupId, artifactId, version);
                publishExtension(jsonExtension);
                if (!compatibleWithQuarkusVersions.isEmpty()) {
                    publishCompatibility(groupId, artifactId, version, compatibleWithQuarkusVersions);
                }
                if (!all) {
                    // Just publish the first one
                    break;
                }
            }
        } catch (IOException e) {
            log.error("Error while processing extension", e);
            return true;
        }
        log.info("---------------------------------------------------------------");
        return false;
    }

    private byte[] readCatalog(List<String> repositories, String groupId, String artifactId, String version, String classifier, String serverId)
            throws IOException {
        List<URI> triedUris = new ArrayList<>();
        for (String repository : repositories) {
            URI platformJson;
            if (classifier == null) {
                platformJson = URI.create(MessageFormat.format("{0}{1}/{2}/{3}/{2}-{3}.json",
                        Objects.toString(repository, MAVEN_CENTRAL),
                        groupId.replace('.', '/'),
                        artifactId,
                        version));
            } else {
                // https://repo1.maven.org/maven2/io/quarkus/quarkus-bom-quarkus-platform-descriptor/1.13.0.Final/quarkus-bom-quarkus-platform-descriptor-1.13.0.Final-1.13.0.Final.json
                platformJson = URI.create(MessageFormat.format("{0}{1}/{2}/{3}/{2}-{4}-{3}.json",
                        Objects.toString(repository, MAVEN_CENTRAL),
                        groupId.replace('.', '/'),
                        artifactId,
                        version,
                        classifier));
            }
            triedUris.add(platformJson);
            if ("file".equals(platformJson.getScheme())) {
                var path = Path.of(platformJson);
                if (!Files.exists(path)) {
                    throw new IOException(path + " does not exist");
                }
                return Files.readAllBytes(path);
            }
            try (CloseableHttpClient httpClient = createHttpClient();
                 CloseableHttpResponse response = request(httpClient, platformJson, serverId)) {
                try (InputStream is = response.getEntity().getContent()) {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        log.info("Can't get the extension catalog from " + platformJson + ", server responded: " + new String(is.readAllBytes()));
                        continue; // try the next possible repo
                    } else {
                        return is.readAllBytes();
                    }
                }
            }
        }
        throw new RuntimeException("Can't read the extension catalog, URIs tried: " + triedUris);
    }

    private CloseableHttpResponse request(CloseableHttpClient httpClient, URI platformJson, String serverId) throws IOException {
        HttpGet request = new HttpGet(platformJson);

        UsernamePasswordCredentials credentials = findAuthenticationInfo(serverId, platformJson);
        if (credentials != null) {
//            HttpClientContext httpClientContext = HttpClientContext.create();
//            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
//            credentialsProvider.setCredentials(AuthScope.ANY, credentials);
//            httpClientContext.setCredentialsProvider(credentialsProvider);
//            httpClient.execute(request, httpClientContext);
            request.addHeader("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                    (credentials.getUserName() + ":" + credentials.getPassword()).getBytes()));
        }
        return httpClient.execute(request);
    }

    private byte[] readExtension(String repository, String groupId, String artifactId, String version) throws IOException {
        URL extensionJarURL = URI.create(MessageFormat.format("jar:{0}{1}/{2}/{3}/{2}-{3}.jar!/META-INF/quarkus-extension.yaml",
                Objects.toString(repository, MAVEN_CENTRAL),
                groupId.replace('.', '/'),
                artifactId,
                version)).toURL();
        try (InputStream is = extensionJarURL.openStream()) {
            return is.readAllBytes();
        }
    }

    private void publishExtension(byte[] extension) throws IOException {
        try (final CloseableHttpClient httpClient = createHttpClient()) {
            HttpPost post = new HttpPost(registryURL.resolve("/admin/v1/extension"));
            post.setHeader("Content-Type", "application/yaml");
            if (token != null) {
                post.setHeader("Token", token);
            }
            post.setEntity(new ByteArrayEntity(extension));
            try (CloseableHttpResponse response = httpClient.execute(post)) {
                StatusLine statusLine = response.getStatusLine();
                int statusCode = statusLine.getStatusCode();
                if (statusCode == HttpURLConnection.HTTP_CONFLICT) {
                    log.info("Conflict, version already exists. Ignoring");
                    return;
                }
                if (statusCode >= 300) {
                    throw new IOException(statusLine.getStatusCode() + " -> " + statusLine.getReasonPhrase());
                } else {
                    log.info("Extension published");
                }
            }
        }
    }

    private void publishCatalog(String platformKey, byte[] jsonPlatform, boolean pinned, String platformType, ArtifactCoords artifactCoords)
            throws IOException {
        try (final CloseableHttpClient httpClient = createHttpClient()) {
            HttpPost post = new HttpPost(registryURL.resolve("/admin/v1/extension/catalog"));
            post.setHeader("X-Platform", platformKey);
            post.setHeader("X-Platform-Pinned", Boolean.toString(pinned));
            post.setHeader("X-Platform-Type", platformType);
            post.setHeader("X-Group-Id", artifactCoords.getGroupId());
            post.setHeader("X-Artifact-Id", artifactCoords.getArtifactId());
            post.setHeader("X-Version", artifactCoords.getVersion());
            post.setHeader("Content-Type", "application/json");
            if (token != null) {
                post.setHeader("Token", token);
            }
            post.setEntity(new ByteArrayEntity(jsonPlatform));
            try (CloseableHttpResponse response = httpClient.execute(post)) {
                StatusLine statusLine = response.getStatusLine();
                int statusCode = statusLine.getStatusCode();
                if (statusCode == HttpURLConnection.HTTP_CONFLICT) {
                    log.info("Conflict, version already exists. Ignoring");
                    return;
                }
                if (statusCode >= 300) {
                    throw new IOException(statusLine.getStatusCode() + " -> " + statusLine.getReasonPhrase());
                } else {
                    log.info("Platform published");
                }
            }
        }
    }

    private void publishCompatibility(String groupId, String artifactId, String version,
                                      List<String> compatibleWithQuarkusVersions) throws IOException {
        try (final CloseableHttpClient httpClient = createHttpClient()) {
            HttpPost post = new HttpPost(registryURL.resolve("/admin/v1/extension/compat"));
            post.setHeader("Content-Type", "application/x-www-form-urlencoded");
            if (token != null) {
                post.setHeader("Token", token);
            }
            for (String quarkusCore : compatibleWithQuarkusVersions) {
                List<NameValuePair> params = List.of(
                        new BasicNameValuePair("groupId", groupId),
                        new BasicNameValuePair("artifactId", artifactId),
                        new BasicNameValuePair("version", version),
                        new BasicNameValuePair("quarkusCore", quarkusCore),
                        new BasicNameValuePair("compatible", "true")
                );
                post.setEntity(new UrlEncodedFormEntity(params));
                try (CloseableHttpResponse response = httpClient.execute(post)) {
                    StatusLine statusLine = response.getStatusLine();
                    int statusCode = statusLine.getStatusCode();
                    if (statusCode >= 300) {
                        throw new IOException(statusLine.getStatusCode() + " -> " + statusLine.getReasonPhrase());
                    } else {
                        log.infof("Extension %s:%s:%s is now marked as compatible with Quarkus %s", groupId, artifactId,
                                version, quarkusCore);
                    }
                }
            }
        }
    }

    private void patchPlatformStream(String platformKey, String stream, boolean pinned, boolean unlisted, boolean lts) throws IOException {
        try (final CloseableHttpClient httpClient = createHttpClient()) {
            HttpPatch post = new HttpPatch(registryURL.resolve("/admin/v1/stream/" + platformKey + "/" + stream));
            post.setHeader("Content-Type", "application/x-www-form-urlencoded");
            if (token != null) {
                post.setHeader("Token", token);
            }
            List<NameValuePair> params = List.of(
                    new BasicNameValuePair("pinned", String.valueOf(pinned)),
                    new BasicNameValuePair("unlisted", String.valueOf(unlisted)),
                    new BasicNameValuePair("lts", String.valueOf(lts))
            );
            post.setEntity(new UrlEncodedFormEntity(params));
            try (CloseableHttpResponse response = httpClient.execute(post)) {
                StatusLine statusLine = response.getStatusLine();
                int statusCode = statusLine.getStatusCode();
                if (statusCode >= 300) {
                    throw new IOException(statusLine.getStatusCode() + " -> " + statusLine.getReasonPhrase());
                } else {
                    log.infof("Stream %s (platform %s) is now patched", stream, platformKey);
                }
            }
        }
    }

    private Set<String> toSet(ArrayNode node) {
        return StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
    }

    private CloseableHttpClient createHttpClient() {
        return HttpClients.custom()
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .build();
    }

    private static Settings getMavenSettings() {
        if (mavenSettings == null) {
            DefaultSettingsBuildingRequest buildingRequest = new DefaultSettingsBuildingRequest();
            DefaultSettingsBuilderFactory factory = new DefaultSettingsBuilderFactory();
            try {
                // Decrypt settings if needed
                buildingRequest.setUserSettingsFile(Path.of(System.getProperty("user.home"), ".m2", "settings.xml").toFile());
                buildingRequest.setSystemProperties(System.getProperties());
                mavenSettings = factory.newInstance().build(buildingRequest).getEffectiveSettings();
            } catch (SettingsBuildingException e) {
                log.error("Error while reading Maven settings", e);
                return null;
            }
        }
        return mavenSettings;
    }

    private UsernamePasswordCredentials findAuthenticationInfo(String serverId, URI platformJson) {
        // Try to find the credentials in the Maven settings
        Settings settings = getMavenSettings();
        if (settings == null) {
            log.warnf("No Maven settings found. Skipping");
            return null;
        }
        Server server = settings.getServer(serverId);
        UsernamePasswordCredentials credentials = null;
        if (server == null) {
            if (serverId != null && !serverId.isEmpty()) {
                // If the server id is not null or empty, warn the user
                log.warnf("No server found with id %s in settings.xml for %s", serverId, platformJson);
            }
        } else if (server.getUsername() == null || server.getPassword() == null) {
            log.warnf("Server %s does not have username or password defined in settings.xml for %s", serverId, platformJson);
        } else if (server.getUsername().isEmpty() || server.getPassword().isEmpty()) {
            log.warnf("Server %s has empty username or password defined in settings.xml for %s", serverId, platformJson);
        } else {
            credentials = new UsernamePasswordCredentials(server.getUsername(), server.getPassword());
        }
        return credentials;
    }
}

