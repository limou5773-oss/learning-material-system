CREATE TABLE model_attempt (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT NOT NULL, execution_epoch INT NOT NULL, attempt_no INT NOT NULL,
 provider_key VARCHAR(96) NOT NULL, requested_model VARCHAR(128) NOT NULL,
 response_model VARCHAR(128) NULL, prompt_version VARCHAR(64) NOT NULL,
 dispatched BOOLEAN NOT NULL, status VARCHAR(16) NOT NULL, error_code VARCHAR(64) NULL,
 request_id VARCHAR(128) NULL, input_tokens BIGINT NULL, output_tokens BIGINT NULL,
 started_at DATETIME(6) NOT NULL, finished_at DATETIME(6) NULL, duration_ms BIGINT NULL,
 cost DECIMAL(12,6) NULL,
 UNIQUE KEY uk_model_task_attempt(task_id,attempt_no),
 KEY idx_model_attempt_epoch(task_id,execution_epoch,status),
 CONSTRAINT fk_model_attempt_task FOREIGN KEY(task_id) REFERENCES ai_task(id),
 CONSTRAINT ck_model_attempt_status CHECK(status IN ('RUNNING','SUCCESS','FAILED','UNKNOWN')),
 CONSTRAINT ck_model_attempt_number CHECK(attempt_no BETWEEN 1 AND 2),
 CONSTRAINT ck_model_tokens CHECK((input_tokens IS NULL OR input_tokens>=0) AND (output_tokens IS NULL OR output_tokens>=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
