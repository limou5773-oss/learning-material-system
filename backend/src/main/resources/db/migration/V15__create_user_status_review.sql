CREATE TABLE admin_control_lock (
    id TINYINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_admin_control_singleton CHECK (id = 1)
) ENGINE=InnoDB;

INSERT INTO admin_control_lock (id) VALUES (1);

CREATE TABLE review_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operator_id BIGINT NOT NULL,
    target_user_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    before_status VARCHAR(16) NOT NULL,
    after_status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_review_target_created (target_user_id, created_at),
    KEY idx_review_operator (operator_id),
    CONSTRAINT fk_review_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    CONSTRAINT fk_review_target_user FOREIGN KEY (target_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
