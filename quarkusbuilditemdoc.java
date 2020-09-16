//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.jboss.forge.roaster:roaster-jdt:2.22.2.Final
//DEPS org.eclipse.collections:eclipse-collections:10.4.0
//DEPS org.yaml:snakeyaml:1.27

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import org.eclipse.collections.api.multimap.Multimap;
import org.eclipse.collections.api.multimap.MutableMultimap;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.factory.Multimaps;
import org.eclipse.collections.impl.tuple.Tuples;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "builditemdoc", mixinStandardHelpOptions = true, version = "builditemdoc 0.1",
        description = "builditemdoc made with jbang")
class quarkusbuilditemdoc implements Callable<Integer> {

    public static void main(String... args) {
        int exitCode = new CommandLine(new quarkusbuilditemdoc()).execute(args);
        System.exit(exitCode);
    }

    // find extensions -name "*BuildItem.java" | jbang ~/workspace/quarkus-jbang-catalog/builditemdoc.java > docs/src/main/asciidoc/build-items.adoc
    @Override
    public Integer call() throws Exception {
        final Multimap<String, Pair<Path, JavaClassSource>> multimap = collect();
        Map<String, String> names = extractNames(Paths.get("."), multimap.keySet());
        printDocumentHeader();
        names.forEach((key, name) -> {
            printTableHeader(name);
            for (Pair<Path, JavaClassSource> source : multimap.get(key)) {
                printTableRow(source);
            }
            printTableFooter();
        });
        return 0;
    }

    private String buildDescriptionFromJavaDoc(JavaClassSource source) {
        if (!source.hasJavaDoc()) {
            return "<i>No Javadoc found</i>";
        }
        return source.getJavaDoc().getText();
    }

    private Multimap<String, Pair<Path, JavaClassSource>> collect() throws IOException {
        MutableMultimap<String, Pair<Path, JavaClassSource>> multimap = Multimaps.mutable.list.empty();
        String line;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            while ((line = br.readLine()) != null) {
                Path path = Paths.get(line);
                JavaClassSource source = Roaster.parse(JavaClassSource.class, path.toFile());
                // Ignore deprecated annotations
                if (!source.hasAnnotation(Deprecated.class)) {
                    String pathString = path.toString();
                    int spiIdx = pathString.indexOf("/spi/src");
                    int runtimeIdx = pathString.indexOf("/runtime/src");
                    int deploymentIdx = pathString.indexOf("/deployment/src");
                    int idx = Math.max(Math.max(spiIdx, runtimeIdx), deploymentIdx);
                    int extensionsIdx = pathString.indexOf("extensions/");
                    String name;
                    if (idx == -1) {
                        name = pathString.substring(extensionsIdx + 11, pathString.indexOf("/", extensionsIdx + 12));
                    } else {
                        name = pathString.substring(extensionsIdx + 11, idx);
                    }
                    Pair<Path, JavaClassSource> pair = Tuples.pair(path, source);
                    multimap.put(name, pair);
                }
            }
        }
        return multimap;
    }


    private Map<String, String> extractNames(Path root, Iterable<String> extensionDirs) throws IOException {
        Map<String, String> names = new TreeMap<>();
        Yaml yaml = new Yaml();
        for (String extension : extensionDirs) {
            Path yamlPath = root.resolve("extensions/" + extension + "/runtime/src/main/resources/META-INF/quarkus-extension.yaml");
            if (Files.exists(yamlPath)) {
                try (InputStream is = Files.newInputStream(yamlPath)) {
                    Map<String, String> map = yaml.load(is);
                    names.put(extension, map.get("name"));
                }
            } else {
                names.put(extension, extension);
            }
        }
        return names;
    }

    private void printDocumentHeader() {
        System.out.println("////");
        System.out.println("This guide is maintained in the main Quarkus repository");
        System.out.println("and pull requests should be submitted there:");
        System.out.println("https://github.com/quarkusio/quarkus/tree/master/docs/src/main/asciidoc");
        System.out.println("////");
        System.out.println();
        System.out.println("= Quarkus - Build Items");
        System.out.println();
        System.out.println("Here you can find a list of Build Items and the extension that provides them:");
        System.out.println();
    }

    private void printTableHeader(String title) {
        System.out.println("== " + title);
        System.out.println("[%header,cols=2*]");
        System.out.println("|===");
        System.out.println("|Class Name |Description");
    }

    private void printTableRow(Pair<Path, JavaClassSource> pair) {
        //TODO: Use tagged version?
        String link = "https://github.com/quarkusio/quarkus/blob/master/" + pair.getOne();
        String className = pair.getTwo().getQualifiedName();
        String description = buildDescriptionFromJavaDoc(pair.getTwo());

        System.out.println("| " + link + "[`" + className + "`, window=\"_blank\"]");
        System.out.println("| +++" + javadocToHTML(description) + "+++");
    }

    private void printTableFooter() {
        System.out.println("|===");
    }

    private String javadocToHTML(String content) {
        return content
                .replaceAll("\\{?@see ", "<pre>")
                .replaceAll("\\{?@code ", "<pre>")
                .replaceAll("\\{?@link ", "<pre>")
                .replaceAll(" ?}", "</pre>");
    }

    private String javadocToAsciidoc(String content) {
        return content
                .replaceAll("<p>", "\n")
                .replaceAll("</p>", "\n")
                .replaceAll("\\{?@see ", "```")
                .replaceAll("\\{?@code ", "```")
                .replaceAll("\\{?@link ", "```")
                .replaceAll("<pre>", "```\n")
                .replaceAll("</pre>", "\n```")
                .replaceAll(" ?}", "```");
    }


}
