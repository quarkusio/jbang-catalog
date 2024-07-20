///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3
//DEPS org.zeroturnaround:zt-exec:1.12
//DEPS org.slf4j:slf4j-simple:1.7.30
//DEPS io.smallrye.common:smallrye-common-os:2.0.0

import io.smallrye.common.os.OS;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import static java.lang.System.out;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;

import org.zeroturnaround.exec.ProcessExecutor;

@Command(name = "quarkus-kill", mixinStandardHelpOptions = true, version = "0.1", description = "quarkus-kill made with jbang")
class kill implements Callable<Integer> {

    @Option(names = { "--port", "-p" }, description = "Port to try kill", defaultValue = "8080")
    int port;

    @Option(names = { "--force", "-9", "-f" }, description = "Force kill")
    boolean force;

    public static void main(String... args) {
        int exitCode = new CommandLine(new kill()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception { // your business logic goes here...

        final HashSet<String> kills = new HashSet<String>();
        System.out.println("Scanning for processes using port " + port + " ...");

        if (OS.WINDOWS.isCurrent()) {
            List<String> args = List.of("netstat", "-ano");
            String output = new ProcessExecutor().command(args)
                    .readOutput(true).execute().outputUTF8();

            output.lines().forEach(line -> {
                if (!line.contains("LISTENING")) {
                    return;
                }
                String[] parts = line.split("\\s+");
                if (parts.length >= 5) {
                    String listen = parts[2];
                    String pid = parts[parts.length-1];
                    try {
                        if(listen.endsWith(":" + port) && !"0".equals(pid)) {
                        kills.add(pid);
                        out.println("Kill " + pid);
                        List<String> cmd = new ArrayList<>();
                        cmd.add("taskkill");
                        if(force) {
                            cmd.add("/F");
                        }
                        cmd.add("/T");
                        cmd.add("/PID");
                        cmd.add(pid);
                        int res = new ProcessExecutor().command(cmd).readOutput(true).execute().getExitValue();
                        if(res!=0) {
                            System.err.println("Process " + pid + " not killed. Try again using --force");
                            kills.remove(pid);
                        }
                    }
                    } catch (Exception e) {
                        System.err.println("Error killing " + pid);
                        kills.remove(pid);
                    }
                }
            });

        } else {

            String output = new ProcessExecutor().command("lsof", "-i:" + port)
                    .readOutput(true).execute().outputUTF8();

            output.lines().skip(1).forEach(line -> {
                if (!line.contains("(LISTEN)")) {
                    return;
                }
                String[] parts = line.split("\\s+");
                String pid = parts[1];
                try {
                    out.println("Killing " + pid);
                    new ProcessExecutor().command("kill", force ? "-9" : "-15", pid).execute();
                    kills.add(pid);
                } catch (Exception e) {
                    System.err.println("Error killing " + pid);
                }
            });

            
        }

        if (kills.isEmpty()) {
            out.println("No process killed or not found any process on port " + port);
            out.println("If looking for a different port use --port <port>");
        }
        
        return 0;
    }
}
