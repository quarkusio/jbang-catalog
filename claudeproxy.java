///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.quarkus.platform:quarkus-bom:3.35.3@pom
//DEPS io.quarkus:quarkus-rest-jackson

//JAVAC_OPTIONS -parameters
//JAVA_OPTIONS -Djava.util.logging.manager=org.jboss.logmanager.LogManager --add-opens java.base/java.lang=ALL-UNNAMED

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@Path("/v1")
public class claudeproxy {

    private static final int POOL_SIZE = 2;

    private static final Map<String, String> MODEL_MAP = Map.of(
            "sonnet", "sonnet",
            "claude-sonnet-4-6", "sonnet",
            "opus", "opus",
            "claude-opus-4-6", "opus",
            "haiku", "haiku",
            "claude-haiku-4-5", "haiku");

    private static final List<ModelInfo> AVAILABLE_MODELS = List.of(
            new ModelInfo("claude-sonnet-4-6"),
            new ModelInfo("claude-opus-4-6"),
            new ModelInfo("claude-haiku-4-5"));

    private final BlockingQueue<Process> pool = new LinkedBlockingQueue<>();
    private volatile boolean running = true;

    void onStart(@Observes StartupEvent ev) {
        Log.infof("Pre-warming %d Claude CLI processes...", POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            spawnWarm("sonnet");
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
        Process p;
        while ((p = pool.poll()) != null) {
            p.destroyForcibly();
        }
        Log.info("Claude CLI process pool shut down");
    }

    private void spawnWarm(String model) {
        if (!running) return;
        try {
            var process = new ProcessBuilder(
                    "claude", "-p",
                    "--input-format", "stream-json",
                    "--output-format", "stream-json",
                    "--model", model,
                    "--no-session-persistence",
                    "--verbose",
                    "--bare")
                    .start();
            pool.offer(process);
            Log.infof("Spawned warm CLI process pid=%d (pool size=%d)", process.pid(), pool.size());
        } catch (Exception e) {
            Log.errorf(e, "Failed to spawn warm CLI process");
        }
    }

    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    public ModelsResponse listModels() {
        return new ModelsResponse(AVAILABLE_MODELS);
    }

    @POST
    @Path("/chat/completions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response chatCompletions(ChatRequest request) {
        String systemPrompt = null;
        var userMessages = new ArrayList<String>();

        for (var msg : request.messages) {
            if ("system".equals(msg.role)) {
                systemPrompt = msg.content;
            } else {
                userMessages.add(msg.role + ": " + msg.content);
            }
        }

        String prompt = String.join("\n\n", userMessages);
        if (systemPrompt != null) {
            prompt = "System: " + systemPrompt + "\n\n" + prompt;
        }
        String model = resolveModel(request.model);

        try {
            String result = callClaude(prompt, model);
            var response = new ChatResponse(
                    "chatcmpl-" + UUID.randomUUID(),
                    Instant.now().getEpochSecond(),
                    request.model != null ? request.model : "claude-sonnet-4-6",
                    result);
            return Response.ok(response).build();
        } catch (Exception e) {
            Log.errorf(e, "Claude CLI failed");
            return Response.serverError()
                    .entity(Map.of("error",
                            Map.of("message", "Claude CLI error: " + e.getMessage(),
                                    "type", "server_error")))
                    .build();
        }
    }

    private String resolveModel(String model) {
        if (model == null)
            return "sonnet";
        return MODEL_MAP.getOrDefault(model, model);
    }

    private String callClaude(String prompt, String model) throws Exception {
        long start = System.currentTimeMillis();

        Process process = pool.poll();
        if (process != null && !process.isAlive()) {
            Log.warnf("Warm process pid=%d died early (exit=%d)", process.pid(), process.exitValue());
            process = null;
        }
        boolean warm = process != null;
        if (!warm) {
            Log.info("No warm process available, spawning cold");
            process = new ProcessBuilder(
                    "claude", "-p",
                    "--input-format", "stream-json",
                    "--output-format", "stream-json",
                    "--model", model,
                    "--no-session-persistence",
                    "--verbose",
                    "--bare")
                    .start();
        }

        Log.infof("Sending prompt (length=%d, warm=%b) to pid=%d", prompt.length(), warm, process.pid());

        // Send message in stream-json format
        String jsonMsg = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
                + jsonEscape(prompt) + "},\"parent_tool_use_id\":null}";
        OutputStream os = process.getOutputStream();
        os.write(jsonMsg.getBytes(StandardCharsets.UTF_8));
        os.write('\n');
        os.flush();
        os.close();

        // Read stream-json output line by line, look for the result
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String result = null;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    var node = mapper.readTree(line);
                    String type = node.has("type") ? node.get("type").asText() : "";
                    if ("result".equals(type)) {
                        if (node.has("is_error") && node.get("is_error").asBoolean()) {
                            throw new RuntimeException("CLI error: " + line);
                        }
                        result = node.has("result") ? node.get("result").asText() : "";
                        break;
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception ignored) {}
            }
        }

        int exitCode = process.waitFor();
        long elapsed = System.currentTimeMillis() - start;
        Log.infof("Claude responded in %dms (exit=%d, warm=%b)", elapsed, exitCode, warm);

        if (exitCode != 0 && result == null) {
            String stderr = new String(process.getErrorStream().readAllBytes()).trim();
            throw new RuntimeException("CLI exited with code " + exitCode + ": " + stderr);
        }

        // Replenish the pool in the background
        Thread.startVirtualThread(() -> spawnWarm(model));

        if (result == null) {
            throw new RuntimeException("No result received from CLI");
        }
        return result;
    }

    private static String jsonEscape(String s) {
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    // --- Request/Response DTOs ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatRequest {
        public String model;
        public List<Message> messages;
        public Double temperature;
        @JsonProperty("max_tokens")
        public Integer maxTokens;
        public Boolean stream;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        public String role;
        public String content;
    }

    public static class ChatResponse {
        public String id;
        public String object = "chat.completion";
        public long created;
        public String model;
        public List<Choice> choices;
        public Usage usage;

        public ChatResponse(String id, long created, String model, String content) {
            this.id = id;
            this.created = created;
            this.model = model;
            this.choices = List.of(new Choice(content));
            this.usage = new Usage();
        }
    }

    public static class Choice {
        public int index = 0;
        public Message message;
        @JsonProperty("finish_reason")
        public String finishReason = "stop";

        public Choice(String content) {
            this.message = new Message();
            this.message.role = "assistant";
            this.message.content = content;
        }
    }

    public static class Usage {
        @JsonProperty("prompt_tokens")
        public int promptTokens = 0;
        @JsonProperty("completion_tokens")
        public int completionTokens = 0;
        @JsonProperty("total_tokens")
        public int totalTokens = 0;
    }

    public static class ModelsResponse {
        public String object = "list";
        public List<ModelInfo> data;

        public ModelsResponse(List<ModelInfo> data) {
            this.data = data;
        }
    }

    public static class ModelInfo {
        public String id;
        public String object = "model";
        public long created = Instant.now().getEpochSecond();
        @JsonProperty("owned_by")
        public String ownedBy = "anthropic";

        public ModelInfo() {}

        public ModelInfo(String id) {
            this.id = id;
        }
    }
}
