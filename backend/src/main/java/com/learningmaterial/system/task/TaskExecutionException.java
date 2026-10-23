package com.learningmaterial.system.task;

public class TaskExecutionException extends RuntimeException {
    private final String errorCode;

    public TaskExecutionException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String errorCode() { return errorCode; }
}
