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
@CrossOrigin(origins={"https://astra-local-agent-frontend.onrender.com"})
public class AgentController {
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private final OllamaService ollama;
    private final WindowsToolService windows;

    public AgentController(OllamaService ollama, WindowsToolService windows) {
        this.ollama = ollama;
        this.windows = windows;
    }

    @GetMapping("/health")
    public Map<String,Object> health() {
        return Map.of("status", "UP", "agent", "Astra Local v4", "ollama", ollama.health());
    }

    @PostMapping("/open-whatsapp")
    public Map<String,String> openWhatsApp() {
        return Map.of("reply", windows.openWhatsApp(), "tool", "open_whatsapp");
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String,String>> chat(@RequestBody Map<String,String> req) {
        String m = req.getOrDefault("message", "").trim();
        if (m.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("reply", "Please say or type something."));
        }
        String l = m.toLowerCase();

        // 1. Time / Date queries (Instant response)
        if (l.contains("what time") || l.equals("time") || l.contains("current time")) {
            String t = ZonedDateTime.now(INDIA).format(DateTimeFormatter.ofPattern("hh:mm a"));
            return ResponseEntity.ok(Map.of("reply", "The current time in India is " + t + ".", "tool", "current_time"));
        }

        if (l.contains("today's date") || l.contains("todays date") || l.equals("date")) {
            String d = ZonedDateTime.now(INDIA).format(DateTimeFormatter.ofPattern("dd MMMM yyyy"));
            return ResponseEntity.ok(Map.of("reply", "Today's date in India is " + d + ".", "tool", "current_date"));
        }

        // 2. Math Calculations
        if (l.startsWith("calculate ") || l.startsWith("calc ")) {
            String expr = m.replaceFirst("(?i)^(calculate|calc)\\s+", "").trim();
            String r = calculate(expr);
            if (r != null) {
                return ResponseEntity.ok(Map.of("reply", "The answer is " + r + ".", "tool", "calculator"));
            }
        }

        // 3. High-Speed Windows Native Intent Matcher (Instant execution for apps, volume, folders, websites, screenshots, etc.)
        WindowsToolService.ExecutionResult nativeResult = windows.tryExecuteNativeIntent(m);
        if (nativeResult != null) {
            return ResponseEntity.ok(Map.of("reply", nativeResult.reply(), "tool", nativeResult.tool()));
        }

        // 4. Ollama AI Agent with Windows Action Generation & Fallback Chat
        try {
            OllamaService.AiActionResponse aiResp = ollama.chatWithWindowsAction(m, windows);
            return ResponseEntity.ok(Map.of("reply", aiResp.reply(), "tool", aiResp.tool()));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                "reply", "Astra could not reach Ollama for AI generation. However, direct laptop commands (open apps, volume, folders, screenshots) remain fully functional!",
                "tool", "ollama_error"
            ));
        }
    }

    private String calculate(String s) {
        s = s.replaceAll("\\s+", "");
        if (!Pattern.matches("[0-9+\\-*/().]+", s)) return null;
        try {
            return trim(eval(s));
        } catch (Exception e) {
            return null;
        }
    }

    private double eval(String s) {
        return new Object() {
            int pos = -1, ch;
            void next() { ch = (++pos < s.length()) ? s.charAt(pos) : -1; }
            boolean eat(int c) { while (ch == ' ') next(); if (ch == c) { next(); return true; } return false; }
            double parse() { next(); double x = expr(); if (pos < s.length()) throw new RuntimeException(); return x; }
            double expr() { double x = term(); for (;;) { if (eat('+')) x += term(); else if (eat('-')) x -= term(); else return x; } }
            double term() { double x = factor(); for (;;) { if (eat('*')) x *= factor(); else if (eat('/')) x /= factor(); else return x; } }
            double factor() {
                if (eat('+')) return factor();
                if (eat('-')) return -factor();
                int start = pos;
                if (eat('(')) { double x = expr(); if (!eat(')')) throw new RuntimeException(); return x; }
                while ((ch >= '0' && ch <= '9') || ch == '.') next();
                if (start == pos) throw new RuntimeException();
                return Double.parseDouble(s.substring(start, pos));
            }
        }.parse();
    }

    private String trim(double v) {
        if (Double.isInfinite(v) || Double.isNaN(v)) return "undefined";
        return v == (long) v ? Long.toString((long) v) : Double.toString(v);
    }
}
