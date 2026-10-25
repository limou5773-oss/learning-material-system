CREATE TABLE knowledge_card (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  candidate_key VARCHAR(64) NOT NULL,
  title VARCHAR(255) NOT NULL,
  content TEXT NOT NULL,
  summary TEXT NULL,
  subject VARCHAR(64) NULL,
  tags_json JSON NOT NULL,
  parent_card_id BIGINT NULL,
  version INT NOT NULL DEFAULT 1,
  origin VARCHAR(16) NOT NULL,
  generation_mode VARCHAR(16) NOT NULL,
  is_edited BOOLEAN NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_task_candidate (task_id, candidate_key),
  KEY idx_card_owner_time (user_id, updated_at, id),
  CONSTRAINT fk_card_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_card_task FOREIGN KEY (task_id) REFERENCES ai_task(id),
  CONSTRAINT fk_card_parent FOREIGN KEY (parent_card_id) REFERENCES knowledge_card(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE card_source (
  id BIGINT NOT NULL AUTO_INCREMENT,
  card_id BIGINT NOT NULL,
  chunk_id BIGINT NOT NULL,
  quote_text TEXT NOT NULL,
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_source (card_id, chunk_id, start_offset, end_offset),
  CONSTRAINT fk_card_source_card FOREIGN KEY (card_id) REFERENCES knowledge_card(id),
  CONSTRAINT fk_card_source_chunk FOREIGN KEY (chunk_id) REFERENCES material_chunk(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE card_relation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  source_card_id BIGINT NOT NULL,
  target_card_id BIGINT NOT NULL,
  relation_type VARCHAR(16) NOT NULL,
  evidence_text TEXT NOT NULL,
  origin VARCHAR(24) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_relation (user_id, source_card_id, target_card_id, relation_type),
  CONSTRAINT fk_relation_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_relation_source FOREIGN KEY (source_card_id) REFERENCES knowledge_card(id),
  CONSTRAINT fk_relation_target FOREIGN KEY (target_card_id) REFERENCES knowledge_card(id),
  CONSTRAINT ck_relation_distinct CHECK (source_card_id <> target_card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
