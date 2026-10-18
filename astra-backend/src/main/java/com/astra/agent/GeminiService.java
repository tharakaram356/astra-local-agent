package com.astra.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeminiService {

    public record AiActionResponse(
            String reply,
            String tool,
            boolean requiresPermission,
            String pendingAction,
            String actionDescription
    ) {}

    private final RestClient client;
    private final String apiKey;
    private final String model;

    public GeminiService(
            @Value("${astra.gemini.api-url}") String apiUrl,
            @Value("${astra.gemini.api-key}") String apiKey,
            @Value("${astra.gemini.model}") String model) {

        this.client = RestClient.builder()
                .baseUrl(apiUrl)
                .build();

        this.apiKey = apiKey;
        this.model = model;
    }

    public AiActionResponse chatWithWindowsAction(String userMessage, WindowsToolService windows, boolean executeNow) {
        String systemPrompt = """
                You are Tharak (also known as Astra AI), a powerful and friendly AI assistant running directly on the user's Windows laptop with full control over Windows.

                You have direct native access to automate this Windows laptop (such as launching applications, opening folders, searching YouTube or Google, opening websites, adjusting volume, taking screenshots, locking screen, checking battery/system info, emptying recycle bin, and executing PowerShell commands).

                When the user asks you to do something on their laptop, or whenever their request involves a Windows or browser action (even with typos or voice transcription mistakes like "open crome", "crome", "chrom", "vsc", "notpad", "edg", etc.), YOU MUST RESPOND IN THIS FORMAT:
                ACTION: <action_name> [arguments]
                REPLY: <short friendly spoken response explaining what you are doing>

                Supported ACTION formats:
                - ACTION: open_chrome [optional_url]     (e.g., user says "open crome", "launch chrome", "open google chrome", "open browser")
                - ACTION: open_app <app_name>            (e.g., notepad, calculator, paint, code, cmd, powershell, settings, edge, taskmgr, spotify, snippingtool, camera, word, excel, explorer)
                - ACTION: open_whatsapp                  (e.g., "open whatsapp", "launch whatsapp")
                - ACTION: open_folder <folder_name>      (e.g., downloads, desktop, documents, pictures, music, videos, c drive, d drive)
                - ACTION: youtube <search_query>         (e.g., "search youtube for lofi", "play jazz on youtube")
                - ACTION: google <search_query>          (e.g., "search google for weather", "google news")
                - ACTION: open_url <url>                 (e.g., "open github.com", "open https://news.ycombinator.com")
                - ACTION: volume <mute|unmute|up|down|play_pause> (e.g., "mute volume", "turn volume up")
                - ACTION: screenshot                     (e.g., "take a screenshot", "snipping tool")
                - ACTION: lock                           (e.g., "lock laptop", "lock pc")
                - ACTION: battery                        (e.g., "battery status", "what is my battery")
                - ACTION: specs                          (e.g., "system specs", "laptop specs")
                - ACTION: network                        (e.g., "what is my ip", "wifi info")
                - ACTION: empty_recycle_bin              (e.g., "empty recycle bin")
                - ACTION: create_folder <folder_name>    (e.g., "create folder named Projects")
                - ACTION: create_file <file_name>        (e.g., "create file notes.txt")
                - ACTION: powershell <command>           (for safe Windows PowerShell queries or actions)

                CRITICAL INSTRUCTIONS:
                1. NEVER say "I can't open applications on your device directly" or "I don't have access to your computer". You DO have direct control over this Windows laptop through your system tools!
                2. ALWAYS interpret misspellings and voice transcription mistakes (e.g., "open crome", "crome" -> ACTION: open_chrome).
                3. If the user's intent is to launch or open an app, folder, site, or perform an action, ALWAYS output ACTION: followed by REPLY:.
                4. If the user is just asking a question (e.g. coding question, explanations, general knowledge, greetings, math, chit-chat) where no Windows laptop action is required, DO NOT include ACTION:. Simply answer naturally, warmly, and concisely because your answer will be spoken aloud.
                """;

        Map<String, Object> body = Map.of(
                "system_instruction",
                Map.of(
                        "parts",
                        List.of(
                                Map.of("text", systemPrompt)
                        )
                ),
                "contents",
                List.of(
                        Map.of(
                                "role", "user",
                                "parts",
                                List.of(
                                        Map.of(
                                                "text",
                                                userMessage
                                        )
                                )
                        )
                )
        );

        Map<?, ?> response = client.post()
                .uri("/models/" + model + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        String raw = extractText(response);

        // Check if model returned an ACTION: ... REPLY: ...
        Pattern actionPattern = Pattern.compile("(?i)^\\s*ACTION:\\s*([^\r\n]+)(?:\\r?\\n+\\s*REPLY:\\s*(.*))?$", Pattern.DOTALL);
        Matcher matcher = actionPattern.matcher(raw);

        String actionCmd = null;
        String reply = null;

        if (matcher.find()) {
            actionCmd = matcher.group(1).trim();
            reply = matcher.group(2) != null ? matcher.group(2).trim() : "";
        } else {
            // Check for inline ACTION: or REPLY:
            Pattern inlineAction = Pattern.compile("(?i)ACTION:\\s*([^\r\n]+)");
            Matcher inlineActionMat = inlineAction.matcher(raw);
            if (inlineActionMat.find()) {
                actionCmd = inlineActionMat.group(1).trim();
                Pattern inlineReply = Pattern.compile("(?i)REPLY:\\s*(.+)");
                Matcher inlineReplyMat = inlineReply.matcher(raw);
                if (inlineReplyMat.find()) {
                    reply = inlineReplyMat.group(1).trim();
                }
            }
        }

        // Clean actionCmd if enclosed in code blocks
        if (actionCmd != null) {
            actionCmd = actionCmd.replaceAll("^`+|`+$", "").trim();
        }

        // Safety fallback guard: If Gemini claimed it cannot open apps, or user clearly wants to open an app (e.g. Chrome/crome)
        String userLower = userMessage.toLowerCase(Locale.ROOT);
        if (actionCmd == null && (raw.toLowerCase(Locale.ROOT).contains("can't open")
                || raw.toLowerCase(Locale.ROOT).contains("cannot open")
                || raw.toLowerCase(Locale.ROOT).contains("can't launch")
                || raw.toLowerCase(Locale.ROOT).contains("cannot launch")
                || userLower.contains("crome") || userLower.contains("chrome"))) {

            if (userLower.contains("chrome") || userLower.contains("crome") || userLower.contains("chrom")) {
                actionCmd = "open_chrome";
            } else if (windows != null) {
                WindowsToolService.ActionIntent intent = windows.resolveActionIntent(userMessage);
                if (intent != null) {
                    actionCmd = intent.actionCmd();
                }
            }
        }

        if (actionCmd != null && !actionCmd.isBlank()) {
            String desc = windows != null ? windows.getFriendlyDescription(actionCmd) : actionCmd;

            if (executeNow) {
                WindowsToolService.ExecutionResult execRes = windows != null ? windows.executeAction(actionCmd) : null;
                if (reply == null || reply.isBlank()) {
                    reply = execRes != null ? execRes.reply() : "I executed " + desc + " for you.";
                }
                String tool = execRes != null && execRes.tool() != null ? execRes.tool() : "windows_ai_action";
                return new AiActionResponse(reply, tool, false, null, null);
            } else {
                String permissionPrompt = "I need your permission to access this tool: " + desc + ". Would you like me to proceed?";
                return new AiActionResponse(permissionPrompt, "permission_request", true, actionCmd, desc);
            }
        }

        if (raw.startsWith("REPLY:")) {
            raw = raw.substring(6).trim();
        }

        return new AiActionResponse(raw, "gemini", false, null, null);
    }

    public AiActionResponse chatWithWindowsAction(String userMessage, WindowsToolService windows) {
        return chatWithWindowsAction(userMessage, windows, false);
    }

    public String chat(String userMessage) {
        return chatWithWindowsAction(userMessage, null, false).reply();
    }

    private String extractText(Map<?, ?> response) {

        if (response == null) {
            return "I did not receive a response from Gemini.";
        }

        try {

            List<?> candidates =
                    (List<?>) response.get("candidates");

            if (candidates == null || candidates.isEmpty()) {
                return "Gemini did not return an answer.";
            }

            Map<?, ?> candidate =
                    (Map<?, ?>) candidates.get(0);

            Map<?, ?> content =
                    (Map<?, ?>) candidate.get("content");

            List<?> parts =
                    (List<?>) content.get("parts");

            if (parts == null || parts.isEmpty()) {
                return "Gemini returned an empty answer.";
            }

            Map<?, ?> firstPart =
                    (Map<?, ?>) parts.get(0);

            Object text =
                    firstPart.get("text");

            if (text == null) {
                return "Gemini returned an empty answer.";
            }

            return text.toString().trim();

        } catch (Exception e) {

            return "I received a response from Gemini, but I could not read it.";
        }
    }

    public String health() {

        if (apiKey == null || apiKey.isBlank()) {
            return "Gemini API key is not configured.";
        }

        return "Gemini API configured.";
    }
}
