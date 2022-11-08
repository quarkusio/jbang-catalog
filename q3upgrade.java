///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+

import static java.lang.System.out;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

class q3upgrade {

    static String recipeURL = "https://raw.githubusercontent.com/quarkusio/quarkus/main/jakarta/quarkus3.yml";

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

    static String gradleInit = """
            initscript {
                repositories {
                    maven { url "https://plugins.gradle.org/m2" }
                }

                dependencies {
                    classpath("org.openrewrite:plugin:latest.release")
                }
            }

            addListener(new BuildInfoPluginListener())

            allprojects {
                project.afterEvaluate {
                    if (!project.plugins.hasPlugin(org.openrewrite.gradle.RewritePlugin)) {
                        project.plugins.apply(org.openrewrite.gradle.RewritePlugin)
                    }
                }
                dependencies {
                  rewrite("org.openrewrite:rewrite-java")
                }
                rewrite {
                  configFile = project.getRootProject().file("%rewritefile%")
                  activeRecipe("io.quarkus.openrewrite.Quarkus3")
                }
            }

            class BuildInfoPluginListener extends BuildAdapter {

                def void projectsLoaded(Gradle gradle) {
                    Project root = gradle.getRootProject()
                    if (!"buildSrc".equals(root.name)) {
                        root.allprojects {
                            apply {
                                apply plugin: org.openrewrite.gradle.RewritePlugin
                            }
                        }
                    }
                }
            }
            """;

    public static void main(String[] args) throws IOException, InterruptedException {

        System.out.println("This script will attempt to upgrade your Quarkus project to be compatible with Quarkus 3.");
        out.println("It will change files on disk - make sure to have all files commited or some other kind of backup before running it.");
        out.println("Waiting 3 seconds before starting...");
        int i=3;
        while(i>0) {
            out.println(i--);
            Thread.sleep(1000);
        }
        if (hasGradle(Path.of(System.getProperty("user.dir")))) {
            if (!Files.exists(Path.of(gradleCommand()))) {
                System.err.println("Cannot locate " + gradleCommand()
                        + ", currently only support Maven or Gradle for migration. Is the current directory in root of your Quarkus project?");
                System.exit(-1);
            }
            
            System.err.println(
                    "WARNING: Deteced Gradle build file. Upgrading dependencies in Gradle not yet supported. Migration will only update sources.");
            Path tempfile = downloadRecipe();

            Path tempInit = Files.createTempFile("initbuild", "gradle");
            out.println("Generating tempoary gradle init script.");
            Files.writeString(tempInit, gradleInit
                    .replace("%rewritefile%", tempfile.toAbsolutePath().toString()));

            String[] command = new String[] {
                    gradleCommand(),
                    "--init-script",
                    tempInit.toAbsolutePath().toString(),
                    "rewriteRun",
            };

            executeCommand(command);

        } else if (hasMaven(Path.of(System.getProperty("user.dir")))) {
            if (!Files.exists(Path.of(mvnCommand()))) {
                System.err.println("Cannot locate " + mvnCommand()
                        + ", currently only support Maven or Gradle for migration. Is the current directory in root of your Quarkus project?");
                System.exit(-1);
            }

            Path tempfile = downloadRecipe();

            String[] command = new String[] {
                    mvnCommand(),
                    "org.openrewrite.maven:rewrite-maven-plugin:4.36.0:run",
                    "-Drewrite.configLocation=" + tempfile.toAbsolutePath(),
                    "-DactiveRecipes=io.quarkus.openrewrite.Quarkus3"
            };

            executeCommand(command);

        } else {
            System.err.println("Could not find pom.xml nor build.gradle file in current directory.");
        }
    }

    private static void executeCommand(String[] command) {
        ProcessBuilder processBuilder = new ProcessBuilder();

        System.out.println("Executing:\n" + String.join(" ", command));
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

    private static String mvnCommand() {
        return isWindows() ? "./mvnw.cmd" : "./mvnw";
    }

    private static String gradleCommand() {
        return isWindows() ? "./gradlew.cmd" : "./gradlew";
    }

    private static Path downloadRecipe() throws MalformedURLException, IOException, ProtocolException {
        URL url = new URL(recipeURL);

        out.println("Downloading " + recipeURL + " to temporary file.");

        HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
        httpConn.setRequestMethod("GET");
        Path tempfile = null;

        InputStream responseStream = httpConn.getResponseCode() / 100 == 2
                ? httpConn.getInputStream()
                : httpConn.getErrorStream();
        try (Scanner s = new Scanner(responseStream).useDelimiter("\\A")) {
            String response = s.hasNext() ? s.next() : "";
            tempfile = Files.createTempFile("quarkus3", "yml");
            Files.writeString(tempfile, response);
        }
        return tempfile;
    }

}
