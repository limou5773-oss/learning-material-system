package com.learningmaterial.system.integration.model;

public final class ModelException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final boolean dispatched;

    public ModelException(String code, boolean retryable, boolean dispatched) {
        super(code);
        this.code = code;
        this.retryable = retryable;
        this.dispatched = dispatched;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean dispatched() {
        return dispatched;
    }
}
