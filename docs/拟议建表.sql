-- 依据开题报告整理的拟议结构，尚未在MySQL执行。
-- 需在独立开发库中编写迁移并验证，不包含DROP、默认账号或密钥。
-- 应用与数据库连接统一使用UTC；业务约束同时在服务层验证。

CREATE TABLE `app_user` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `username` VARCHAR(64) NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `display_name` VARCHAR(64) NOT NULL,
  `role` VARCHAR(16) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `auth_session` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `user_id` BIGINT NOT NULL,
  `token_hash` CHAR(64) NOT NULL,
  `client_type` VARCHAR(16) NOT NULL,
  `expires_at` DATETIME NOT NULL,
  `revoked_at` DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_token (token_hash),
  KEY idx_session_user_expiry (user_id,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `wechat_binding` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `user_id` BIGINT NOT NULL,
  `appid` VARCHAR(64) NOT NULL,
  `openid` VARCHAR(128) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wechat_identity (appid,openid),
  UNIQUE KEY uk_user_app (user_id,appid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `material` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `user_id` BIGINT NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `subject` VARCHAR(64) NULL,
  `material_type` VARCHAR(16) NOT NULL,
  `storage_key` VARCHAR(512) NULL,
  `mime_type` VARCHAR(128) NULL,
  `file_size` BIGINT NULL,
  `file_hash` CHAR(64) NULL,
  `page_count` INT NULL,
  `note_text` LONGTEXT NULL,
  `summary` TEXT NULL,
  `version` INT NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `access_state` VARCHAR(16) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_material_owner_time (user_id,updated_at,id),
  KEY idx_material_hash (user_id,file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `material_chunk` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `material_id` BIGINT NOT NULL,
  `material_version` INT NOT NULL,
  `chunk_no` INT NOT NULL,
  `page_no` INT NULL,
  `content` LONGTEXT NOT NULL,
  `extraction_method` VARCHAR(16) NOT NULL,
  `quality_note` VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_chunk (material_id,material_version,chunk_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `tag` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `user_id` BIGINT NOT NULL,
  `name` VARCHAR(64) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_tag (user_id,name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `material_tag` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `material_id` BIGINT NOT NULL,
  `tag_id` BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_tag (material_id,tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `ai_task` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `user_id` BIGINT NOT NULL,
  `material_id` BIGINT NOT NULL,
  `material_version` INT NOT NULL,
  `request_key` VARCHAR(64) NOT NULL,
  `request_hash` CHAR(64) NOT NULL,
  `parent_task_id` BIGINT NULL,
  `goal_type` VARCHAR(32) NOT NULL,
  `instruction` TEXT NULL,
  `status` VARCHAR(20) NOT NULL,
  `stage` VARCHAR(32) NULL,
  `step_count` INT NOT NULL,
  `cancel_flag` TINYINT NOT NULL,
  `lease_until` DATETIME NULL,
  `execution_epoch` INT NOT NULL,
  `context_json` JSON NULL,
  `config_json` JSON NOT NULL,
  `error_code` VARCHAR(64) NULL,
  `started_at` DATETIME NULL,
  `finished_at` DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_request (user_id,request_key),
  KEY idx_task_claim (status,lease_until,id),
  KEY idx_task_owner_time (user_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `ai_tool_call` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `task_id` BIGINT NOT NULL,
  `step_no` INT NOT NULL,
  `attempt_no` INT NOT NULL,
  `execution_epoch` INT NOT NULL,
  `tool_name` VARCHAR(64) NOT NULL,
  `provider_kind` VARCHAR(16) NOT NULL,
  `input_digest` CHAR(64) NULL,
  `output_summary` TEXT NULL,
  `status` VARCHAR(16) NOT NULL,
  `error_code` VARCHAR(64) NULL,
  `started_at` DATETIME NOT NULL,
  `finished_at` DATETIME NULL,
  `usage_json` JSON NULL,
  `cost_amount` DECIMAL(14,6) NULL,
  `currency` VARCHAR(8) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tool_attempt (task_id,step_no,attempt_no),
  KEY idx_tool_usage (provider_kind,started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `ai_result` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `task_id` BIGINT NOT NULL,
  `version` INT NOT NULL,
  `candidate_json` JSON NOT NULL,
  `validation_json` JSON NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `confirmation_json` JSON NULL,
  `confirmed_at` DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_result_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `knowledge_card` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `user_id` BIGINT NOT NULL,
  `task_id` BIGINT NULL,
  `candidate_key` VARCHAR(64) NULL,
  `title` VARCHAR(255) NOT NULL,
  `content` LONGTEXT NOT NULL,
  `summary` TEXT NULL,
  `tags_json` JSON NULL,
  `subject` VARCHAR(64) NULL,
  `parent_card_id` BIGINT NULL,
  `version` INT NOT NULL,
  `origin` VARCHAR(16) NOT NULL,
  `is_edited` TINYINT NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_candidate (task_id,candidate_key),
  KEY idx_card_owner_time (user_id,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `card_source` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `card_id` BIGINT NOT NULL,
  `chunk_id` BIGINT NOT NULL,
  `quote_text` TEXT NULL,
  `start_offset` INT NULL,
  `end_offset` INT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_chunk (card_id,chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `card_relation` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `user_id` BIGINT NOT NULL,
  `source_card_id` BIGINT NOT NULL,
  `target_card_id` BIGINT NOT NULL,
  `relation_type` VARCHAR(16) NOT NULL,
  `evidence_text` TEXT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_relation (user_id,source_card_id,target_card_id,relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `card_layout` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `user_id` BIGINT NOT NULL,
  `layout_key` VARCHAR(64) NOT NULL,
  `items_json` JSON NOT NULL,
  `version` INT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_layout (user_id,layout_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `notification` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `user_id` BIGINT NOT NULL,
  `task_id` BIGINT NULL,
  `notification_type` VARCHAR(32) NOT NULL,
  `dedupe_key` VARCHAR(128) NOT NULL,
  `content` TEXT NOT NULL,
  `target_json` JSON NULL,
  `read_at` DATETIME NULL,
  `channel_status` VARCHAR(32) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notification_event (user_id,dedupe_key),
  KEY idx_notification_owner_time (user_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `favorite` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `user_id` BIGINT NOT NULL,
  `material_id` BIGINT NULL,
  `card_id` BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_favorite_material (user_id,material_id),
  UNIQUE KEY uk_favorite_card (user_id,card_id),
  CONSTRAINT ck_favorite_target CHECK ((material_id IS NULL) <> (card_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `content_review` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `material_id` BIGINT NOT NULL,
  `material_version` INT NOT NULL,
  `trigger_type` VARCHAR(16) NOT NULL,
  `reason` TEXT NOT NULL,
  `access_scope` VARCHAR(32) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `version` INT NOT NULL,
  `reviewer_id` BIGINT NULL,
  `decision_reason` TEXT NULL,
  `decided_at` DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_material_version (material_id,material_version),
  KEY idx_review_queue (status,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `review_record` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `operator_id` BIGINT NOT NULL,
  `content_review_id` BIGINT NULL,
  `material_id` BIGINT NULL,
  `target_user_id` BIGINT NULL,
  `action` VARCHAR(32) NOT NULL,
  `reason` TEXT NOT NULL,
  `decision` VARCHAR(32) NULL,
  PRIMARY KEY (id),
  KEY idx_audit_time (created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `auth_session` ADD CONSTRAINT `fk_auth_session_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `wechat_binding` ADD CONSTRAINT `fk_wechat_binding_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `material` ADD CONSTRAINT `fk_material_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `material_chunk` ADD CONSTRAINT `fk_material_chunk_material_id` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`);
ALTER TABLE `tag` ADD CONSTRAINT `fk_tag_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `material_tag` ADD CONSTRAINT `fk_material_tag_material_id` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`);
ALTER TABLE `material_tag` ADD CONSTRAINT `fk_material_tag_tag_id` FOREIGN KEY (`tag_id`) REFERENCES `tag` (`id`);
ALTER TABLE `ai_task` ADD CONSTRAINT `fk_ai_task_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `ai_task` ADD CONSTRAINT `fk_ai_task_material_id` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`);
ALTER TABLE `ai_task` ADD CONSTRAINT `fk_ai_task_parent_task_id` FOREIGN KEY (`parent_task_id`) REFERENCES `ai_task` (`id`);
ALTER TABLE `ai_tool_call` ADD CONSTRAINT `fk_ai_tool_call_task_id` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`);
ALTER TABLE `ai_result` ADD CONSTRAINT `fk_ai_result_task_id` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`);
ALTER TABLE `knowledge_card` ADD CONSTRAINT `fk_knowledge_card_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `knowledge_card` ADD CONSTRAINT `fk_knowledge_card_task_id` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`);
ALTER TABLE `knowledge_card` ADD CONSTRAINT `fk_knowledge_card_parent_card_id` FOREIGN KEY (`parent_card_id`) REFERENCES `knowledge_card` (`id`);
ALTER TABLE `card_source` ADD CONSTRAINT `fk_card_source_card_id` FOREIGN KEY (`card_id`) REFERENCES `knowledge_card` (`id`);
ALTER TABLE `card_source` ADD CONSTRAINT `fk_card_source_chunk_id` FOREIGN KEY (`chunk_id`) REFERENCES `material_chunk` (`id`);
ALTER TABLE `card_relation` ADD CONSTRAINT `fk_card_relation_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `card_relation` ADD CONSTRAINT `fk_card_relation_source_card_id` FOREIGN KEY (`source_card_id`) REFERENCES `knowledge_card` (`id`);
ALTER TABLE `card_relation` ADD CONSTRAINT `fk_card_relation_target_card_id` FOREIGN KEY (`target_card_id`) REFERENCES `knowledge_card` (`id`);
ALTER TABLE `card_layout` ADD CONSTRAINT `fk_card_layout_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `notification` ADD CONSTRAINT `fk_notification_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `notification` ADD CONSTRAINT `fk_notification_task_id` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`);
ALTER TABLE `favorite` ADD CONSTRAINT `fk_favorite_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `favorite` ADD CONSTRAINT `fk_favorite_material_id` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`);
ALTER TABLE `favorite` ADD CONSTRAINT `fk_favorite_card_id` FOREIGN KEY (`card_id`) REFERENCES `knowledge_card` (`id`);
ALTER TABLE `content_review` ADD CONSTRAINT `fk_content_review_material_id` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`);
ALTER TABLE `content_review` ADD CONSTRAINT `fk_content_review_reviewer_id` FOREIGN KEY (`reviewer_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `review_record` ADD CONSTRAINT `fk_review_record_operator_id` FOREIGN KEY (`operator_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `review_record` ADD CONSTRAINT `fk_review_record_content_review_id` FOREIGN KEY (`content_review_id`) REFERENCES `content_review` (`id`);
ALTER TABLE `review_record` ADD CONSTRAINT `fk_review_record_material_id` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`);
ALTER TABLE `review_record` ADD CONSTRAINT `fk_review_record_target_user_id` FOREIGN KEY (`target_user_id`) REFERENCES `app_user` (`id`);
