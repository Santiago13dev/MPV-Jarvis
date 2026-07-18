package com.whatsappmvp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableScheduling
public class WhatsappMvpApplication {

    public static void main(String[] args) {
        SpringApplication.run(WhatsappMvpApplication.class, args);
        log.info("🚀 WhatsApp MVP Backend started successfully");
    }
}
