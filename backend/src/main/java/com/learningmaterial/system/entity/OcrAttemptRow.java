package com.learningmaterial.system.entity;

import java.time.LocalDateTime;

public class OcrAttemptRow {
    private String currency;

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String value) {
        currency = value;
    }

    private Long id;
    private Long taskId;
    private Integer executionEpoch;
    private Integer pageNo;
    private Integer attemptNo;
    private String providerKey;
    private Boolean dispatched;
    private String status;
    private String errorCode;
    private String requestId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        id = value;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long value) {
        taskId = value;
    }

    public Integer getExecutionEpoch() {
        return executionEpoch;
    }

    public void setExecutionEpoch(Integer value) {
        executionEpoch = value;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer value) {
        pageNo = value;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Integer value) {
        attemptNo = value;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String value) {
        providerKey = value;
    }

    public Boolean getDispatched() {
        return dispatched;
    }

    public void setDispatched(Boolean value) {
        dispatched = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String value) {
        errorCode = value;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String value) {
        requestId = value;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime value) {
        startedAt = value;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime value) {
        finishedAt = value;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long value) {
        durationMs = value;
    }
}
