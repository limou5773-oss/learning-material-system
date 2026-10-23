package com.learningmaterial.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.core.env.Environment;

import java.util.List;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class LearningMaterialApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(LearningMaterialApplication.class);
        application.addInitializers(context -> requireDatabaseSettings(context.getEnvironment()));
        application.run(args);
    }

    private static void requireDatabaseSettings(Environment environment) {
        for (String key : List.of("DB_URL", "DB_USERNAME", "DB_PASSWORD")) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing required database setting: " + key);
            }
        }
    }
}
