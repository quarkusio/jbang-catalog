///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.1
//DEPS io.quarkus:quarkus-devtools-registry-client:3.7.1
//DEPS org.eclipse.jgit:org.eclipse.jgit:5.13.0.202109080827-r
//JAVA_OPTIONS "-Djava.util.logging.SimpleFormatter.format=%1$s [%4$s] %5$s%6$s%n"
//JAVA 17

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.quarkus.registry.catalog.CatalogMapperHelper;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jboss.logging.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "catalog_check_updates", mixinStandardHelpOptions = true, version = "catalog_check_updates 0.1",
        description = "catalog_check_updates made with jbang")
class catalog_check_updates implements Callable<Integer> {

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    private static final Logger log = Logger.getLogger(catalog_check_updates.class);

    @Option(names = { "-w", "--working-directory" }, description = "The working directory", required = true)
    Path workingDirectory;

    @Option(names = { "-n", "--no-commit" }, description = "Do not commit changes")
    boolean noCommit;

    private final ObjectMapper yamlMapper;

    private Git git;

    public static void main(String... args) {
        int exitCode = new CommandLine(new catalog_check_updates()).execute(args);
        System.exit(exitCode);
    }

    public catalog_check_updates() {
        this.yamlMapper = new YAMLMapper();
        CatalogMapperHelper.initMapper(yamlMapper);
    }

    @Override
    public Integer call() throws Exception {
        try (Git gitHandle = Git.open(workingDirectory.toFile())) {
            this.git = gitHandle;
            list(workingDirectory.resolve("platforms"), this::processCatalog);
            list(workingDirectory.resolve("extensions"), this::processExtension);
        }
        return 0;
    }

    private void list(Path path, Consumer<Path> consumer) throws IOException {
        try (Stream<Path> files = Files.list(path)) {
            files
                    .filter(file -> {
                        String fileName = file.getFileName().toString();
                        return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
                    })
                    .forEach(consumer);
        }
    }

    private void processCatalog(Path platformYaml) {
        try {
            log.infof("Processing platform %s", platformYaml);
            log.info("---------------------------------------------------------------");
            ObjectNode tree = (ObjectNode) yamlMapper.readTree(platformYaml.toFile());
            if (!tree.path("enabled").asBoolean(true)) {
                log.info("Platform is disabled. Skipping");
                return;
            }
            String repository = tree.path("maven-repository").asText(MAVEN_CENTRAL);
            String groupId = tree.get("group-id").asText();
            String artifactId = tree.get("artifact-id").asText();
            log.infof("Fetching latest version for %s:%s", groupId, artifactId);
            List<String> newVersions = populateVersions(repository, groupId, artifactId,
                    tree.withArray("versions"),
                    tree.withArray("exclude-versions"));
            if (newVersions.isEmpty()) {
                log.info("No new versions found");
            } else {
                log.infof("New versions Found: %s", newVersions);
                yamlMapper.writeValue(platformYaml.toFile(), tree);
                // Git commit
                gitCommit(platformYaml, "Add " + newVersions + " to " + workingDirectory.resolve(platformYaml).normalize());
            }
        } catch (IOException e) {
            log.error("Error while processing platform", e);
        }
        log.info("---------------------------------------------------------------");

    }

    private void processExtension(Path extensionYaml) {
        try {
            log.infof("Processing extension %s", extensionYaml);
            log.info("---------------------------------------------------------------");
            // Read
            ObjectNode tree = (ObjectNode) yamlMapper.readTree(extensionYaml.toFile());
            if (!tree.path("enabled").asBoolean(true)) {
                log.info("Extension is disabled. Skipping");
                return;
            }
            String repository = tree.path("maven-repository").asText(MAVEN_CENTRAL);
            String groupId = tree.get("group-id").asText();
            String artifactId = tree.get("artifact-id").asText();
            log.infof("Fetching latest version for %s:%s", groupId, artifactId);
            List<String> newVersions = populateVersions(repository, groupId, artifactId,
                    tree.withArray("versions"),
                    tree.withArray("exclude-versions"))
                     .stream().filter(catalog_check_updates::isFinal)
                     .collect(Collectors.toList());
            if (newVersions.isEmpty()) {
                log.info("No new versions found");
            } else {
                log.infof("New Versions Found: %s", newVersions);
                // Write version
                yamlMapper.writeValue(extensionYaml.toFile(), tree);
                // Git commit
                gitCommit(extensionYaml, "Add " + newVersions + " to " + workingDirectory.resolve(extensionYaml).normalize());
            }
        } catch (IOException e) {
            log.error("Error while processing extension", e);
        }
        log.info("---------------------------------------------------------------");
    }

    private List<String> populateVersions(String repository, String groupId, String artifactId, ArrayNode versionsNode,
            ArrayNode excludeVersions)
            throws IOException {
        List<String> newVersions = new ArrayList<>();
        Map<String, JsonNode> map = new LinkedHashMap<>();
        List<String> versionsAlreadyRead = StreamSupport.stream(versionsNode.spliterator(), false)
                .map(node -> {
                    String versionName;
                    if (node.isObject()) {
                        versionName = node.fieldNames().next();
                        map.put(versionName, node.get(versionName));
                    } else {
                        versionName = node.asText();
                        map.put(versionName, versionsNode.nullNode());
                    }
                    return versionName;
                })
                .collect(Collectors.toList());
        URI metadataURL = URI.create(MessageFormat.format("{0}{1}/{2}/maven-metadata.xml",
                Objects.toString(repository, MAVEN_CENTRAL),
                groupId.replace('.', '/'),
                artifactId));
        try (CloseableHttpClient httpClient = createHttpClient();
                InputStream is = httpClient.execute(new HttpGet(metadataURL)).getEntity().getContent()) {
            MetadataXpp3Reader metadataReader = new MetadataXpp3Reader();
            Metadata metadata = metadataReader.read(is);
            Versioning versioning = metadata.getVersioning();
            List<String> versions = versioning.getVersions();
            Collections.reverse(versions);
            versions = keepLatest(versions);
            versionsNode.removeAll();
            for (String version : versions) {
                if (!containsValue(excludeVersions, version)) {
                    if (map.get(version) == null || map.get(version).isNull()) {
                        versionsNode.add(version);
                    } else {
                        versionsNode.addObject()
                                .set(version, map.get(version));
                    }
                    newVersions.add(version);
                }
            }
        } catch (XmlPullParserException e) {
            log.debug("Invalid metadata", e);
        }
        newVersions.removeAll(versionsAlreadyRead);
        return newVersions;
    }

    private boolean containsValue(ArrayNode arrayNode, String latestVersion) {
        for (JsonNode node : arrayNode) {
            if (node.isObject()) {
                if (latestVersion.matches(node.fieldNames().next())) {
                    return true;
                }
            } else {
                if (latestVersion.matches(node.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> keepLatest(List<String> versions) {
        int major = -1, minor = -1;
        List<String> latest = new ArrayList<>();
        for (String version : versions) {
            DefaultArtifactVersion dav = new DefaultArtifactVersion(version);
            if (dav.getMajorVersion() != major || dav.getMinorVersion() != minor) {
                major = dav.getMajorVersion();
                minor = dav.getMinorVersion();
                latest.add(version);
            }
        }
        return latest;
    }

    public void gitCommit(Path file, String message) throws IOException {
        if (noCommit) {
            return;
        }
        try {
            git.add().addFilepattern(workingDirectory.resolve(file).normalize().toString()).call();
            git.commit().setSign(false).setMessage(message).call();
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
    }

    private static boolean isFinal(String version) {
        DefaultArtifactVersion v = new DefaultArtifactVersion(version);
        String qualifier = v.getQualifier();
        if (qualifier == null) {
            return true;
        }
        qualifier = qualifier.trim().toUpperCase(Locale.ROOT);
        return !qualifier.startsWith("ALPHA") && !qualifier.startsWith("BETA") && !qualifier.startsWith("CR");
    }

    private CloseableHttpClient createHttpClient() {
        return HttpClients.custom()
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .build();
    }
}
