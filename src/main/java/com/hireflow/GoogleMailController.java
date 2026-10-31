package com.hireflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
public class GoogleMailController {

    @Value("${google.client-id}")
    private String clientId;

    @Value("${google.client-secret}")
    private String clientSecret;

    @Value("${google.redirect-uri}")
    private String redirectUri;

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Store token in memory so you don't have to re-login every test!
    private String latestAccessToken = null;
    private String connectedUserEmail = null;

    // 1. STEP A: Click this link to start Google Login
    @GetMapping("/api/v1/mail/connect-google")
    public RedirectView connectGoogle() {
        String scope = "https://www.googleapis.com/auth/gmail.readonly email profile";

        String googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=" + clientId +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8) +
                "&response_type=code" +
                "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8) +
                "&access_type=offline" +
                "&prompt=consent";

        return new RedirectView(googleAuthUrl);
    }

    // 2. STEP B: Callback from Google
    @GetMapping("/api/v1/oauth2/callback/google")
    public ResponseEntity<Map<String, Object>> handleGoogleCallback(@RequestParam("code") String code) {
        try {
            // Exchange code for tokens
            String tokenUrl = "https://oauth2.googleapis.com/token";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("code", code);
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("redirect_uri", redirectUri);
            body.add("grant_type", "authorization_code");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> tokenResponse = restTemplate.postForEntity(tokenUrl, request, String.class);

            JsonNode tokenJson = objectMapper.readTree(tokenResponse.getBody());
            this.latestAccessToken = tokenJson.get("access_token").asText();
            this.connectedUserEmail = fetchUserEmail(this.latestAccessToken);

            // Run search
            return performResumeSearch("has:attachment (filename:pdf OR filename:docx)");

        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "ERROR");
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // 3. STEP C: Dedicated Instant Search Endpoint (No login required once connected!)
    @GetMapping("/api/v1/mail/search")
    public ResponseEntity<Map<String, Object>> searchMail(
            @RequestParam(value = "q", defaultValue = "has:attachment (filename:pdf OR filename:docx)") String query) {
        
        if (this.latestAccessToken == null) {
            Map<String, Object> notConnected = new HashMap<>();
            notConnected.put("status", "NOT_CONNECTED");
            notConnected.put("message", "Please visit /api/v1/mail/connect-google first to login once!");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(notConnected);
        }

        return performResumeSearch(query);
    }

    // Helper: Execute search with URI to prevent double-encoding
    private ResponseEntity<Map<String, Object>> performResumeSearch(String query) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Map<String, String>> matchingResumes = searchGmailForResumes(this.latestAccessToken, query);

            result.put("status", "SUCCESS");
            result.put("connected_mailbox", this.connectedUserEmail);
            result.put("search_filter_used", query);
            result.put("total_resume_emails_found", matchingResumes.size());
            result.put("extracted_resumes", matchingResumes);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    private String fetchUserEmail(String accessToken) throws Exception {
        String profileUrl = "https://gmail.googleapis.com/gmail/v1/users/me/profile";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(profileUrl, HttpMethod.GET, entity, String.class);
        JsonNode profileJson = objectMapper.readTree(response.getBody());
        return profileJson.get("emailAddress").asText();
    }

    private List<Map<String, String>> searchGmailForResumes(String accessToken, String query) throws Exception {
        // Fix: Use URI object so Spring RestTemplate does NOT double-encode the spaces/brackets
        URI searchUri = UriComponentsBuilder.fromHttpUrl("https://gmail.googleapis.com/gmail/v1/users/me/messages")
                .queryParam("q", query)
                .queryParam("maxResults", 10)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(searchUri, HttpMethod.GET, entity, String.class);

        JsonNode searchResult = objectMapper.readTree(response.getBody());
        List<Map<String, String>> resumesList = new ArrayList<>();

        if (searchResult.has("messages")) {
            for (JsonNode msgNode : searchResult.get("messages")) {
                String messageId = msgNode.get("id").asText();

                URI msgDetailUri = UriComponentsBuilder.fromHttpUrl("https://gmail.googleapis.com/gmail/v1/users/me/messages/" + messageId)
                        .build()
                        .toUri();

                ResponseEntity<String> detailResponse = restTemplate.exchange(msgDetailUri, HttpMethod.GET, entity, String.class);
                JsonNode msgDetail = objectMapper.readTree(detailResponse.getBody());

                Map<String, String> resumeInfo = new HashMap<>();
                resumeInfo.put("message_id", messageId);

                // Extract Headers
                JsonNode headersList = msgDetail.path("payload").path("headers");
                for (JsonNode header : headersList) {
                    String name = header.path("name").asText();
                    if ("Subject".equalsIgnoreCase(name)) {
                        resumeInfo.put("subject", header.path("value").asText());
                    } else if ("From".equalsIgnoreCase(name)) {
                        resumeInfo.put("from", header.path("value").asText());
                    } else if ("Date".equalsIgnoreCase(name)) {
                        resumeInfo.put("date", header.path("value").asText());
                    }
                }

                // Extract Attachment Filename
                findAttachments(msgDetail.path("payload"), resumeInfo);

                resumesList.add(resumeInfo);
            }
        }
        return resumesList;
    }

    // Recursive helper to find attachments even in nested multipart emails
    private void findAttachments(JsonNode part, Map<String, String> resumeInfo) {
        String filename = part.path("filename").asText();
        if (!filename.isEmpty()) {
            resumeInfo.put("attachment_filename", filename);
        }
        if (part.has("parts")) {
            for (JsonNode subPart : part.get("parts")) {
                findAttachments(subPart, resumeInfo);
            }
        }
    }
}