package com.learningmaterial.system.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AiToolCallRow {
    private Long id;
    private Long taskId;
    private Integer stepNo;
    private Integer attemptNo;
    private Integer executionEpoch;
    private String toolName;
    private String providerKind;
    private String outputSummary;
    private String status;
    private String errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private BigDecimal costAmount;
    private String currency;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getStepNo() { return stepNo; }
    public void setStepNo(Integer stepNo) { this.stepNo = stepNo; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public Integer getExecutionEpoch() { return executionEpoch; }
    public void setExecutionEpoch(Integer executionEpoch) { this.executionEpoch = executionEpoch; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getProviderKind() { return providerKind; }
    public void setProviderKind(String providerKind) { this.providerKind = providerKind; }
    public String getOutputSummary() { return outputSummary; }
    public void setOutputSummary(String outputSummary) { this.outputSummary = outputSummary; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public BigDecimal getCostAmount() { return costAmount; }
    public void setCostAmount(BigDecimal costAmount) { this.costAmount = costAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
