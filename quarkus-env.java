///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3
//DEPS org.junit.jupiter:junit-jupiter:5.10.2
//DEPS org.junit.platform:junit-platform-launcher:1.10.2
//JAVA 17
//DESCRIPTION Convert Quarkus application.properties to .env format

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static java.lang.System.err;
import static java.lang.System.out;
import static org.junit.jupiter.api.Assertions.*;


// Reference guides at
// https://quarkus.io/guides/config-reference
// https://quarkus.io/guides/cli-tooling

@Command(
    name = "quarkus-env",
    aliases = {"env"},
    mixinStandardHelpOptions = true,
    version = "0.1",
    description = "Convert Quarkus application.properties to .env format.",
    footer = {
        "",
        "Transformation rules:",
        "  quarkus.http.port=8080         ->  QUARKUS_HTTP_PORT=8080",
        "  quarkus.datasource.db-kind=h2  ->  QUARKUS_DATASOURCE_DB_KIND=h2",
        "  %%dev.quarkus.http.port=8181    ->  _DEV_QUARKUS_HTTP_PORT=8181",
        "  quarkus.ds.\"my-db\".url=...   ->  QUARKUS_DS_MY_DB_URL=...",
        "",
        "Comments and blank lines are preserved as-is.",
        "Values are written unchanged (${...} expressions are kept).",
        "",
        "Examples:",
        "  Preview the output without writing any file:",
        "    quarkus env --dry-run",
        "",
        "  Convert and write to .env-<timestamp> (default):",
        "    quarkus env",
        "",
        "  Convert a custom properties file:",
        "    quarkus env -i src/main/resources/application-prod.properties",
        "",
        "  Append to an existing .env file:",
        "    quarkus env --append",
        "",
        "  Write to a specific output file:",
        "    quarkus env -o .env.local"
    }
)
class quarkus_env implements Callable<Integer> {

    @Option(
        names = {"--input", "-i"},
        description = "Input .properties file (default: src/main/resources/application.properties)"
    )
    Path input;

    @Option(
        names = {"--output", "-o"},
        description = "Output file path (overrides default timestamped name)"
    )
    Path output;

    @Option(
        names = {"--append", "-a"},
        description = "Append to .env instead of creating a timestamped .env-<timestamp> file"
    )
    boolean append;

    @Option(
        names = {"--dry-run", "-d"},
        description = "Print result to stdout without writing any file"
    )
    boolean dryRun;

    public static void main(String... args) {
        int exitCode = new CommandLine(new quarkus_env()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (input == null) {
            input = Path.of("src/main/resources/application.properties");
        }

        if (!Files.exists(input)) {
            err.println("Input file not found: " + input);
            err.println("Use --input to specify a different file.");
            return 1;
        }

        Path resolvedOutput = resolveOutput();

        List<String> lines = Files.readAllLines(input);
        List<String> transformed = new ArrayList<>(lines.size());
        for (String line : lines) {
            transformed.add(transformLine(line));
        }

        if (dryRun) {
            transformed.forEach(out::println);
        } else {
            writeOutput(resolvedOutput, transformed);
            out.println("Written to: " + resolvedOutput);
        }

        return 0;
    }

    private Path resolveOutput() {
        if (output != null) {
            return output;
        }
        if (append) {
            return Path.of(".env");
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return Path.of(".env-" + timestamp);
    }

    private void writeOutput(Path target, List<String> lines) throws IOException {
        if (append && Files.exists(target)) {
            // Add a blank separator line before appending
            List<String> withSeparator = new ArrayList<>();
            withSeparator.add("");
            withSeparator.addAll(lines);
            Files.write(target, withSeparator, StandardOpenOption.APPEND);
        } else {
            Files.write(target, lines);
        }
    }

    String transformLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return line;
        }

        int eqIndex = line.indexOf('=');
        if (eqIndex < 0) {
            return transformKey(trimmed) + "=";
        }

        String key = line.substring(0, eqIndex).trim();
        String value = line.substring(eqIndex + 1);
        return transformKey(key) + "=" + value;
    }

    String transformKey(String key) {
        if (key.startsWith("%")) {
            int dotIndex = key.indexOf('.');
            if (dotIndex > 0) {
                String profile = key.substring(1, dotIndex).toUpperCase();
                String rest = key.substring(dotIndex + 1);
                return "_" + profile + "_" + normalizeKey(rest);
            }
            return normalizeKey(key.substring(1));
        }
        return normalizeKey(key);
    }

    String normalizeKey(String key) {
        return key.replace("\"", "")
                  .toUpperCase()
                  .replace('.', '_')
                  .replace('-', '_');
    }
}

// --- Tests (run with: jbang -m quarkus_envTest quarkus-env.java) ---

class quarkus_envTest {

    public static void main(String... args) {
        var listener = new SummaryGeneratingListener();
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(quarkus_envTest.class))
                .build();
        LauncherFactory.create().execute(request, listener);
        var summary = listener.getSummary();
        summary.printFailuresTo(new java.io.PrintWriter(System.err, true));
        System.out.printf("%d tests, %d failed%n",
                summary.getTestsStartedCount(), summary.getTotalFailureCount());
        if (summary.getTotalFailureCount() > 0) System.exit(1);
    }

    private final quarkus_env cmd = new quarkus_env();

    @Test
    void plainKey() {
        assertEquals("QUARKUS_HTTP_PORT=8080", cmd.transformLine("quarkus.http.port=8080"));
    }

    @Test
    void dashedKey() {
        assertEquals("QUARKUS_DATASOURCE_DB_KIND=h2", cmd.transformLine("quarkus.datasource.db-kind=h2"));
    }

    @Test
    void profileKey() {
        assertEquals("_DEV_QUARKUS_HTTP_PORT=8181", cmd.transformLine("%dev.quarkus.http.port=8181"));
    }

    @Test
    void profileKeyProd() {
        assertEquals("_PROD_QUARKUS_DATASOURCE_URL=jdbc:postgresql://localhost/mydb",
            cmd.transformLine("%prod.quarkus.datasource.url=jdbc:postgresql://localhost/mydb"));
    }

    @Test
    void quotedSegment() {
        assertEquals("QUARKUS_DATASOURCE_MY_DB_URL=jdbc:h2:mem:test",
            cmd.transformLine("quarkus.datasource.\"my-db\".url=jdbc:h2:mem:test"));
    }

    @Test
    void commentPreserved() {
        assertEquals("# this is a comment", cmd.transformLine("# this is a comment"));
    }

    @Test
    void bangCommentPreserved() {
        assertEquals("! another comment", cmd.transformLine("! another comment"));
    }

    @Test
    void blankLinePreserved() {
        assertEquals("", cmd.transformLine(""));
        assertEquals("   ", cmd.transformLine("   "));
    }

    @Test
    void valueWithExpressionPreserved() {
        assertEquals("QUARKUS_DATASOURCE_USERNAME=${DB_USER:admin}",
            cmd.transformLine("quarkus.datasource.username=${DB_USER:admin}"));
    }

    @Test
    void keyWithNoValue() {
        assertEquals("QUARKUS_FEATURE_FLAG=", cmd.transformLine("quarkus.feature.flag"));
    }
}
