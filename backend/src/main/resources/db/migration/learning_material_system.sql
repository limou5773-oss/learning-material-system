-- 个人学习资料智能整理系统：MySQL 8 单文件初始化（V1—V17基础表 + 微信绑定表）
-- 基础表由相同项目迁移在全新隔离库实际执行后导出，补充微信绑定表；不包含业务用户、资料或密钥。
-- 按用户选择采用单文件手动初始化；spring.flyway.enabled 保持 false，历史记录仅保留原始迁移元数据。
-- 仅用于首次初始化空库，不用于覆盖/升级已有业务库；有同名表时应停止，不要强制继续。
-- 如需更换库名，同时修改下面 CREATE DATABASE、USE 和本机YAML连接地址。
CREATE DATABASE IF NOT EXISTS `learning_material_dev` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `learning_material_dev`;
SET NAMES utf8mb4;


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `admin_control_lock` (
  `id` tinyint NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_admin_control_singleton` CHECK ((`id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `admin_control_lock` DISABLE KEYS */;
INSERT INTO `admin_control_lock` VALUES (1);
/*!40000 ALTER TABLE `admin_control_lock` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_result` (
  `task_id` bigint NOT NULL,
  `version` int NOT NULL,
  `candidate_json` json NOT NULL,
  `validation_json` json NOT NULL,
  `status` varchar(16) NOT NULL,
  `confirmation_json` json DEFAULT NULL,
  `confirmed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`task_id`),
  CONSTRAINT `fk_result_task` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `ai_result` DISABLE KEYS */;
/*!40000 ALTER TABLE `ai_result` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `material_id` bigint NOT NULL,
  `material_version` int NOT NULL,
  `request_key` varchar(64) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `parent_task_id` bigint DEFAULT NULL,
  `goal_type` varchar(32) NOT NULL,
  `instruction` text,
  `status` varchar(20) NOT NULL,
  `stage` varchar(32) DEFAULT NULL,
  `step_count` int NOT NULL,
  `cancel_flag` tinyint(1) NOT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  `execution_epoch` int NOT NULL,
  `context_json` json DEFAULT NULL,
  `config_json` json NOT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_request` (`user_id`,`request_key`),
  UNIQUE KEY `uk_task_owner` (`id`,`user_id`),
  KEY `idx_task_claim` (`status`,`lease_until`,`id`),
  KEY `idx_task_owner_time` (`user_id`,`created_at`,`id`),
  KEY `idx_task_material` (`material_id`,`status`),
  KEY `fk_task_parent` (`parent_task_id`),
  KEY `idx_task_created` (`created_at`),
  CONSTRAINT `fk_task_material` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`),
  CONSTRAINT `fk_task_parent` FOREIGN KEY (`parent_task_id`) REFERENCES `ai_task` (`id`),
  CONSTRAINT `fk_task_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `ai_task` DISABLE KEYS */;
/*!40000 ALTER TABLE `ai_task` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_tool_call` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `step_no` int NOT NULL,
  `attempt_no` int NOT NULL,
  `execution_epoch` int NOT NULL,
  `tool_name` varchar(64) NOT NULL,
  `provider_kind` varchar(16) NOT NULL,
  `input_digest` char(64) DEFAULT NULL,
  `output_summary` text,
  `status` varchar(16) NOT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `started_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `usage_json` json DEFAULT NULL,
  `cost_amount` decimal(14,6) DEFAULT NULL,
  `currency` varchar(8) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_attempt` (`task_id`,`step_no`,`attempt_no`),
  KEY `idx_tool_usage` (`provider_kind`,`started_at`),
  CONSTRAINT `fk_tool_call_task` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `ai_tool_call` DISABLE KEYS */;
/*!40000 ALTER TABLE `ai_tool_call` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `app_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `display_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `app_user` DISABLE KEYS */;
/*!40000 ALTER TABLE `app_user` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `auth_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `token_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `client_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_token` (`token_hash`),
  KEY `idx_session_user_expiry` (`user_id`,`expires_at`),
  CONSTRAINT `fk_auth_session_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `auth_session` DISABLE KEYS */;
/*!40000 ALTER TABLE `auth_session` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `card_layout` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `layout_key` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `items_json` json NOT NULL,
  `version` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_card_layout_owner_key` (`user_id`,`layout_key`),
  CONSTRAINT `fk_card_layout_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `card_layout` DISABLE KEYS */;
/*!40000 ALTER TABLE `card_layout` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `card_organization_lock` (
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_card_organization_lock_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `card_organization_lock` DISABLE KEYS */;
/*!40000 ALTER TABLE `card_organization_lock` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `card_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `source_card_id` bigint NOT NULL,
  `target_card_id` bigint NOT NULL,
  `relation_type` varchar(16) NOT NULL,
  `evidence_text` text NOT NULL,
  `origin` varchar(24) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_card_relation` (`user_id`,`source_card_id`,`target_card_id`,`relation_type`),
  KEY `fk_relation_source` (`source_card_id`),
  KEY `fk_relation_target` (`target_card_id`),
  CONSTRAINT `fk_relation_source` FOREIGN KEY (`source_card_id`) REFERENCES `knowledge_card` (`id`),
  CONSTRAINT `fk_relation_target` FOREIGN KEY (`target_card_id`) REFERENCES `knowledge_card` (`id`),
  CONSTRAINT `fk_relation_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`),
  CONSTRAINT `ck_relation_distinct` CHECK ((`source_card_id` <> `target_card_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `card_relation` DISABLE KEYS */;
/*!40000 ALTER TABLE `card_relation` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `card_source` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `card_id` bigint NOT NULL,
  `chunk_id` bigint NOT NULL,
  `quote_text` text NOT NULL,
  `start_offset` int NOT NULL,
  `end_offset` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_card_source` (`card_id`,`chunk_id`,`start_offset`,`end_offset`),
  KEY `fk_card_source_chunk` (`chunk_id`),
  CONSTRAINT `fk_card_source_card` FOREIGN KEY (`card_id`) REFERENCES `knowledge_card` (`id`),
  CONSTRAINT `fk_card_source_chunk` FOREIGN KEY (`chunk_id`) REFERENCES `material_chunk` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `card_source` DISABLE KEYS */;
/*!40000 ALTER TABLE `card_source` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `content_review` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `material_version` int NOT NULL,
  `title` varchar(255) NOT NULL,
  `material_type` varchar(16) NOT NULL,
  `trigger_type` varchar(32) NOT NULL,
  `access_scope` varchar(32) NOT NULL,
  `status` varchar(16) NOT NULL,
  `version` int NOT NULL,
  `reason` varchar(1000) NOT NULL,
  `decision_reason` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `decided_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_review_version` (`material_id`,`material_version`),
  UNIQUE KEY `uk_content_review_owner` (`id`,`user_id`),
  KEY `idx_content_review_queue` (`status`,`created_at`,`id`),
  KEY `idx_content_review_owner` (`user_id`,`material_id`,`created_at`,`id`),
  KEY `fk_content_review_material_owner` (`material_id`,`user_id`),
  CONSTRAINT `fk_content_review_material_owner` FOREIGN KEY (`material_id`, `user_id`) REFERENCES `material` (`id`, `user_id`),
  CONSTRAINT `chk_content_review_scope` CHECK (((`trigger_type` = _utf8mb4'USER_SUBMITTED') and (`access_scope` = _utf8mb4'MATERIAL_CONTENT'))),
  CONSTRAINT `chk_content_review_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'APPROVED',_utf8mb4'REJECTED'))),
  CONSTRAINT `chk_content_review_version` CHECK (((`version` > 0) and (`material_version` > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `content_review` DISABLE KEYS */;
/*!40000 ALTER TABLE `content_review` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `material_id` bigint DEFAULT NULL,
  `card_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_favorite_material` (`user_id`,`material_id`),
  UNIQUE KEY `uk_favorite_card` (`user_id`,`card_id`),
  KEY `idx_favorite_owner_time` (`user_id`,`created_at`,`id`),
  KEY `fk_favorite_material` (`material_id`),
  KEY `fk_favorite_card` (`card_id`),
  CONSTRAINT `fk_favorite_card` FOREIGN KEY (`card_id`) REFERENCES `knowledge_card` (`id`),
  CONSTRAINT `fk_favorite_material` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`),
  CONSTRAINT `fk_favorite_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`),
  CONSTRAINT `ck_favorite_target` CHECK (((`material_id` is null) <> (`card_id` is null)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `favorite` DISABLE KEYS */;
/*!40000 ALTER TABLE `favorite` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `type` varchar(20) NOT NULL,
  `script` varchar(1000) NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `flyway_schema_history` DISABLE KEYS */;
INSERT INTO `flyway_schema_history` VALUES (1,'1','create app user','SQL','V1__create_app_user.sql',193496698,'root','2026-10-27 16:15:33',15,1),(2,'2','create auth session','SQL','V2__create_auth_session.sql',1736848948,'root','2026-10-27 16:15:33',31,1),(3,'3','create material and tag','SQL','V3__create_material_and_tag.sql',337630908,'root','2026-10-27 16:15:33',60,1),(4,'4','create favorite','SQL','V4__create_favorite.sql',-1951796941,'root','2026-10-27 16:15:33',21,1),(5,'5','create ai task and tool call','SQL','V5__create_ai_task_and_tool_call.sql',1918233931,'root','2026-10-27 16:15:33',333,1),(6,'6','create material chunk and ai result','SQL','V6__create_material_chunk_and_ai_result.sql',1668799952,'root','2026-10-27 16:15:33',28,1),(7,'7','create knowledge cards','SQL','V7__create_knowledge_cards.sql',577567830,'root','2026-10-27 16:15:33',57,1),(8,'8','create pdf page cache','SQL','V8__create_pdf_page_cache.sql',-1945688166,'root','2026-10-27 16:15:34',19,1),(9,'9','create ocr cache and attempt','SQL','V9__create_ocr_cache_and_attempt.sql',292419063,'root','2026-10-27 16:15:34',37,1),(10,'10','create model attempt','SQL','V10__create_model_attempt.sql',1283972092,'root','2026-10-27 16:15:34',19,1),(11,'11','add agent model attempt purpose','SQL','V11__add_agent_model_attempt_purpose.sql',-384536227,'root','2026-10-27 16:15:34',29,1),(12,'12','support manual cards and card favorites','SQL','V12__support_manual_cards_and_card_favorites.sql',1646712678,'root','2026-10-27 16:15:34',56,1),(13,'13','create card layout','SQL','V13__create_card_layout.sql',242607792,'root','2026-10-27 16:15:34',26,1),(14,'14','create notification','SQL','V14__create_notification.sql',-376282143,'root','2026-10-27 16:15:34',36,1),(15,'15','create user status review','SQL','V15__create_user_status_review.sql',-1123708073,'root','2026-10-27 16:15:34',24,1),(16,'16','create content review','SQL','V16__create_content_review.sql',-1236643236,'root','2026-10-27 16:15:34',93,1),(17,'17','add statistics attempt provenance','SQL','V17__add_statistics_attempt_provenance.sql',1516319930,'root','2026-10-27 16:15:34',141,1);
/*!40000 ALTER TABLE `flyway_schema_history` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_card` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `task_id` bigint DEFAULT NULL,
  `candidate_key` varchar(64) DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `content` text NOT NULL,
  `summary` text,
  `subject` varchar(64) DEFAULT NULL,
  `tags_json` json NOT NULL,
  `parent_card_id` bigint DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  `origin` varchar(16) NOT NULL,
  `generation_mode` varchar(16) NOT NULL,
  `is_edited` tinyint(1) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_card_task_candidate` (`task_id`,`candidate_key`),
  KEY `idx_card_owner_time` (`user_id`,`updated_at`,`id`),
  KEY `fk_card_parent` (`parent_card_id`),
  CONSTRAINT `fk_card_parent` FOREIGN KEY (`parent_card_id`) REFERENCES `knowledge_card` (`id`),
  CONSTRAINT `fk_card_task` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`),
  CONSTRAINT `fk_card_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`),
  CONSTRAINT `ck_card_provenance` CHECK ((((`origin` = _utf8mb4'MANUAL') and (`generation_mode` = _utf8mb4'MANUAL') and (`task_id` is null) and (`candidate_key` is null)) or ((`origin` = _utf8mb4'AI') and (`generation_mode` in (_utf8mb4'MOCK',_utf8mb4'MODEL')) and (`task_id` is not null) and (`candidate_key` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `knowledge_card` DISABLE KEYS */;
/*!40000 ALTER TABLE `knowledge_card` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `material` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `title` varchar(255) NOT NULL,
  `subject` varchar(64) DEFAULT NULL,
  `material_type` varchar(16) NOT NULL,
  `storage_key` varchar(512) DEFAULT NULL,
  `mime_type` varchar(128) DEFAULT NULL,
  `file_size` bigint DEFAULT NULL,
  `file_hash` char(64) DEFAULT NULL,
  `page_count` int DEFAULT NULL,
  `note_text` longtext,
  `summary` text,
  `version` int NOT NULL,
  `status` varchar(16) NOT NULL,
  `access_state` varchar(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_owner` (`id`,`user_id`),
  KEY `idx_material_owner_time` (`user_id`,`updated_at`,`id`),
  KEY `idx_material_hash` (`user_id`,`file_hash`),
  KEY `idx_material_created` (`created_at`),
  CONSTRAINT `fk_material_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `material` DISABLE KEYS */;
/*!40000 ALTER TABLE `material` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `material_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_id` bigint NOT NULL,
  `material_version` int NOT NULL,
  `chunk_no` int NOT NULL,
  `page_no` int DEFAULT NULL,
  `content` longtext NOT NULL,
  `extraction_method` varchar(16) NOT NULL,
  `quality_note` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_chunk` (`material_id`,`material_version`,`chunk_no`),
  CONSTRAINT `fk_chunk_material` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `material_chunk` DISABLE KEYS */;
/*!40000 ALTER TABLE `material_chunk` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `material_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_id` bigint NOT NULL,
  `tag_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_tag` (`material_id`,`tag_id`),
  KEY `idx_material_tag_tag` (`tag_id`,`material_id`),
  CONSTRAINT `fk_material_tag_material` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`),
  CONSTRAINT `fk_material_tag_tag` FOREIGN KEY (`tag_id`) REFERENCES `tag` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `material_tag` DISABLE KEYS */;
/*!40000 ALTER TABLE `material_tag` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `model_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `execution_epoch` int NOT NULL,
  `attempt_no` int NOT NULL,
  `step_no` int DEFAULT NULL,
  `step_attempt_no` int DEFAULT NULL,
  `purpose` varchar(16) NOT NULL DEFAULT 'GENERATION',
  `provider_key` varchar(96) NOT NULL,
  `requested_model` varchar(128) NOT NULL,
  `response_model` varchar(128) DEFAULT NULL,
  `prompt_version` varchar(64) NOT NULL,
  `dispatched` tinyint(1) NOT NULL,
  `status` varchar(16) NOT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `request_id` varchar(128) DEFAULT NULL,
  `input_tokens` bigint DEFAULT NULL,
  `output_tokens` bigint DEFAULT NULL,
  `started_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `cost` decimal(12,6) DEFAULT NULL,
  `currency` varchar(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_task_attempt` (`task_id`,`attempt_no`),
  KEY `idx_model_attempt_epoch` (`task_id`,`execution_epoch`,`status`),
  KEY `idx_model_attempt_step` (`task_id`,`step_no`,`step_attempt_no`),
  KEY `idx_model_attempt_started` (`started_at`),
  CONSTRAINT `fk_model_attempt_task` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`),
  CONSTRAINT `ck_model_attempt_number` CHECK ((`attempt_no` between 1 and 8)),
  CONSTRAINT `ck_model_attempt_purpose` CHECK ((`purpose` in (_utf8mb4'GENERATION',_utf8mb4'PLANNER'))),
  CONSTRAINT `ck_model_attempt_status` CHECK ((`status` in (_utf8mb4'RUNNING',_utf8mb4'SUCCESS',_utf8mb4'FAILED',_utf8mb4'UNKNOWN'))),
  CONSTRAINT `ck_model_tokens` CHECK ((((`input_tokens` is null) or (`input_tokens` >= 0)) and ((`output_tokens` is null) or (`output_tokens` >= 0))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `model_attempt` DISABLE KEYS */;
/*!40000 ALTER TABLE `model_attempt` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `task_id` bigint DEFAULT NULL,
  `type` varchar(24) NOT NULL,
  `dedupe_key` varchar(96) NOT NULL,
  `content` varchar(500) NOT NULL,
  `target_json` json NOT NULL,
  `read_at` datetime(6) DEFAULT NULL,
  `channel_status` varchar(24) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `review_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_event` (`user_id`,`dedupe_key`),
  KEY `idx_notification_owner_time` (`user_id`,`created_at`,`id`),
  KEY `idx_notification_owner_unread` (`user_id`,`read_at`,`created_at`,`id`),
  KEY `fk_notification_task_owner` (`task_id`,`user_id`),
  KEY `fk_notification_review_owner` (`review_id`,`user_id`),
  CONSTRAINT `fk_notification_review_owner` FOREIGN KEY (`review_id`, `user_id`) REFERENCES `content_review` (`id`, `user_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_notification_task_owner` FOREIGN KEY (`task_id`, `user_id`) REFERENCES `ai_task` (`id`, `user_id`) ON DELETE CASCADE,
  CONSTRAINT `chk_notification_channel` CHECK ((`channel_status` = _utf8mb4'NOT_REQUESTED')),
  CONSTRAINT `chk_notification_target` CHECK ((((`type` in (_utf8mb4'RESULT_READY',_utf8mb4'TASK_FAILED')) and (`task_id` is not null) and (`review_id` is null)) or ((`type` = _utf8mb4'REVIEW_DECIDED') and (`task_id` is null) and (`review_id` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `notification` DISABLE KEYS */;
/*!40000 ALTER TABLE `notification` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ocr_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `execution_epoch` int NOT NULL,
  `page_no` int NOT NULL,
  `attempt_no` int NOT NULL,
  `provider_key` varchar(96) NOT NULL,
  `dispatched` tinyint(1) NOT NULL,
  `status` varchar(16) NOT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `request_id` varchar(128) DEFAULT NULL,
  `started_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `cost` decimal(12,6) DEFAULT NULL,
  `currency` varchar(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ocr_task_page_attempt` (`task_id`,`page_no`,`attempt_no`),
  KEY `idx_ocr_attempt_epoch` (`task_id`,`execution_epoch`,`status`),
  KEY `idx_ocr_attempt_started` (`started_at`),
  CONSTRAINT `fk_ocr_attempt_task` FOREIGN KEY (`task_id`) REFERENCES `ai_task` (`id`),
  CONSTRAINT `ck_ocr_attempt_number` CHECK ((`attempt_no` between 1 and 2)),
  CONSTRAINT `ck_ocr_attempt_status` CHECK ((`status` in (_utf8mb4'RUNNING',_utf8mb4'SUCCESS',_utf8mb4'FAILED',_utf8mb4'UNKNOWN')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `ocr_attempt` DISABLE KEYS */;
/*!40000 ALTER TABLE `ocr_attempt` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ocr_page_cache` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_id` bigint NOT NULL,
  `material_version` int NOT NULL,
  `page_no` int NOT NULL,
  `provider_key` varchar(96) NOT NULL,
  `extraction_status` varchar(16) NOT NULL,
  `page_text` longtext NOT NULL,
  `confidence` double DEFAULT NULL,
  `request_id` varchar(128) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ocr_page_version` (`material_id`,`material_version`,`page_no`),
  CONSTRAINT `fk_ocr_cache_material` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`),
  CONSTRAINT `ck_ocr_cache_confidence` CHECK (((`confidence` is null) or ((`confidence` >= 0) and (`confidence` <= 1)))),
  CONSTRAINT `ck_ocr_cache_page` CHECK ((`page_no` between 0 and 100)),
  CONSTRAINT `ck_ocr_cache_status` CHECK ((`extraction_status` in (_utf8mb4'TEXT',_utf8mb4'EMPTY')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `ocr_page_cache` DISABLE KEYS */;
/*!40000 ALTER TABLE `ocr_page_cache` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pdf_page_cache` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_id` bigint NOT NULL,
  `material_version` int NOT NULL,
  `page_no` int NOT NULL,
  `total_pages` int NOT NULL,
  `extraction_status` varchar(16) NOT NULL,
  `page_text` longtext NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pdf_page_version` (`material_id`,`material_version`,`page_no`),
  CONSTRAINT `fk_pdf_page_material` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`),
  CONSTRAINT `ck_pdf_page_status` CHECK ((`extraction_status` in (_utf8mb4'TEXT',_utf8mb4'NEEDS_OCR')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `pdf_page_cache` DISABLE KEYS */;
/*!40000 ALTER TABLE `pdf_page_cache` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `review_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operator_id` bigint NOT NULL,
  `target_user_id` bigint DEFAULT NULL,
  `action` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `before_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `after_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `content_review_id` bigint DEFAULT NULL,
  `material_id` bigint DEFAULT NULL,
  `session_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_review_target_created` (`target_user_id`,`created_at`),
  KEY `idx_review_operator` (`operator_id`),
  KEY `idx_review_content_session` (`content_review_id`,`operator_id`,`session_id`,`action`),
  KEY `fk_review_material` (`material_id`),
  KEY `fk_review_session` (`session_id`),
  CONSTRAINT `fk_review_content` FOREIGN KEY (`content_review_id`) REFERENCES `content_review` (`id`),
  CONSTRAINT `fk_review_material` FOREIGN KEY (`material_id`) REFERENCES `material` (`id`),
  CONSTRAINT `fk_review_operator` FOREIGN KEY (`operator_id`) REFERENCES `app_user` (`id`),
  CONSTRAINT `fk_review_session` FOREIGN KEY (`session_id`) REFERENCES `auth_session` (`id`),
  CONSTRAINT `fk_review_target_user` FOREIGN KEY (`target_user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `review_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `review_record` ENABLE KEYS */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tag` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `name` varchar(64) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_tag` (`user_id`,`name`),
  CONSTRAINT `fk_tag_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40000 ALTER TABLE `tag` DISABLE KEYS */;
/*!40000 ALTER TABLE `tag` ENABLE KEYS */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


-- 微信身份绑定：新增功能的表结构（不包含任何真实微信身份）。
CREATE TABLE `wechat_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `app_id` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `open_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wechat_binding_user` (`user_id`),
  UNIQUE KEY `uk_wechat_binding_identity` (`app_id`,`open_id`),
  CONSTRAINT `fk_wechat_binding_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
