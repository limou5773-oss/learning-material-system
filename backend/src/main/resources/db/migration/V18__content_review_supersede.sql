-- 已有库升级一次；新库使用最新 learning_material_system.sql，不重复执行本文件。
-- 执行前选择目标数据库；保留原决定时间和原因，单独记录失效时间。
ALTER TABLE content_review DROP CHECK chk_content_review_status;
ALTER TABLE content_review
  ADD COLUMN superseded_at datetime(6) DEFAULT NULL,
  ADD CONSTRAINT chk_content_review_status
    CHECK (status IN ('PENDING','APPROVED','REJECTED','SUPERSEDED'));
ALTER TABLE notification DROP CHECK chk_notification_target;
ALTER TABLE notification ADD CONSTRAINT chk_notification_target CHECK (
  (type IN ('RESULT_READY','TASK_FAILED') AND task_id IS NOT NULL AND review_id IS NULL)
  OR (type IN ('REVIEW_DECIDED','REVIEW_SUPERSEDED') AND task_id IS NULL AND review_id IS NOT NULL)
);

START TRANSACTION;
SET @review_superseded_at = UTC_TIMESTAMP(6);
-- 修复已存在的旧版放行；当前版本已有有效批准的资料继续保持其访问状态。
UPDATE material m LEFT JOIN content_review current_review
  ON current_review.material_id=m.id AND current_review.material_version=m.version
     AND current_review.status='APPROVED' AND m.status<>'DELETED'
SET m.access_state='RESTRICTED',m.updated_at=@review_superseded_at
WHERE current_review.id IS NULL AND EXISTS (
  SELECT 1 FROM content_review stale WHERE stale.material_id=m.id
    AND stale.status IN ('PENDING','APPROVED')
    AND (stale.material_version<>m.version OR m.status='DELETED')
);
INSERT INTO notification(user_id,review_id,type,dedupe_key,content,target_json,channel_status,created_at)
SELECT r.user_id,r.id,'REVIEW_SUPERSEDED',CONCAT('material:',m.id,':',m.version,':REVIEW_SUPERSEDED'),
  '资料内容已变更，原审核已失效，需重新送审。',
  JSON_OBJECT('type','MATERIAL','materialId',CAST(m.id AS CHAR),'reviewId',CAST(r.id AS CHAR)),
  'NOT_REQUESTED',@review_superseded_at
FROM material m JOIN content_review r ON r.id=(
  SELECT stale.id FROM content_review stale WHERE stale.material_id=m.id
    AND stale.status IN ('PENDING','APPROVED')
    AND (stale.material_version<>m.version OR m.status='DELETED')
  ORDER BY stale.material_version DESC,stale.id DESC LIMIT 1
)
WHERE m.access_state='RESTRICTED'
ON DUPLICATE KEY UPDATE id=notification.id;
UPDATE content_review r JOIN material m ON m.id=r.material_id
SET r.status='SUPERSEDED',r.version=r.version+1,r.superseded_at=@review_superseded_at
WHERE r.status IN ('PENDING','APPROVED') AND (r.material_version<>m.version OR m.status='DELETED');
UPDATE ai_task t JOIN material m ON m.id=t.material_id
SET t.status='CANCELLED',t.stage='SOURCE_RESTRICTED',t.cancel_flag=1,
  t.error_code='SOURCE_RESTRICTED',t.lease_until=NULL,t.finished_at=@review_superseded_at,t.updated_at=@review_superseded_at
WHERE m.access_state='RESTRICTED' AND t.status IN ('QUEUED','RUNNING','WAIT_CONFIRM')
  AND EXISTS (SELECT 1 FROM content_review r WHERE r.material_id=m.id
    AND r.status='SUPERSEDED' AND r.superseded_at=@review_superseded_at);
UPDATE ai_tool_call c JOIN ai_task t ON t.id=c.task_id
SET c.status='UNKNOWN',c.error_code='SOURCE_RESTRICTED',c.finished_at=@review_superseded_at
WHERE c.status='RUNNING' AND t.stage='SOURCE_RESTRICTED' AND t.updated_at=@review_superseded_at;
UPDATE ocr_attempt a JOIN ai_task t ON t.id=a.task_id
SET a.status='UNKNOWN',a.error_code='SOURCE_RESTRICTED',a.finished_at=@review_superseded_at,
  a.duration_ms=TIMESTAMPDIFF(MICROSECOND,a.started_at,@review_superseded_at) DIV 1000
WHERE a.status='RUNNING' AND t.stage='SOURCE_RESTRICTED' AND t.updated_at=@review_superseded_at;
UPDATE model_attempt a JOIN ai_task t ON t.id=a.task_id
SET a.status='UNKNOWN',a.error_code='SOURCE_RESTRICTED',a.finished_at=@review_superseded_at,
  a.duration_ms=TIMESTAMPDIFF(MICROSECOND,a.started_at,@review_superseded_at) DIV 1000
WHERE a.status='RUNNING' AND t.stage='SOURCE_RESTRICTED' AND t.updated_at=@review_superseded_at;
COMMIT;
