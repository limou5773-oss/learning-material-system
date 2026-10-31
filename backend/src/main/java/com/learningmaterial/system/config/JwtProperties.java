package com.learningmaterial.system.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lms.jwt")
public record JwtProperties(String secret, String issuer, Duration ttl) {
    public JwtProperties {
        if (secret == null || secret.isBlank() || issuer == null || issuer.isBlank()
                || ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Configure lms.jwt.secret, issuer and ttl (at least 1 second)");
        }
    }
}
