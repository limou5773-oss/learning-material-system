package com.learningmaterial.system.integration.ocr;

public interface OcrClient {
    String providerKey();
    Result recognize(byte[] image);
    record Result(String text, Double confidence, String requestId) { }
}
