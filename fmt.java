///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3
//DEPS org.jboss.forge.roaster:roaster-jdt:2.28.0.Final
//DEPS io.quarkus:quarkus-ide-config:2.16.3.Final
//DESCRIPTION This command will format your sources using the same formatting rules used in the Quarkus Core project

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;
import java.util.concurrent.Callable;

import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.util.FormatterProfileReader;

import picocli.CommandLine;

import static java.lang.System.out;

@CommandLine.Command(name = "format", aliases = "fmt", header = "Format your source code", description = "%n"
        + "This command will format your sources using the same formatting rules used in the Quarkus Core project", footer = {
        "%n"
                + "For example, the following command will format the current directory:" + "%n"
                + "quarkus fmt" + "%n" })
public class fmt implements Callable<Integer> {

    public static void main(String... args) {
        int exitCode = new CommandLine(new fmt()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Properties formatterProperties;
        try (InputStream eclipseFormat = getClass().getClassLoader().getResourceAsStream("eclipse-format.xml")) {
            FormatterProfileReader reader = FormatterProfileReader.fromEclipseXml(eclipseFormat);
            formatterProperties = reader.getPropertiesFor("Quarkus");
        }
        Path root = Paths.get(".");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".java")) {
                    String source = Files.readString(file);
                    String formattedSource = Roaster.format(formatterProperties, source);
                    if (!formattedSource.equals(source)) {
                        out.println(root.relativize(file));
                        Files.writeString(file, formattedSource);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return 0;
    }
}
