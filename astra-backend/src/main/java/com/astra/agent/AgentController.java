package com.astra.agent;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = {
        "https://astra-local-agent-frontend.onrender.com",
        "http://localhost:4300",
        "http://localhost:4200"
})
public class AgentController {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final GeminiService gemini;
    private final WindowsToolService windows;

    public AgentController(
            GeminiService gemini,
            WindowsToolService windows) {

        this.gemini = gemini;
        this.windows = windows;
    }

    public record PendingAction(String actionCmd, String description, String tool) {}

    private final java.util.concurrent.ConcurrentHashMap<String, PendingAction> pendingActions =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ---------------------------------------------------------
    // HEALTH
    // ---------------------------------------------------------

    @GetMapping("/health")
    public Map<String, Object> health() {

        return Map.of(
                "status", "UP",
                "agent", "Astra AI",
                "ai", gemini.health()
        );
    }

    // ---------------------------------------------------------
    // EXECUTE (AFTER PERMISSION GRANTED)
    // ---------------------------------------------------------

    @PostMapping("/execute")
    public ResponseEntity<Map<String, String>> execute(
            @RequestBody Map<String, String> req) {

        String session = req.getOrDefault("sessionId", "default");
        String action = req.getOrDefault("action", "").trim();

        PendingAction pending = pendingActions.remove(session);
        if (action.isBlank() && pending != null) {
            action = pending.actionCmd();
        }

        if (action.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "reply", "No action specified to execute.",
                    "tool", "error"
            ));
        }

        WindowsToolService.ExecutionResult res = windows.executeAction(action);
        String desc = windows.getFriendlyDescription(action);
        String reply = "Permission granted! " + (res != null ? res.reply() : "Executed " + desc + ".");

        return ResponseEntity.ok(Map.of(
                "reply", reply,
                "tool", res != null ? res.tool() : "windows_action",
                "status", "approved"
        ));
    }

    // ---------------------------------------------------------
    // DENY PERMISSION
    // ---------------------------------------------------------

    @PostMapping("/deny")
    public ResponseEntity<Map<String, String>> deny(
            @RequestBody Map<String, String> req) {

        String session = req.getOrDefault("sessionId", "default");
        PendingAction pending = pendingActions.remove(session);
        String desc = pending != null ? pending.description() : "the requested tool";

        return ResponseEntity.ok(Map.of(
                "reply", "Permission denied. I cancelled the request to " + desc + ".",
                "tool", "permission_denied",
                "status", "denied"
        ));
    }

    // ---------------------------------------------------------
    // CHAT
    // ---------------------------------------------------------

    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(
            @RequestBody Map<String, String> req) {

        String message =
                req.getOrDefault("message", "").trim();
        String session =
                req.getOrDefault("sessionId", "default");
        boolean confirmed =
                Boolean.parseBoolean(req.getOrDefault("confirmed", "false"));

        if (message.isBlank()) {

            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "reply",
                            "Please say or type something.",
                            "tool",
                            "validation"
                    ));
        }

        String lower =
                message.toLowerCase();

        // -----------------------------------------------------
        // CHECK PENDING ACTION CONFIRMATION (VIA TEXT OR VOICE)
        // -----------------------------------------------------

        PendingAction pending = pendingActions.get(session);
        if (pending != null) {
            String trimmedLower = lower.trim().replaceAll("[.!?]+$", "");
            boolean isConfirm = trimmedLower.matches("(?i)^(yes|yep|yeah|sure|ok|okay|confirm|allow|proceed|go ahead|do it|granted|please do)$");
            boolean isDeny = trimmedLower.matches("(?i)^(no|nope|nah|deny|cancel|stop|don't|do not|nevermind|abort)$");

            if (isConfirm) {
                pendingActions.remove(session);
                WindowsToolService.ExecutionResult execRes = windows.executeAction(pending.actionCmd());
                String reply = "Permission granted! " + (execRes != null ? execRes.reply() : "Executed " + pending.description() + ".");
                return ResponseEntity.ok(Map.of(
                        "reply", reply,
                        "tool", execRes != null ? execRes.tool() : pending.tool(),
                        "status", "approved"
                ));
            }

            if (isDeny) {
                pendingActions.remove(session);
                return ResponseEntity.ok(Map.of(
                        "reply", "Permission denied. I cancelled the request to " + pending.description() + ".",
                        "tool", "permission_denied",
                        "status", "denied"
                ));
            }
        }

        // -----------------------------------------------------
        // TIME (NO PERMISSION REQUIRED)
        // -----------------------------------------------------

        if (lower.contains("what time")
                || lower.equals("time")
                || lower.contains("current time")) {

            String time = ZonedDateTime
                    .now(INDIA)
                    .format(
                            DateTimeFormatter.ofPattern(
                                    "hh:mm a"
                            )
                    );

            return ResponseEntity.ok(
                    Map.of(
                            "reply",
                            "The current time in India is "
                                    + time + ".",
                            "tool",
                            "current_time"
                    )
            );
        }

        // -----------------------------------------------------
        // DATE (NO PERMISSION REQUIRED)
        // -----------------------------------------------------

        if (lower.contains("today's date")
                || lower.contains("todays date")
                || lower.equals("date")
                || lower.contains("current date")) {

            String date = ZonedDateTime
                    .now(INDIA)
                    .format(
                            DateTimeFormatter.ofPattern(
                                    "dd MMMM yyyy"
                            )
                    );

            return ResponseEntity.ok(
                    Map.of(
                            "reply",
                            "Today's date in India is "
                                    + date + ".",
                            "tool",
                            "current_date"
                    )
            );
        }

        // -----------------------------------------------------
        // CALCULATOR (NO PERMISSION REQUIRED)
        // -----------------------------------------------------

        if (lower.startsWith("calculate ")
                || lower.startsWith("calc ")) {

            String expression =
                    message.replaceFirst(
                            "(?i)^(calculate|calc)\\s+",
                            ""
                    ).trim();

            String result = calculate(expression);

            if (result != null) {

                return ResponseEntity.ok(
                        Map.of(
                                "reply",
                                "The answer is "
                                        + result + ".",
                                "tool",
                                "calculator"
                        )
                );
            }
        }

        // -----------------------------------------------------
        // WINDOWS NATIVE INTENT (REQUIRES PERMISSION BEFORE ACCESS)
        // -----------------------------------------------------

        try {

            WindowsToolService.ActionIntent
                    nativeIntent = windows.resolveActionIntent(message);

            if (nativeIntent != null) {

                if (confirmed) {
                    WindowsToolService.ExecutionResult res = windows.executeAction(nativeIntent.actionCmd());
                    return ResponseEntity.ok(Map.of(
                            "reply", res != null ? res.reply() : "Executed " + nativeIntent.description(),
                            "tool", res != null ? res.tool() : nativeIntent.tool(),
                            "status", "approved"
                    ));
                }

                pendingActions.put(session, new PendingAction(nativeIntent.actionCmd(), nativeIntent.description(), nativeIntent.tool()));

                return ResponseEntity.ok(
                        Map.of(
                                "reply",
                                "I need your permission to access this tool: "
                                        + nativeIntent.description()
                                        + ". Would you like me to proceed?",
                                "tool",
                                "permission_request",
                                "requiresPermission",
                                "true",
                                "pendingAction",
                                nativeIntent.actionCmd(),
                                "actionDescription",
                                nativeIntent.description()
                        )
                );
            }

        } catch (Exception e) {

            return ResponseEntity.status(500)
                    .body(
                            Map.of(
                                    "reply",
                                    "I found a Windows command, "
                                            + "but it could not be processed.",
                                    "tool",
                                    "windows_error"
                            )
                    );
        }

        // -----------------------------------------------------
        // GEMINI AI (ASK PERMISSION IF ACTION RETURNED)
        // -----------------------------------------------------

        try {

            GeminiService.AiActionResponse actionResponse =
                    gemini.chatWithWindowsAction(message, windows, confirmed);

            if (actionResponse.requiresPermission() && actionResponse.pendingAction() != null) {
                pendingActions.put(session, new PendingAction(actionResponse.pendingAction(), actionResponse.actionDescription(), actionResponse.tool()));

                return ResponseEntity.ok(
                        Map.of(
                                "reply",
                                actionResponse.reply(),
                                "tool",
                                "permission_request",
                                "requiresPermission",
                                "true",
                                "pendingAction",
                                actionResponse.pendingAction(),
                                "actionDescription",
                                actionResponse.actionDescription()
                        )
                );
            }

            return ResponseEntity.ok(
                    Map.of(
                            "reply",
                            actionResponse.reply(),
                            "tool",
                            actionResponse.tool()
                    )
            );

        } catch (Exception e) {

            return ResponseEntity.status(503)
                    .body(
                            Map.of(
                                    "reply",
                                    "Astra AI could not connect "
                                            + "to Gemini right now.",
                                    "tool",
                                    "gemini_error"
                            )
                    );
        }
    }

    // ---------------------------------------------------------
    // CALCULATOR
    // ---------------------------------------------------------

    private String calculate(String expression) {

        expression =
                expression.replaceAll("\\s+", "");

        if (!Pattern.matches(
                "[0-9+\\-*/().]+",
                expression)) {

            return null;
        }

        try {

            return trim(eval(expression));

        } catch (Exception e) {

            return null;
        }
    }

    private double eval(String expression) {

        return new Object() {

            int position = -1;
            int character;

            void next() {

                character =
                        (++position < expression.length())
                                ? expression.charAt(position)
                                : -1;
            }

            boolean eat(int c) {

                while (character == ' ') {
                    next();
                }

                if (character == c) {
                    next();
                    return true;
                }

                return false;
            }

            double parse() {

                next();

                double value = expr();

                if (position < expression.length()) {
                    throw new RuntimeException();
                }

                return value;
            }

            double expr() {

                double value = term();

                while (true) {

                    if (eat('+')) {

                        value += term();

                    } else if (eat('-')) {

                        value -= term();

                    } else {

                        return value;
                    }
                }
            }

            double term() {

                double value = factor();

                while (true) {

                    if (eat('*')) {

                        value *= factor();

                    } else if (eat('/')) {

                        value /= factor();

                    } else {

                        return value;
                    }
                }
            }

            double factor() {

                if (eat('+')) {
                    return factor();
                }

                if (eat('-')) {
                    return -factor();
                }

                int start = position;

                if (eat('(')) {

                    double value = expr();

                    if (!eat(')')) {
                        throw new RuntimeException();
                    }

                    return value;
                }

                while ((character >= '0'
                        && character <= '9')
                        || character == '.') {

                    next();
                }

                if (start == position) {
                    throw new RuntimeException();
                }

                return Double.parseDouble(
                        expression.substring(
                                start,
                                position
                        )
                );
            }

        }.parse();
    }

    private String trim(double value) {

        if (Double.isInfinite(value)
                || Double.isNaN(value)) {

            return "undefined";
        }

        return value == (long) value
                ? Long.toString((long) value)
                : Double.toString(value);
    }
}
