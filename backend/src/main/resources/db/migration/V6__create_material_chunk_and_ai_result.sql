CREATE TABLE material_chunk (
  id BIGINT NOT NULL AUTO_INCREMENT,
  material_id BIGINT NOT NULL,
  material_version INT NOT NULL,
  chunk_no INT NOT NULL,
  page_no INT NULL,
  content LONGTEXT NOT NULL,
  extraction_method VARCHAR(16) NOT NULL,
  quality_note VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_chunk (material_id, material_version, chunk_no),
  CONSTRAINT fk_chunk_material FOREIGN KEY (material_id) REFERENCES material(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_result (
  task_id BIGINT NOT NULL,
  version INT NOT NULL,
  candidate_json JSON NOT NULL,
  validation_json JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  confirmation_json JSON NULL,
  confirmed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (task_id),
  CONSTRAINT fk_result_task FOREIGN KEY (task_id) REFERENCES ai_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
