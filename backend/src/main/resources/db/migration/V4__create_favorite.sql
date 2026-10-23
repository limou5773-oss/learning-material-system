CREATE TABLE favorite (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  material_id BIGINT NULL,
  card_id BIGINT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_favorite_material (user_id, material_id),
  UNIQUE KEY uk_favorite_card (user_id, card_id),
  KEY idx_favorite_owner_time (user_id, created_at, id),
  CONSTRAINT ck_favorite_target CHECK ((material_id IS NULL) <> (card_id IS NULL)),
  CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_favorite_material FOREIGN KEY (material_id) REFERENCES material(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
