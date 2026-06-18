package com.demo.mcpclient.config;

import com.demo.mcpclient.McpClientApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ClientConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public CommandLineRunner initRegistry(ServerRegistry registry) {
        return args -> {
            registry.initialize();
        };
    }
}
