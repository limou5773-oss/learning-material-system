# 数据库初始化与本次升级

当前按用户选择采用合并SQL手动初始化，`spring.flyway.enabled: false`。数据库地址/账号/密码仍在原 `backend/src/main/resources/application.yml` 修改。

## 已有数据库

本次微信身份功能新增 `wechat_binding` 表。在数据库客户端选中原开发库，执行一次 [增量SQL](updates/20261028_wechat_binding.sql)。只新增表，不重导原表，不清空账号和资料；不要重新导入整个初始化文件。

## 首次初始化空库

执行 [完整SQL](../backend/src/main/resources/db/migration/learning_material_system.sql)，包含建库、原有全部表、必要控制记录和新绑定表。默认库名learning_material_dev；如使用其他库名，先同步SQL的CREATE/USE与YAML。

SQL中的旧Flyway历史仅保留原始迁移元数据，不代表当前会自动迁移。现有版本脚本已被用户改为合并SQL，不能仍按旧说明开启Flyway自动建表。后续已有库变更使用明确的增量SQL。

2026-10-28已在全新隔离MySQL 8实例验证完整SQL导入：25张表、业务记录为0，仅含1条必要控制记录和17条旧迁移记录。增量SQL也在另一个隔离库导入，并核对绑定表与完整SQL一致。本次没有连接或修改用户数据库；仍需用户对自己的已有库执行升级。
