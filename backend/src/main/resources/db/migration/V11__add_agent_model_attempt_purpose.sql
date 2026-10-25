ALTER TABLE model_attempt
 ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'GENERATION' AFTER attempt_no,
 DROP CHECK ck_model_attempt_number,
 ADD CONSTRAINT ck_model_attempt_number CHECK(attempt_no BETWEEN 1 AND 8),
 ADD CONSTRAINT ck_model_attempt_purpose CHECK(purpose IN ('GENERATION','PLANNER'));
