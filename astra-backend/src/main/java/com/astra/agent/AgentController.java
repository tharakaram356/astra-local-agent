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

    public AgentController(GeminiService gemini) {
        this.gemini = gemini;
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
    // CHAT
    // ---------------------------------------------------------

    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(
            @RequestBody Map<String, String> req) {

        String message = req.getOrDefault("message", "").trim();

        if (message.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "reply", "Please say or type something.",
                            "tool", "validation"
                    ));
        }

        String lower = message.toLowerCase();

        // -----------------------------------------------------
        // CURRENT TIME
        // -----------------------------------------------------

        if (lower.contains("what time")
                || lower.equals("time")
                || lower.contains("current time")) {

            String time = ZonedDateTime
                    .now(INDIA)
                    .format(DateTimeFormatter.ofPattern("hh:mm a"));

            return ResponseEntity.ok(
                    Map.of(
                            "reply",
                            "The current time in India is " + time + ".",
                            "tool",
                            "current_time"
                    )
            );
        }

        // -----------------------------------------------------
        // CURRENT DATE
        // -----------------------------------------------------

        if (lower.contains("today's date")
                || lower.contains("todays date")
                || lower.equals("date")
                || lower.contains("current date")) {

            String date = ZonedDateTime
                    .now(INDIA)
                    .format(DateTimeFormatter.ofPattern("dd MMMM yyyy"));

            return ResponseEntity.ok(
                    Map.of(
                            "reply",
                            "Today's date in India is " + date + ".",
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

            String expression = message
                    .replaceFirst(
                            "(?i)^(calculate|calc)\\s+",
                            ""
                    )
                    .trim();

            String result = calculate(expression);

            if (result != null) {

                return ResponseEntity.ok(
                        Map.of(
                                "reply",
                                "The answer is " + result + ".",
                                "tool",
                                "calculator"
                        )
                );
            }
        }

        // -----------------------------------------------------
        // GEMINI AI
        // -----------------------------------------------------

        try {

            String reply = gemini.chat(message);

            return ResponseEntity.ok(
                    Map.of(
                            "reply", reply,
                            "tool", "gemini"
                    )
            );

        } catch (Exception e) {

            return ResponseEntity
                    .status(503)
                    .body(
                            Map.of(
                                    "reply",
                                    "Astra AI could not connect to Gemini right now. Please try again.",
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

        expression = expression.replaceAll("\\s+", "");

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
