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
    // DIRECT EXECUTE
    // ---------------------------------------------------------

    @PostMapping("/execute")
    public ResponseEntity<Map<String, String>> execute(
            @RequestBody Map<String, String> req) {

        String action = req.getOrDefault("action", "").trim();

        if (action.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "reply", "No action specified to execute.",
                    "tool", "error"
            ));
        }

        WindowsToolService.ExecutionResult res = windows.executeAction(action);
        return ResponseEntity.ok(Map.of(
                "reply", res != null ? res.reply() : "Action executed.",
                "tool", res != null ? res.tool() : "windows_action"
        ));
    }

    // ---------------------------------------------------------
    // CHAT (AUTOMATIC EXECUTION - NO PERMISSION POPUPS)
    // ---------------------------------------------------------

    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(
            @RequestBody Map<String, String> req) {

        String message =
                req.getOrDefault("message", "").trim();

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
        // TIME
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
        // DATE
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
        // CALCULATOR
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
        // WINDOWS COMMANDS (AUTOMATIC EXECUTION)
        // -----------------------------------------------------

        try {

            WindowsToolService.ActionIntent nativeIntent =
                    windows.resolveActionIntent(message);

            if (nativeIntent != null) {
                WindowsToolService.ExecutionResult res =
                        windows.executeAction(nativeIntent.actionCmd());

                return ResponseEntity.ok(
                        Map.of(
                                "reply",
                                res != null ? res.reply() : "Opened " + nativeIntent.description() + ".",
                                "tool",
                                res != null ? res.tool() : nativeIntent.tool()
                        )
                );
            }

            WindowsToolService.ExecutionResult nativeResult =
                    windows.tryExecuteNativeIntent(message);

            if (nativeResult != null) {

                return ResponseEntity.ok(
                        Map.of(
                                "reply",
                                nativeResult.reply(),
                                "tool",
                                nativeResult.tool()
                        )
                );
            }

        } catch (Exception e) {

            return ResponseEntity.status(500)
                    .body(
                            Map.of(
                                    "reply",
                                    "I found a Windows command, but it could not be executed.",
                                    "tool",
                                    "windows_error"
                            )
                    );
        }

        // -----------------------------------------------------
        // GEMINI AI (AUTOMATIC ACTION EXECUTION)
        // -----------------------------------------------------

        try {

            GeminiService.AiActionResponse actionResponse =
                    gemini.chatWithWindowsAction(message, windows);

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
                                    "Astra AI could not connect to Gemini right now.",
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
