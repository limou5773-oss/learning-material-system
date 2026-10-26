ALTER TABLE ai_task ADD UNIQUE KEY uk_task_owner (id, user_id);

CREATE TABLE notification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  type VARCHAR(24) NOT NULL,
  dedupe_key VARCHAR(96) NOT NULL,
  content VARCHAR(500) NOT NULL,
  target_json JSON NOT NULL,
  read_at DATETIME(6) NULL,
  channel_status VARCHAR(24) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notification_event (user_id, dedupe_key),
  KEY idx_notification_owner_time (user_id, created_at, id),
  KEY idx_notification_owner_unread (user_id, read_at, created_at, id),
  CONSTRAINT fk_notification_task_owner FOREIGN KEY (task_id, user_id)
    REFERENCES ai_task(id, user_id) ON DELETE CASCADE,
  CONSTRAINT chk_notification_type CHECK (type IN ('RESULT_READY', 'TASK_FAILED')),
  CONSTRAINT chk_notification_channel CHECK (channel_status = 'NOT_REQUESTED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
