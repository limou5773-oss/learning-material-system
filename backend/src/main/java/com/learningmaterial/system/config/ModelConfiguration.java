package com.learningmaterial.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.integration.model.DeepSeekModelClient;
import com.learningmaterial.system.integration.model.ModelClient;
import com.learningmaterial.system.integration.model.ModelException;
import com.learningmaterial.system.integration.model.ModelSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelConfiguration {
    @Bean
    public ModelSettings modelSettings(
            @Value("${lms.model.provider:disabled}") String provider,
            @Value("${lms.model.name:deepseek-flash}") String model) {
        return new ModelSettings(provider, model);
    }

    @Bean
    public ModelClient configuredModelClient(
            ModelSettings settings,
            @Value("${lms.model.api-key:}") String apiKey,
            ObjectMapper json) {
        if ("deepseek".equalsIgnoreCase(settings.provider()))
            return new DeepSeekModelClient(apiKey, json);
        return new ModelClient() {
            @Override
            public String providerKey() {
                return "DISABLED";
            }

            @Override
            public Result generate(String model, String systemPrompt, String userPrompt) {
                throw new ModelException("MODEL_NOT_CONFIGURED", false, false);
            }
        };
    }
}
