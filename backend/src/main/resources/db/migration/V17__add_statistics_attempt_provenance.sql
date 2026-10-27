ALTER TABLE model_attempt
 ADD COLUMN step_no INT NULL AFTER attempt_no,
 ADD COLUMN step_attempt_no INT NULL AFTER step_no,
 ADD COLUMN currency VARCHAR(3) NULL AFTER cost,
 ADD KEY idx_model_attempt_step(task_id,step_no,step_attempt_no),
 ADD KEY idx_model_attempt_started(started_at);

ALTER TABLE ocr_attempt
 ADD COLUMN currency VARCHAR(3) NULL AFTER cost,
 ADD KEY idx_ocr_attempt_started(started_at);

ALTER TABLE material ADD KEY idx_material_created(created_at);
ALTER TABLE ai_task ADD KEY idx_task_created(created_at);
