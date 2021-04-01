///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.eclipse.sisu:org.eclipse.sisu.plexus:0.3.4
//DEPS io.quarkus:quarkus-devtools-common:${quarkus.version:1.13.0.Final}
//DEPS io.quarkus:quarkus-devtools-testing:${quarkus.version:1.13.0.Final}

import io.quarkus.devtools.codestarts.CodestartProjectDefinition;
import io.quarkus.devtools.codestarts.quarkus.QuarkusCodestartCatalog;
import io.quarkus.devtools.codestarts.quarkus.QuarkusCodestartProjectInput;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.BuildTool;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import io.quarkus.devtools.project.QuarkusProjectHelper;
import io.quarkus.registry.catalog.ExtensionCatalog;
import io.quarkus.platform.tools.ToolsConstants;
import io.quarkus.platform.tools.ToolsUtils;
import io.quarkus.devtools.testing.WrapperRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static io.quarkus.devtools.codestarts.quarkus.QuarkusCodestartData.QuarkusDataKey.*;
import static io.quarkus.platform.tools.ToolsUtils.readQuarkusProperties;
import static io.quarkus.platform.tools.ToolsUtils.resolvePlatformDescriptorDirectly;

@Command(name = "QuarkusExampleCodestartDev", mixinStandardHelpOptions = true, version = "0.1",
        description = "QuarkusExampleCodestartDev made with jbang")
class QuarkusExampleCodestartDev implements Callable<Integer> {
    private ExtensionCatalog catalog;

    @Option(names = "-l", description = "comma separated list of languages to generate", defaultValue = "java")
    private String languages;

    @Option(names = "-b", description = "buildtool to use (maven, gradle, gradle-kotlin-dsl)", defaultValue = "maven")
    private String buildTool;

    @Option(names = "-c", description = "the directory containing one or more codestarts to develop", defaultValue = "./codestarts")
    private File codestartsDir;

    @Option(names = "-n", description = "the name of the project to generate", defaultValue = "dev-app")
    private String projName;

    @Option(names = "-t", description = "compile and run tests", defaultValue = "false")
    private boolean test;

    @Option(names = "-d", description = "enable debug", defaultValue = "false")
    private boolean debug;

    @Option(names = "-o", description = "the target directory where we generate the projects", defaultValue = "target")
    private File targetDir;

    @Parameters(index = "0", description = "comma separated list of codestarts to add")
    private String names;

    public static void main(String... args) {
        int exitCode = new CommandLine(new QuarkusExampleCodestartDev()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        for (String s : languages.split(",")) {
            String language = s.trim().toLowerCase();

            final QuarkusCodestartProjectInput input = QuarkusCodestartProjectInput.builder()
                    .addData(getDefaultData(getCatalog()))
                    .buildTool(BuildTool.findTool(buildTool))
                    .addCodestart(language)
                    .addCodestarts(Arrays.stream(names.split(",")).map(String::trim).collect(Collectors.toList()))
                    .putData(JAVA_VERSION.key(), System.getProperty("java.specification.version"))
                    .messageWriter(debug ? MessageWriter.debug() : MessageWriter.info())
                    .build();

            final CodestartProjectDefinition projectDefinition = getCodestartCatalog().createProject(input);
            final Path targetPath = targetDir.toPath().resolve(projName + "_" + language);
            if (Files.isDirectory(targetPath)) {
                System.out.println("Directory already exists '" + targetPath.toString() + "'.. delete [y]?");
                Scanner scanner = new Scanner(System.in);
                String res = scanner.nextLine();
                if (res.isBlank() || res.startsWith("y")) {
                    deleteDirectoryStream(targetPath);
                } else {
                    continue;
                }
            }
            projectDefinition.generate(targetPath);
            System.out.println("\n\nProject created in " + language + ": " + targetPath.toString());

            if (test) {
                System.out.println("Building and running tests...");
                WrapperRunner.run(targetPath, WrapperRunner.Wrapper.fromBuildtool(buildTool));
            }
            System.out.println("---------------\n");
        }
        return 0;
    }

    static void deleteDirectoryStream(Path path) throws IOException {
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    static Map<String, Object> getDefaultData(final ExtensionCatalog catalog) {
        final HashMap<String, Object> data = new HashMap<>();
        final Properties quarkusProp = readQuarkusProperties(catalog);
        data.put(BOM_GROUP_ID.key(), catalog.getBom().getGroupId());
        data.put(BOM_ARTIFACT_ID.key(), catalog.getBom().getArtifactId());
        data.put(BOM_VERSION.key(), catalog.getBom().getVersion());
        data.put(QUARKUS_VERSION.key(), catalog.getQuarkusCoreVersion());
        data.put(QUARKUS_MAVEN_PLUGIN_GROUP_ID.key(), ToolsUtils.getMavenPluginGroupId(quarkusProp));
        data.put(QUARKUS_MAVEN_PLUGIN_ARTIFACT_ID.key(), ToolsUtils.getMavenPluginArtifactId(quarkusProp));
        data.put(QUARKUS_MAVEN_PLUGIN_VERSION.key(), ToolsUtils.getMavenPluginVersion(quarkusProp));
        data.put(QUARKUS_GRADLE_PLUGIN_ID.key(), ToolsUtils.getMavenPluginGroupId(quarkusProp));
        data.put(QUARKUS_GRADLE_PLUGIN_VERSION.key(), ToolsUtils.getGradlePluginVersion(quarkusProp));
        data.put(JAVA_VERSION.key(), "11");
        data.put(KOTLIN_VERSION.key(), quarkusProp.getProperty(ToolsConstants.PROP_KOTLIN_VERSION));
        data.put(SCALA_VERSION.key(), quarkusProp.getProperty(ToolsConstants.PROP_SCALA_VERSION));
        data.put(SCALA_MAVEN_PLUGIN_VERSION.key(), quarkusProp.getProperty(ToolsConstants.PROP_SCALA_PLUGIN_VERSION));
        data.put(MAVEN_COMPILER_PLUGIN_VERSION.key(), quarkusProp.getProperty(ToolsConstants.PROP_COMPILER_PLUGIN_VERSION));
        data.put(MAVEN_SUREFIRE_PLUGIN_VERSION.key(), quarkusProp.getProperty(ToolsConstants.PROP_SUREFIRE_PLUGIN_VERSION));
        return data;
    }

    private QuarkusCodestartCatalog getCodestartCatalog() throws IOException {
        return QuarkusCodestartCatalog
                .fromQuarkusPlatformDescriptorAndDirectories(getCatalog(), Collections.singletonList(codestartsDir.toPath()));
    }

    private ExtensionCatalog getCatalog() {
        if (catalog == null) {
            this.catalog = resolvePlatformDescriptorDirectly(null, null, "1.13.0.Final",
                    QuarkusProjectHelper.artifactResolver(), QuarkusProjectHelper.messageWriter());
        }
        return this.catalog;
    }
}
