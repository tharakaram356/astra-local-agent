package com.astra.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OllamaService {
    private final RestClient client;
    private final String model;

    public record AiActionResponse(String reply, String tool) {}

    public OllamaService(@Value("${astra.ollama.base-url}") String baseUrl,
                         @Value("${astra.ollama.model}") String model) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.model = model;
    }

    public AiActionResponse chatWithWindowsAction(String text, WindowsToolService windows) {
        String systemPrompt = """
            You are Tharak, an intelligent AI voice companion with direct execution access to the user's Windows laptop.
            When the user asks you to do something on their laptop (e.g. open an application, check hardware/system information, run a command, search, manage files, or perform a system action), you MUST format your answer as:
            ACTION: <powershell one-liner command or executable to run>
            REPLY: <short friendly spoken response explaining what you did>

            Rules for ACTION:
            - Provide a safe PowerShell one-liner or Windows command.
            - Do not use blocking or interactive scripts.
            - If no laptop action is needed (the user is just asking a question, greeting, or chatting), do NOT include ACTION:. Simply reply naturally with a warm, helpful, and concise answer.
            """;

        Map<String,Object> system = Map.of("role", "system", "content", systemPrompt);
        Map<String,Object> user = Map.of("role", "user", "content", text);
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(system, user));
        body.put("stream", false);

        Map<?,?> result = client.post()
            .uri("/api/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(Map.class);

        if (result != null && result.get("message") instanceof Map<?,?> msg && msg.get("content") != null) {
            String raw = msg.get("content").toString().trim();

            // Check if model returned an ACTION
            Pattern actionPattern = Pattern.compile("(?i)^ACTION:\\s*(.+?)(?:\\r?\\n+REPLY:\\s*(.*))?$", Pattern.DOTALL);
            Matcher matcher = actionPattern.matcher(raw);
            if (matcher.find()) {
                String actionCmd = matcher.group(1).trim();
                String reply = matcher.group(2) != null ? matcher.group(2).trim() : "";

                if (reply.isBlank()) {
                    reply = "I've executed that action on your laptop for you.";
                }

                // Clean markdown code blocks if the model enclosed it
                actionCmd = actionCmd.replaceAll("^`+|`+$", "").trim();

                if (windows != null) {
                    String output = windows.executePowerShell(actionCmd);
                    if (!output.isEmpty() && !output.startsWith("Command executed successfully") && !output.startsWith("Execution error")) {
                        String cleanOutput = output.length() > 300 ? output.substring(0, 300) + "..." : output;
                        reply = reply + "\n\nOutput:\n" + cleanOutput;
                    }
                }

                return new AiActionResponse(reply, "windows_ai_action");
            }

            if (raw.startsWith("REPLY:")) {
                raw = raw.substring(6).trim();
            }

            return new AiActionResponse(raw, "ollama_local_ai");
        }

        return new AiActionResponse("I received an empty response from the local AI.", "ollama_error");
    }

    public String chat(String text) {
        return chatWithWindowsAction(text, null).reply();
    }

    public String health() {
        try {
            client.get().uri("/api/tags").retrieve().body(Map.class);
            return "Ollama is running.";
        } catch (Exception e) {
            return "Ollama is not reachable. Run: ollama pull " + model;
        }
    }
}
