package com.learningmaterial.system.task;

import java.time.LocalDateTime;

public class ModelAttemptRow {
    private Integer stepNo;
    private Integer stepAttemptNo;
    private String currency;

    public Integer getStepNo() {
        return stepNo;
    }

    public void setStepNo(Integer value) {
        stepNo = value;
    }

    public Integer getStepAttemptNo() {
        return stepAttemptNo;
    }

    public void setStepAttemptNo(Integer value) {
        stepAttemptNo = value;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String value) {
        currency = value;
    }

    private String purpose = "GENERATION";

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String value) {
        purpose = value;
    }

    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        id = value;
    }

    private Long taskId;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long value) {
        taskId = value;
    }

    private Integer executionEpoch;

    public Integer getExecutionEpoch() {
        return executionEpoch;
    }

    public void setExecutionEpoch(Integer value) {
        executionEpoch = value;
    }

    private Integer attemptNo;

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Integer value) {
        attemptNo = value;
    }

    private String providerKey;

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String value) {
        providerKey = value;
    }

    private String requestedModel;

    public String getRequestedModel() {
        return requestedModel;
    }

    public void setRequestedModel(String value) {
        requestedModel = value;
    }

    private String responseModel;

    public String getResponseModel() {
        return responseModel;
    }

    public void setResponseModel(String value) {
        responseModel = value;
    }

    private String promptVersion;

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String value) {
        promptVersion = value;
    }

    private Boolean dispatched;

    public Boolean getDispatched() {
        return dispatched;
    }

    public void setDispatched(Boolean value) {
        dispatched = value;
    }

    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    private String errorCode;

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String value) {
        errorCode = value;
    }

    private String requestId;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String value) {
        requestId = value;
    }

    private Long inputTokens;

    public Long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Long value) {
        inputTokens = value;
    }

    private Long outputTokens;

    public Long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Long value) {
        outputTokens = value;
    }

    private LocalDateTime startedAt;

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime value) {
        startedAt = value;
    }

    private LocalDateTime finishedAt;

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime value) {
        finishedAt = value;
    }

    private Long durationMs;

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long value) {
        durationMs = value;
    }
}
