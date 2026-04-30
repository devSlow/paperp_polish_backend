package com.paper.polish.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.openai")
public class AiConfig {

    private String baseUrl;
    private String apiKey;
    private String model;
}
