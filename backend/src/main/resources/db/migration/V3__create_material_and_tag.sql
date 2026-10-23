CREATE TABLE material (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL,
  subject VARCHAR(64) NULL,
  material_type VARCHAR(16) NOT NULL,
  storage_key VARCHAR(512) NULL,
  mime_type VARCHAR(128) NULL,
  file_size BIGINT NULL,
  file_hash CHAR(64) NULL,
  page_count INT NULL,
  note_text LONGTEXT NULL,
  summary TEXT NULL,
  version INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  access_state VARCHAR(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_material_owner_time (user_id, updated_at, id),
  KEY idx_material_hash (user_id, file_hash),
  CONSTRAINT fk_material_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tag (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_tag (user_id, name),
  CONSTRAINT fk_tag_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE material_tag (
  id BIGINT NOT NULL AUTO_INCREMENT,
  material_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_tag (material_id, tag_id),
  KEY idx_material_tag_tag (tag_id, material_id),
  CONSTRAINT fk_material_tag_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_material_tag_tag FOREIGN KEY (tag_id) REFERENCES tag(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
