package com.learningmaterial.system.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer currentVersion;
    private final String existingMaterialId;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentVersion = null;
        this.existingMaterialId = null;
    }

    public ApiException(HttpStatus status, String code, String message, Integer currentVersion) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentVersion = currentVersion;
        this.existingMaterialId = null;
    }

    public ApiException(HttpStatus status, String code, String message, String existingMaterialId) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentVersion = null;
        this.existingMaterialId = existingMaterialId;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Integer currentVersion() { return currentVersion; }
    public String existingMaterialId() { return existingMaterialId; }
}
