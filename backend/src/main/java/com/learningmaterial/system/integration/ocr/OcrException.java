package com.learningmaterial.system.integration.ocr;

/** Only application error codes are exposed; provider bodies and credentials are never exception messages. */
public final class OcrException extends RuntimeException {
    private final boolean retryable;
    private final boolean dispatched;
    public OcrException(String code, boolean retryable, boolean dispatched) {
        super(code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "OCR_PROVIDER_FAILED");
        this.retryable = retryable; this.dispatched = dispatched;
    }
    public String code() { return getMessage(); }
    public boolean retryable() { return retryable; }
    public boolean dispatched() { return dispatched; }
}
