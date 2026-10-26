ALTER TABLE knowledge_card
  MODIFY COLUMN task_id BIGINT NULL,
  MODIFY COLUMN candidate_key VARCHAR(64) NULL,
  ADD CONSTRAINT ck_card_provenance CHECK (
    (origin = 'MANUAL' AND generation_mode = 'MANUAL' AND task_id IS NULL AND candidate_key IS NULL)
    OR
    (origin = 'AI' AND generation_mode IN ('MOCK', 'MODEL') AND task_id IS NOT NULL AND candidate_key IS NOT NULL)
  );

ALTER TABLE favorite
  ADD CONSTRAINT fk_favorite_card FOREIGN KEY (card_id) REFERENCES knowledge_card(id);
