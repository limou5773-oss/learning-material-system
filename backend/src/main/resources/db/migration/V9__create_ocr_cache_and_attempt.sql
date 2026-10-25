CREATE TABLE ocr_page_cache (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  material_id BIGINT NOT NULL,
  material_version INT NOT NULL,
  page_no INT NOT NULL,
  provider_key VARCHAR(96) NOT NULL,
  extraction_status VARCHAR(16) NOT NULL,
  page_text LONGTEXT NOT NULL,
  confidence DOUBLE NULL,
  request_id VARCHAR(128) NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_ocr_page_version (material_id,material_version,page_no),
  CONSTRAINT fk_ocr_cache_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT ck_ocr_cache_status CHECK (extraction_status IN ('TEXT','EMPTY')),
  CONSTRAINT ck_ocr_cache_page CHECK (page_no BETWEEN 0 AND 100),
  CONSTRAINT ck_ocr_cache_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ocr_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  execution_epoch INT NOT NULL,
  page_no INT NOT NULL,
  attempt_no INT NOT NULL,
  provider_key VARCHAR(96) NOT NULL,
  dispatched BOOLEAN NOT NULL,
  status VARCHAR(16) NOT NULL,
  error_code VARCHAR(64) NULL,
  request_id VARCHAR(128) NULL,
  started_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  duration_ms BIGINT NULL,
  cost DECIMAL(12,6) NULL,
  UNIQUE KEY uk_ocr_task_page_attempt (task_id,page_no,attempt_no),
  KEY idx_ocr_attempt_epoch (task_id,execution_epoch,status),
  CONSTRAINT fk_ocr_attempt_task FOREIGN KEY (task_id) REFERENCES ai_task(id),
  CONSTRAINT ck_ocr_attempt_status CHECK (status IN ('RUNNING','SUCCESS','FAILED','UNKNOWN')),
  CONSTRAINT ck_ocr_attempt_number CHECK (attempt_no BETWEEN 1 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
