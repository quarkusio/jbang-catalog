//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.2.0
//DEPS de.vandermeer:asciitable:0.3.2
//DEPS org.jline:jline:3.16.0
//DEPS org.zeroturnaround:zt-exec:1.11
//DEPS org.slf4j:slf4j-nop:1.7.30
//DEPS net.steppschuh.markdowngenerator:markdowngenerator:1.3.2

import de.vandermeer.asciitable.AsciiTable;
import de.vandermeer.asciitable.CWC_LongestLine;
import de.vandermeer.asciitable.CWC_LongestWordMax;
import de.vandermeer.asciithemes.TA_GridThemes;
import de.vandermeer.skb.interfaces.transformers.textformat.TextAlignment;
import net.steppschuh.markdowngenerator.table.Table;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Time;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.lang.System.getenv;
import static java.lang.System.out;

import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;

@Command(name = "quarkusissue", mixinStandardHelpOptions = true, version = "quarkusissue 1.0",
        description = "Gathers system info for when reporting Quarkus made with jbang.dev")
class quarkusissue implements Callable<Integer> {

    boolean isWindows = System.getProperty("os.name")
            .toLowerCase().startsWith("windows");

    public static void main(String... args) {
        int exitCode = new CommandLine(new quarkusissue()).execute(args);
        System.exit(exitCode);
    }

    String run(String... command) {
        try {
            return new ProcessExecutor().command(command).readOutput(true).execute().outputUTF8();
        } catch (InterruptedException | TimeoutException | IOException e) {
            return e.getMessage();
        }
    }

    String mvnproperty(String property) {
        return run("mvn", "help:evaluate", "-Dexpression=" + property, "-q", "-DforceStdout");
    }

    Map<String, String> gatherInfo() throws Exception {

        Map<String, String> results = new LinkedHashMap<>();

        if(isWindows) {
            results.put("ver", run("ver"));
        } else {
            results.put("uname -a", run("uname", "-a"));
        }

        results.put("java -version", run("java", "-version"));

        if(System.getenv("GRAALVM_HOME")!=null) {
           // results.put("GRAALVM_HOME", System.getenv("GRAALVM_HOME"));
            results.put("graalvm java -version", run(System.getenv("GRAALVM_HOME") + "/bin/java", "-version"));
        }

        if(Files.exists(Path.of("mvnw"))) {
            results.put("mvnw --version", run("./mvnw", "--version"));
            results.put("quarkus-plugin.version", mvnproperty("quarkus-plugin.version") );
            results.put("quarkus.platform.artifact-id", mvnproperty("quarkus.platform.artifact-id"));
            results.put("quarkus.platform.group-id", mvnproperty("quarkus.platform.group-id"));
            results.put("quarkus.platform.version", mvnproperty("quarkus.platform.version"));
        }


        if(Files.exists(Path.of("gradlew"))) {
            results.put("gradlew --version", run("./gradlew", "--version"));
        }

        if(Files.exists(Path.of("gradle.properties"))) {
            Properties p = new Properties();
            try (var f = new FileInputStream((new File("gradle.properties")))) {
                p.load(f);
                results.put("quarkusPluginVersion", p.getProperty("quarkusPluginVersion","N/A") );
                results.put("quarkusPlatformGroupId", p.getProperty("quarkusPlatformGroupId", "N/A"));
                results.put("quarkusPlatformArtifactId", p.getProperty("quarkusPlatformArtifactId", "N/A"));
                results.put("quarkusPlatformVersion", p.getProperty("quarkusPlatformVersion", "N/A"));

            }
        }

        return results;
    }

    void print(Map<String, String> results) throws Exception {

        int[] maxkey = { 0 };

        Table.Builder tableBuilder = new Table.Builder();
        tableBuilder.addRow("Key", "Value");
        AsciiTable at = new AsciiTable();

        at.addRule();
        results.keySet().stream()
                .forEach(key -> {
                    maxkey[0] = Math.max(maxkey[0], ((String) key).length());
                    var value = results.get((String) key).replace(System.lineSeparator(), "<br>");
                    var row = at.addRow(key, value);
                    row.getCells().getLast().getContext().setTextAlignment(TextAlignment.LEFT);
                    at.addRule();

                    tableBuilder.addRow(key, value);
                });

        // best guess in java on terminal width       
        int colwidth = org.jline.terminal.TerminalBuilder.terminal().getWidth();
        if(colwidth==0) colwidth = 80;

        at.getRenderer().setCWC(new CWC_LongestLine().add(5, colwidth-maxkey[0]));

        String table = at.render(colwidth);

        out.println(table);

        System.out.println("Copied markdown version to clipboard.");
        StringSelection stringSelection = new StringSelection(tableBuilder.build().toString());
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(stringSelection, null);

        
    }

    @Override
    public Integer call() throws Exception { // your business logic goes here...

        out.println("Gathering information...");

        var info = gatherInfo();

        print(info);

        System.out.println("Paste the markdown in issue at https://github.com/quarkusio/quarkus/issues/new?assignees=&labels=kind%2Fbug&template=bug_report.md");
   
        return 0;
    }
}
