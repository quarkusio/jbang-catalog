///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//FILES openrewriteinit.gradle
//DEPS org.eclipse.transformer:org.eclipse.transformer:0.5.0
//DEPS org.slf4j:slf4j-simple:1.7.36

import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.transformer.action.ByteData;
import org.eclipse.transformer.action.impl.ActionContextImpl;
import org.eclipse.transformer.action.impl.ByteDataImpl;
import org.eclipse.transformer.action.impl.SelectionRuleImpl;
import org.eclipse.transformer.action.impl.SignatureRuleImpl;
import org.eclipse.transformer.action.impl.TextActionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class q3upgrade {

    private static final String RECIPE_URL = "https://raw.githubusercontent.com/quarkusio/quarkus/main/jakarta/quarkus3.yml";
    private static final String PACKAGE_RENAMES_URL = "https://raw.githubusercontent.com/quarkusio/quarkus/main/jakarta/jakarta-renames.properties";

    public static void main(String[] args) {
        new q3upgrade().run();
    }

    void run() {
        Path baseDir = Path.of(System.getProperty("user.dir"));

        try {
            printInfo();
        } catch (InterruptedException e) {
            err.println("Upgrade cancelled!");
            exit(1);
        }

        try {
            if (hasGradle(baseDir)) {
                handleGradle(baseDir);

            } else if (hasMaven(baseDir)) {
                handleMaven(baseDir);
            } else {
                err.println("Could not find pom.xml nor build.gradle file in current directory.");
                exit(1);
            }

            transformDocumentation(baseDir);

            out.println("\n\n");
            out.println(" Your project has now been upgraded to use Quarkus 3.");
            out.println(
                    " Please check the changed files and execute a build with tests before committing the changes.");
            out.println("");

        } catch (IOException fe) {
            fe.printStackTrace();
            err.println("Something went wrong in upgrade. See output above.");
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void transformDocumentation(Path baseDir) throws IOException {
        try (Stream<Path> walk = Files.walk(baseDir)) {
            List<Path> documentationFiles = walk
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.toString().toLowerCase().endsWith(".md")
                            || p.toString().toLowerCase().endsWith(".adoc"))
                    .collect(Collectors.toList());

            Path packageRenamesPath = downloadPackageRenames();

            try (InputStream textRenamesStream = Files.newInputStream(packageRenamesPath)) {
                Properties textRenames = new Properties();
                textRenames.load(textRenamesStream);

                Map<String, Map<String, String>> masterTextUpdates = Map.of(
                        "*.adoc", (Map<String, String>) (Map) textRenames,
                        "*.md", (Map<String, String>) (Map) textRenames);

                Logger logger = LoggerFactory.getLogger("JakartaTransformer");
                ActionContextImpl actionContext = new ActionContextImpl(logger,
                        new SelectionRuleImpl(logger, Map.of(), Map.of()),
                        new SignatureRuleImpl(logger, Map.of(), Map.of(), Map.of(),
                                Map.of(), masterTextUpdates, Map.of(), Map.of()));
                TextActionImpl action = new TextActionImpl(actionContext);

                for (Path documentationFile : documentationFiles) {
                    ByteData inputData = new ByteDataImpl(documentationFile.toString(),
                            ByteBuffer.wrap(Files.readAllBytes(documentationFile)), StandardCharsets.UTF_8);
                    ByteData outputData = action.apply(inputData);
                    try (OutputStream documentationFileOutputStream = Files.newOutputStream(documentationFile)) {
                        outputData.writeTo(Files.newOutputStream(documentationFile));
                    }
                }
            } finally {
                try {
                    Files.deleteIfExists(packageRenamesPath);
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    private static void handleMaven(Path baseDir) throws MalformedURLException, IOException, ProtocolException {
        Path mavenCmd = findMvnCommand(baseDir);
        if (mavenCmd == null) {
            err.println("Cannot locate mvnw or mvn"
                    + ". Make sure gradlew is in the current directory or gradle in your PATH.");
            exit(-1);
        }

        Path tempfile = downloadRecipe();

        try {
            String[] command = new String[] {
                    mavenCmd.toString(),
                    "org.openrewrite.maven:rewrite-maven-plugin:4.36.0:run",
                    "-Drewrite.configLocation=" + tempfile.toAbsolutePath(),
                    "-DactiveRecipes=io.quarkus.openrewrite.Quarkus3",
                    "-Drewrite.pomCacheEnabled=false"
            };

            executeCommand(command);
        } finally {
            try {
                Files.deleteIfExists(tempfile);
            } catch (Exception e) {
                // ignore
            }
        }

        // format the sources
        String[] command = new String[] {
                mavenCmd.toString(),
                "process-sources"
        };

        executeCommand(command);
    }

    private static void handleGradle(Path baseDir) throws MalformedURLException, IOException, ProtocolException {
        Path gradleCmd = findGradleCommand(baseDir);

        if (gradleCmd == null) {
            err.println("Cannot locate gradlew or gradle"
                    + ". Make sure gradlew is in the current directory or gradle in your PATH");
            exit(-1);
        }

        err.println(
                "WARNING: Detected Gradle build file. Upgrading dependencies in Gradle not yet supported. Migration will only update sources.");
        Path tempfile = downloadRecipe();

        Path tempInit = Files.createTempFile("initbuild", "gradle");
        out.println("Generating tempoary gradle init script.");

        InputStream stream = q3upgrade.class.getResourceAsStream("/openrewriteinit.gradle");
        String gradleInit = new Scanner(stream).useDelimiter("\\Z").next();
        stream.close();

        Files.writeString(tempInit, gradleInit
                .replace("%rewritefile%", tempfile.toAbsolutePath().toString()));

        String[] command = new String[] {
                gradleCmd.toString(),
                "--init-script",
                tempInit.toAbsolutePath().toString(),
                "rewriteRun",
        };

        executeCommand(command);
    }

    private static void printInfo() throws InterruptedException {
        out.println("This script will attempt to upgrade your Quarkus project to be compatible with Quarkus 3.\n");
        out.println(
                "It will change files on disk - make sure to have all files committed or some other kind of backup before running it.");
        out.println("Waiting 3 seconds before starting...");
        int i = 3;
        while (i > 0) {
            out.println(i--);
            Thread.sleep(1000);
        }
    }

    private static void executeCommand(String[] command) {
        ProcessBuilder processBuilder = new ProcessBuilder();

        out.println("Executing:\n" + String.join(" ", command));
        processBuilder.command(command);

        try {

            Process process = processBuilder.redirectOutput(Redirect.INHERIT).redirectError(Redirect.INHERIT).start();

            BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                out.println(line);
            }

            int exitCode = process.waitFor();
            out.println("\nExited with error code : " + exitCode);

        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    static Path findMvnCommand(Path baseDir) {
        return findWrapperOrCommand(baseDir, "mvnw", "mvn");
    }

    static Path findGradleCommand(Path baseDir) {
        return findWrapperOrCommand(baseDir, "gradlew", "gradle");
    }

    private static Path findWrapperOrCommand(Path baseDir, String wrapper, String cmd) {
        Path gradlePath = searchPath(wrapper, baseDir.toString());
        if (gradlePath == null) {
            gradlePath = searchPath(cmd);
        }
        return gradlePath;
    }

    private static Path downloadRecipe() throws MalformedURLException, IOException, ProtocolException {
        return downloadFile(RECIPE_URL, "quarkus3", ".yml");
    }

    private static Path downloadPackageRenames() throws MalformedURLException, IOException, ProtocolException {
        return downloadFile(PACKAGE_RENAMES_URL, "jakarta-renames", ".properties");
    }

    private static Path downloadFile(String downloadUrl, String prefix, String suffix)
            throws MalformedURLException, IOException, ProtocolException {
        URL url = new URL(downloadUrl);

        out.println("Downloading " + downloadUrl + " to temporary file.");
        HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
        httpConn.setRequestMethod("GET");
        Path tempfile = null;

        InputStream responseStream = httpConn.getResponseCode() / 100 == 2
                ? httpConn.getInputStream()
                : httpConn.getErrorStream();
        try (Scanner s = new Scanner(responseStream).useDelimiter("\\A")) {
            String response = s.hasNext() ? s.next() : "";
            tempfile = Files.createTempFile(prefix, suffix);
            Files.writeString(tempfile, response);
        }
        return tempfile;
    }

    /**
     * Searches the locations defined by PATH for the given executable
     * 
     * @param cmd The name of the executable to look for
     * @return A Path to the executable, if found, null otherwise
     */
    public static Path searchPath(String cmd) {
        String envPath = System.getenv("PATH");
        envPath = envPath != null ? envPath : "";
        return searchPath(cmd, envPath);
    }

    /**
     * Searches the locations defined by `paths` for the given executable
     *
     * @param cmd   The name of the executable to look for
     * @param paths A string containing the paths to search
     * @return A Path to the executable, if found, null otherwise
     */
    public static Path searchPath(String cmd, String paths) {
        return Arrays.stream(paths.split(File.pathSeparator))
                .map(dir -> Paths.get(dir).resolve(cmd))
                .flatMap(q3upgrade::executables)
                .filter(q3upgrade::isExecutable)
                .findFirst()
                .orElse(null);
    }

    private static Stream<Path> executables(Path base) {
        if (isWindows()) {
            return Stream.of(Paths.get(base.toString() + ".exe"),
                    Paths.get(base.toString() + ".bat"),
                    Paths.get(base.toString() + ".cmd"),
                    Paths.get(base.toString() + ".ps1"));
        } else {
            return Stream.of(base);
        }
    }

    private static boolean isExecutable(Path file) {
        if (Files.isRegularFile(file)) {
            if (isWindows()) {
                String nm = file.getFileName().toString().toLowerCase();
                return nm.endsWith(".exe") || nm.endsWith(".bat") || nm.endsWith(".cmd") || nm.endsWith(".ps1");
            } else {
                return Files.isExecutable(file);
            }
        }
        return false;
    }

    private static String OS = System.getProperty("os.name").toLowerCase();

    public static boolean isWindows() {
        return OS.contains("win");
    }

    static boolean hasGradle(Path dir) {
        return Files.exists(dir.resolve("build.gradle"));
    }

    private static boolean hasMaven(Path dir) {
        return Files.exists(dir.resolve("pom.xml"));
    }
}
