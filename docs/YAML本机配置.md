# 直接修改 YAML 配置

数据库、DeepSeek、腾讯云 OCR、任务处理与管理员初始化，直接修改原文件 `backend/src/main/resources/application.yml`。不需要环境变量或额外的本机配置文件，修改后重启后端生效。

本文件会随 Maven 打包进入 jar。分享源码或上传 GitHub 前，移除其中的真实数据库密码、API 密钥和管理员密码。

## 填写位置

| 配置 | YAML字段 |
| --- | --- |
| MySQL地址、库名 | spring.datasource.url |
| MySQL账号、密码 | spring.datasource.username / password |
| JWT签名密钥 | lms.jwt.secret，至少32个随机字节的Base64编码 |
| JWT签发方、有效期 | lms.jwt.issuer / ttl，默认learning-material-system / 7d |
| DeepSeek密钥 | lms.model.api-key |
| 模型名 | lms.model.name，默认deepseek-flash |
| 腾讯云密钥 | lms.ocr.secret-id / secret-key |
| OCR开关 | lms.ocr.enabled，配好后设true |
| 整理模式 | lms.task.processor.mode：agent/model/mock/disabled |
| 任务处理开关 | lms.task.worker.enabled，准备处理时设true |
| 首次管理员 | lms.admin-bootstrap，启用一次后关闭 |
| 上传文件目录 | 顶层STORAGE_ROOT，可填写绝对路径 |

密码和密钥填写在引号内。DeepSeek 的 `lms.model.provider` 设为 `deepseek`，腾讯 OCR 的 `lms.ocr.provider` 设为 `tencent`；智能体整理选择 `lms.task.processor.mode: agent`。先填写数据库密码测试登录/资料功能；真实AI另填密钥并开启worker，OCR按需开启。仅修改配置并不代表真实厂商联调已通过。

## 启动

JWT配置仍在原 `application.yml` 的 `lms.jwt` 节点。本机已生成随机签名密钥；修改密钥会使所有旧JWT失效。不要把真实签名密钥上传GitHub，也不要将自动测试使用的公开测试密钥用于日常运行。JWT切换无需改表；重启后端后，Web和小程序都需重新登录。详细机制见[JWT登录说明](JWT登录说明.md)。

首次可直接导入整合好的[单文件SQL](../backend/src/main/resources/db/migration/learning_material_system.sql)，包含建库、全部表和迁移记录。操作见[数据库初始化说明](../database/README.md)，导入后正常启动后端即可。

已有开发库本次先执行 `database/updates/20261028_wechat_binding.sql`，不重导完整SQL。当前采用手动SQL方式，`spring.flyway.enabled` 保持 `false`。微信配置及账号操作见[账号与微信登录配置](账号与微信登录配置.md)。

在项目根目录启动：

```powershell
mvn -f backend/pom.xml spring-boot:run
```

Web仍在另一个终端运行`npm --prefix web run dev`，小程序仍修改自身API地址。AI密钥不填到前端或小程序。修改YAML后重启后端生效。

原有configure-model/configure-ocr/start-first-admin环境脚本已停用，数据库、AI与管理员初始化均直接修改本文件。已有IDE运行配置中的Spring同名覆盖项仍可能优先于YAML，应在IDE中移除旧覆盖项后使用文件配置。

自动验收是独立入口：`node scripts/verify.mjs`仍通过进程内隔离的专用测试库配置运行，并通过 acceptance 测试配置覆盖开发库连接，避免对个人数据库执行测试。它不会要求日常启动使用环境变量。本轮仅做配置及静态/针对性检查，实际数据库和厂商联调由用户执行。
