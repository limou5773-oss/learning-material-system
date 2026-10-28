package com.learningmaterial.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.integration.ocr.OcrClient;
import com.learningmaterial.system.integration.ocr.OcrException;
import com.learningmaterial.system.integration.ocr.OcrSettings;
import com.learningmaterial.system.integration.ocr.TencentOcrClient;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OcrConfiguration {
    @Bean OcrSettings ocrSettings(@Value("${lms.ocr.enabled:false}") boolean enabled) { return new OcrSettings(enabled); }
    @Bean OcrClient configuredOcrClient(@Value("${lms.ocr.provider:disabled}") String provider,
        @Value("${lms.ocr.secret-id:}") String secretId, @Value("${lms.ocr.secret-key:}") String secretKey,
        ObjectMapper json, Clock clock) {
        if ("tencent".equalsIgnoreCase(provider)) return new TencentOcrClient(secretId, secretKey, json, clock);
        return new OcrClient() {
            public String providerKey() { return "DISABLED"; }
            public Result recognize(byte[] image) { throw new OcrException("OCR_NOT_CONFIGURED", false, false); }
        };
    }
}
