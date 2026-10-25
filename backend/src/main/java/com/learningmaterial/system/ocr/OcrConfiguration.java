package com.learningmaterial.system.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

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
