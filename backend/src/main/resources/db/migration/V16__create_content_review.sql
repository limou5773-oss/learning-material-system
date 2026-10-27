ALTER TABLE material ADD UNIQUE KEY uk_material_owner (id,user_id);

CREATE TABLE content_review (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  material_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  material_version INT NOT NULL,
  title VARCHAR(255) NOT NULL,
  material_type VARCHAR(16) NOT NULL,
  trigger_type VARCHAR(32) NOT NULL,
  access_scope VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  version INT NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  decision_reason VARCHAR(1000) NULL,
  created_at DATETIME(6) NOT NULL,
  decided_at DATETIME(6) NULL,
  UNIQUE KEY uk_content_review_version (material_id,material_version),
  UNIQUE KEY uk_content_review_owner (id,user_id),
  KEY idx_content_review_queue (status,created_at,id),
  KEY idx_content_review_owner (user_id,material_id,created_at,id),
  CONSTRAINT fk_content_review_material_owner FOREIGN KEY (material_id,user_id) REFERENCES material(id,user_id),
  CONSTRAINT chk_content_review_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  CONSTRAINT chk_content_review_version CHECK (version>0 AND material_version>0),
  CONSTRAINT chk_content_review_scope CHECK (trigger_type='USER_SUBMITTED' AND access_scope='MATERIAL_CONTENT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE review_record
  MODIFY target_user_id BIGINT NULL,
  ADD content_review_id BIGINT NULL,
  ADD material_id BIGINT NULL,
  ADD session_id BIGINT NULL,
  ADD KEY idx_review_content_session (content_review_id,operator_id,session_id,action),
  ADD CONSTRAINT fk_review_content FOREIGN KEY (content_review_id) REFERENCES content_review(id),
  ADD CONSTRAINT fk_review_material FOREIGN KEY (material_id) REFERENCES material(id),
  ADD CONSTRAINT fk_review_session FOREIGN KEY (session_id) REFERENCES auth_session(id);

ALTER TABLE notification
  MODIFY task_id BIGINT NULL,
  ADD review_id BIGINT NULL,
  DROP CHECK chk_notification_type,
  ADD CONSTRAINT fk_notification_review_owner FOREIGN KEY (review_id,user_id) REFERENCES content_review(id,user_id) ON DELETE CASCADE,
  ADD CONSTRAINT chk_notification_target CHECK (
    (type IN ('RESULT_READY','TASK_FAILED') AND task_id IS NOT NULL AND review_id IS NULL)
    OR (type='REVIEW_DECIDED' AND task_id IS NULL AND review_id IS NOT NULL)
  );
