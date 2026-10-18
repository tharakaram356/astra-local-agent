
package com.astra.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

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

    public String chat(String userMessage) {

        String systemPrompt = """
                You are Astra AI, a helpful and friendly voice assistant.

                Keep your answers clear and reasonably short because
                your responses may be spoken aloud.

                You can help with Java, Spring Boot, Angular,
                programming, Windows, troubleshooting, learning,
                and general questions.
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

        return extractText(response);
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
