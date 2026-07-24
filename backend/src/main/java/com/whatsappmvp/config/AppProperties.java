package com.whatsappmvp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private OpenAI openai = new OpenAI();
    private WhatsApp whatsapp = new WhatsApp();
    private Admin admin = new Admin();
    private RateLimit rateLimit = new RateLimit();
    private Uploads uploads = new Uploads();

    @Data
    public static class Jwt {
        private String secret;
        private long expirationMs;
        private long refreshExpirationMs;
    }

    @Data
    public static class OpenAI {
        private String apiKey;
        private String model;
        private int maxTokens;
        private double temperature;
        private String baseUrl;
    }

    @Data
    public static class WhatsApp {
        private String serviceUrl;
        private String webhookSecret;
    }

    @Data
    public static class Admin {
        private String email;
        private String password;
        private String fullName;
        private String phone;
    }

    @Data
    public static class RateLimit {
        private int maxMessagesPerWindow;
        private int windowMinutes;
    }

    @Data
    public static class Uploads {
        private String basePath;
        private String baseUrl;
    }
}
