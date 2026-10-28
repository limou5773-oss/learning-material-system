-- 已有开发库升级：先在数据库客户端选中 application.yml 指向的原数据库，再执行本文件一次。
-- 只新增微信绑定表；不重建原表，不删除或修改现有资料/账号；重复执行会明确报表已存在。
SET NAMES utf8mb4;

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
