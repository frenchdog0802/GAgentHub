package com.gagent.service;

import com.gagent.dto.RunRequest;
import com.gagent.dto.RunResponse;
import com.gagent.entity.Message;
import com.gagent.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GagentService {

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    private final MessageRepository messageRepository;

    public RunResponse processRequest(RunRequest request, String userId) {
        if (openAiApiKey == null || openAiApiKey.isEmpty()) {
            return new RunResponse(
                    "Error: OpenAI API key is not configured. Please set the OPENAI_API_KEY environment variable.",
                    "error", Instant.now());
        }

        // Save user message to database
        Message userMessage = Message.builder()
                .role("user")
                .content(request.getMessage())
                .userId(userId)
                .build();
        messageRepository.save(userMessage);

        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAiApiKey);

            String systemPrompt = "You are an intelligent Google Workspace AI assistant. " +
                    "You have access to the conversation history. You MUST use the past messages to remember the user's name, activities, and context. "
                    +
                    "Your capabilities include managing emails (Gmail), creating and editing documents (Google Docs), "
                    +
                    "scheduling meetings and sending invites (Google Calendar), and uploading or managing files (Google Drive). "
                    +
                    "You must assist the user efficiently and return clear, well-written, and professional responses.";

            List<Message> history = messageRepository.findByUserIdOrderByCreatedAtAsc(userId);
            List<Map<String, String>> apiMessages = new java.util.ArrayList<>();
            apiMessages.add(Map.of("role", "system", "content", systemPrompt));

            for (Message msg : history) {
                if (msg.getRole() != null && msg.getContent() != null) {
                    apiMessages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
                    System.out.println("DEGUG: Message added to history: " + msg.getRole() + ": " + msg.getContent());
                }
            }

            Map<String, Object> requestBody = Map.of(
                    "model", "gpt-4o-mini",
                    "messages", apiMessages);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://api.openai.com/v1/chat/completions",
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null && responseBody.containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) message.get("content");

                    // Save assistant message to database
                    Message aiMessage = Message.builder()
                            .role("assistant")
                            .content(content)
                            .userId(userId)
                            .build();
                    messageRepository.save(aiMessage);

                    return new RunResponse(content, "success", Instant.now());
                }
            }

            return new RunResponse("Error: Empty response from OpenAI.", "error", Instant.now());

        } catch (Exception e) {
            e.printStackTrace();
            return new RunResponse("Error calling OpenAI API: " + e.getMessage(), "error", Instant.now());
        }
    }
}
