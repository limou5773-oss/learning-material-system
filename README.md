# 个人学习资料智能整理系统

毕业设计题目：**面向Web与微信小程序的个人学习资料智能整理系统设计与实现**。

微信小程序用于拍照上传和碎片笔记，Web用于集中管理、知识卡片、关系展示与平台管理。后端通过受限工具调用的学习资料整理智能体，组织文本提取、OCR、摘要及卡片生成，结果经用户确认后保存。

## 当前状态

截至2026年10月25日，D01至D13已实现，D14加入腾讯云OCR适配代码、图片及扫描页处理、缓存和请求记录；真实腾讯云联调按用户选择留待本机配置密钥后进行。OCR默认关闭。D12提供候选确认与双端卡片查阅，D13加入PDF指定页段的真实文字提取、页码与固定版本缓存。D03至D10仍需补齐部分浏览器操作和微信开发者工具原生验证。当前卡片生成仍为明确标注的模拟结果；真实模型和智能体规划循环尚未接入。详见[开发进度与下一步](docs/开发进度.md)和[任务清单](tasks/todo.md)。

## 文档入口

- [开题报告](docs/开题报告.md)
- [需求与开发说明书](docs/需求与开发说明书.md)与[Word阅读稿](docs/需求与开发说明书.docx)
- [数据库设计](docs/数据库设计.md)与[拟议建表SQL](docs/拟议建表.sql)
- [OpenAPI接口契约](docs/接口契约.yaml)
- [开发计划](tasks/plan.md)与[任务清单](tasks/todo.md)
- [开发进度与下一步](docs/开发进度.md)
- [腾讯云OCR配置与待验证事项](docs/OCR接入配置.md)

## 技术方案

- 后端：Spring Boot、Spring Security、MyBatis-Plus、MySQL。
- Web：Vue3、Element Plus；关系图阶段计划接入ECharts。
- 移动端：原生微信小程序。
- 智能处理：公有大模型API、OCR接口、单智能体及工具白名单。

后端与Web依赖补丁版本已在工程文件及锁文件中固定；后续阶段的配置仍按开发计划逐步落地。

## 开发顺序

先完成独立数据库连通和后端健康检查，再实现登录、文字笔记与双端查阅；随后增加图片和PDF上传。异步任务、来源校验及人工确认协议验证后，再接真实OCR和模型，最后完成知识可视化、管理后台及统计。

D01、D02、D11至D13已有对应验收记录；D03至D10已有代码与自动化验证记录，尚待对应的真实操作证据。D14代码与本地验证见[验证记录](tasks/evidence/D14.md)，真实厂商验收待配置密钥。小程序原生验证按用户此前选择暂缓。

## 后端本地启动（D01）

需要JDK 21、Maven 3.9+及独立的MySQL 8开发库。先创建空库`learning_material_dev`，不要将[拟议建表SQL](docs/拟议建表.sql)整份导入。复制[.env.example](.env.example)为`.env`并填写本地账号与密码，再把三个`DB_`变量导出到运行后端的终端环境。缺少或留空任一变量时后端会拒绝启动。

```bash
set -a
source .env
set +a
mvn -f backend/pom.xml spring-boot:run
curl -i http://localhost:8080/actuator/health
```

正常运行时健康接口只返回状态；Flyway启动时执行V1至V9迁移并记录到`flyway_schema_history`。D01已在独立MySQL上完成首次启动、迁移、重复启动和空库重放验证；V3资料与标签迁移见[D04验证记录](tasks/evidence/D04.md)，V4收藏迁移见[D08验证记录](tasks/evidence/D08.md)，V5任务迁移见[D09验证记录](tasks/evidence/D09.md)，V6候选与来源迁移见[D11验证记录](tasks/evidence/D11.md)，V7正式卡片迁移见[D12验证记录](tasks/evidence/D12.md)，V8 PDF页缓存见[D13验证记录](tasks/evidence/D13.md)。

本项目当前使用的Mac已配置`lms-mysql-start`和`lms-mysql-stop`命令。重启电脑后先在终端运行`lms-mysql-start`，再执行上述后端启动命令。微信开发者工具已安装在“应用程序”中；小程序联调时仍需使用本人微信账号登录并导入项目。

## Web 本地启动（D02）

先按上文启动后端，再另开一个终端，在项目根目录执行：

```bash
cd web
npm ci
npm run dev
```

浏览器访问 `http://127.0.0.1:5173`，可注册普通账号、登录、查看本人信息并退出。Web开发服务器把`/api`请求转发到本机8080端口。令牌只放在页面内存，刷新后需要重新登录；注册接口不接收`role`或`status`字段。

登录后进入“我的资料”，可创建、编辑、归档、恢复和删除文字笔记，按标题或正文搜索，并按学科、标签、状态和收藏筛选。列表及详情可收藏或取消收藏本人资料；重复点击不会生成多条收藏，归档资料仍可查看，删除后收藏同步移除。编辑时发送版本号；若收到409，页面保留草稿并展示服务器版本。网页编辑草稿保存在当前标签页的会话存储中，刷新并重新登录后可恢复。

资料列表也可上传JPEG、PNG图片，并在详情页预览原图。单张图片默认不超过10 MiB，解码后最多4000万像素；服务端会核对扩展名、实际类型和大小。重复上传会显示已有资料入口，可由用户确认保存副本。文件默认存于运行后端的系统账户下的`~/.learning-material-system/files`，位于项目目录外；可通过`STORAGE_ROOT`指定其他绝对目录，`FILE_MAX_IMAGE_BYTES`调整服务端图片字节上限。文件只能通过携带会话令牌的`GET /api/v1/materials/{id}/file`读取，不能通过裸路径访问。

“上传 PDF”可一次选择最多10个文件，前端同时上传2个；每个文件独立显示成功、失败或重复结果。单个PDF默认不超过30 MiB、100页，服务端解析后保存页数和SHA-256摘要；密码加密或损坏文件会明确报错。重复文件可打开已有资料或确认保存副本。PDF详情通过授权请求读取文件，在浏览器内预览并提供下载。可用`FILE_MAX_PDF_BYTES`调整服务端字节上限，最大配置值为32 MiB。

资料详情可进入“提交整理任务”，选择生成复习卡片、提炼重点或关联笔记，并填写补充说明。PDF需选择不超过20页的有效页段，提交前需确认外部处理同意。每个账号最多同时保留2个排队或执行中的任务；相同请求键及输入返回原任务，不同输入返回409。Web“整理任务”页和小程序任务页可读取状态和脱敏步骤、取消任务，并在重新同意外部处理后基于当前资料版本创建重试任务。删除原资料会取消对应的非终态任务。D10已实现最多2个worker、30秒租约、10秒心跳、300秒执行时限和epoch写入保护。

D11起提供本地确定性模拟处理器：严格校验`prepare_source`、`generate_summary_cards`、`validate_candidate`工具参数，保存固定版本来源片段和候选摘要、标签、卡片，核验引用后将任务原子地转为`WAIT_CONFIRM`。Web与小程序可预览候选及完整来源，明确显示“模拟生成”。文字笔记引用笔记正文；未启用OCR时，图片只引用标题元数据，实际处理页数为0。候选在用户确认前不会写入正式卡片或资料摘要。

D13对PDF调用本地`extract_pdf_text`，只提取用户所选连续页段（最多20页）的文字层。来源保留实际页码，按资料、版本和页码缓存；同版本重叠页段复用来源。没有文字层的页标记需要OCR或可能为空白，混合PDF只对有文字的页生成模拟候选并列出未识别页；全范围无文字时明确失败，不使用标题假装正文。每片段最多2000个UTF-16代码单元，每任务最多30个片段，超过上限提示缩小页段，不静默截断PDF文字。此步骤不调用外部OCR或模型。

默认`TASK_WORKER_ENABLED=false`，任务仍排队；仅开启worker但未选择处理器时会安全失败并返回`PROCESSOR_NOT_CONFIGURED`。本地联调模拟流程时，在启动后端前设置`TASK_WORKER_ENABLED=true`和`TASK_PROCESSOR_MODE=mock`。保持默认`OCR_ENABLED=false`时不调用外部模型或OCR。D14显式开启OCR并配置腾讯云后，图片及所选扫描页会发送给腾讯云，可能产生OCR费用；摘要和卡片仍为模拟生成。配置步骤见[OCR接入配置](docs/OCR接入配置.md)。

D12在待确认任务提供“编辑并确认”入口，可修改摘要、标签和卡片文字，选择部分卡片并逐条勾选关联。确认后，卡片、固定版本来源、所选关系与资料摘要和标签整体保存；重复确认返回同一回执。来源版本变化返回409并保留编辑草稿；保存失败整体回滚。Web“知识卡片”和小程序“我的信息 → 知识卡片”可查阅正式卡片及来源，仍显示“模拟生成”。来源删除或受限时隐藏引用和片段正文并说明原因。人工新建、后续卡片编辑与搜索留在D17。

## 微信小程序本地联调（D03）

先启动后端，再打开微信开发者工具，导入项目中的`miniprogram/`目录。项目默认使用`touristappid`作为本地导入占位；如有自己的小程序AppID，在开发者工具中配置。使用Web端已注册的用户名和密码登录，小程序“我的信息”页应显示与Web相同的用户ID。当前只实现账号密码登录，未模拟微信身份，也未实现微信绑定。

`miniprogram/config.js`默认请求`http://127.0.0.1:8080/api/v1`，只适合电脑上的开发者工具模拟器。本地模拟器联调时，如工具拦截该地址，可仅在开发者工具的本地调试设置中关闭请求域名校验。真机预览或发布前，须换成可从手机访问的HTTPS域名，并在小程序后台配置合法request域名；`127.0.0.1`在手机上指手机自身，不能访问电脑后端。小程序令牌保存在微信本地存储，退出后删除；401也会清理已失效令牌。

请求封装可在项目根目录运行`node --test tests/miniprogram-api.test.js`验证。当前这台Mac的开发者工具命令行服务端口未开启，原生页面登录截图尚未取得，见[D03验证记录](tasks/evidence/D03.md)。

登录后的“我的信息”页可进入资料列表、快速记笔记，也可拍照或从相册上传图片；图片详情通过携带令牌的下载请求读取原图。PDF详情可通过授权下载后使用微信原生文档预览。资料列表可按学科、状态和收藏筛选，列表及详情可收藏或取消收藏。资料详情还可提交整理任务，“我的信息”页可进入任务列表。小程序页面测试可运行`node --test tests/*.test.js`。小程序编辑页在输入时将草稿存入本机，冲突后不替换草稿，保存成功才清理。这些自动化测试不代替微信开发者工具的原生编译与点击验证，当前暂缓该步骤，见[D09验证记录](tasks/evidence/D09.md)。

### 在Windows上运行

在Windows电脑确认已有[JDK 21](https://adoptium.net/temurin/releases?version=21)、[Maven 3.9+](https://maven.apache.org/download.cgi)、[MySQL 8.0](https://dev.mysql.com/downloads/mysql/)和[Git](https://git-scm.com/download/win)，缺少时再安装。打开新的PowerShell，确认`java -version`、`mvn -version`、`mysql --version`和`git --version`可执行。若后续运行Web，需[Node.js 24 LTS](https://nodejs.org/en/download)；运行小程序则需[微信开发者工具](https://developers.weixin.qq.com/miniprogram/dev/devtools/download.html)。项目不依赖Mac专用路径。

将已提交并推送的项目拉取到Windows，在MySQL中创建独立的空开发库；已有同名开发库时可跳过建库。`root`密码与Windows本机安装时设定的一致，不要将凭证提交到仓库：

```powershell
git clone https://github.com/limou5773-oss/learning-material-system.git
cd learning-material-system
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS learning_material_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```

接着在同一个PowerShell窗口设置Windows本机数据库连接并启动后端：

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/learning_material_dev?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '你的本地数据库密码'
mvn -f backend/pom.xml spring-boot:run
```

如需在Windows上本地体验模拟候选，在启动后端的PowerShell窗口额外设置`$env:TASK_WORKER_ENABLED = 'true'`和`$env:TASK_PROCESSOR_MODE = 'mock'`，然后启动后端。打开另一个PowerShell窗口，运行`curl.exe -i http://localhost:8080/actuator/health`，应看到HTTP 200和`{"status":"UP"}`。三个`DB_`变量只对启动后端的PowerShell窗口生效。换电脑时只需在新机器配置本地环境，不需要修改Java源码或数据库迁移；Mac上的数据库内容不会随Git代码自动同步。

Windows上的图片和PDF文件默认保存在当前用户目录下的`.learning-material-system\files`，不会随Git同步。若想改位置，在启动后端的PowerShell窗口额外设置`$env:STORAGE_ROOT = 'D:\LearningMaterialFiles'`；请选项目目录外的文件夹，并自行备份该目录。

启动Web时另开一个PowerShell窗口，在项目根目录运行`cd web`、`npm ci`、`npm run dev`，然后访问`http://127.0.0.1:5173`。首次启动后端会自动执行V1至V9数据库迁移，不要手动导入整份拟议SQL。Windows上的微信开发者工具模拟器可导入同一个`miniprogram/`目录；后端也运行在这台Windows电脑时，默认本机地址可沿用。真机联调仍需HTTPS合法域名。

## 工程目录

```text
backend/       Spring Boot后端，D01至D13相关接口已实现
web/           Vue3与Element Plus，资料、任务确认及知识卡片查阅
miniprogram/   原生微信小程序，资料采集、任务确认及知识卡片查阅
docs/          需求、接口与数据库设计
tasks/         开发计划和完成记录
```

密钥、数据库凭证、真实学习资料、上传文件和本地运行配置保存在本地，不提交到仓库。
