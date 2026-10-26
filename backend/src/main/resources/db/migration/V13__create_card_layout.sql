CREATE TABLE card_layout (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  layout_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  items_json JSON NOT NULL,
  version INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_layout_owner_key (user_id, layout_key),
  CONSTRAINT fk_card_layout_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE card_organization_lock (
  user_id BIGINT NOT NULL,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_card_organization_lock_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
