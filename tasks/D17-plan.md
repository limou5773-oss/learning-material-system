# D17 知识卡片管理实施计划

> 执行方式：subagent-driven-development，先RED/GREEN，再独立规格及质量审查；根代理统一验证与GitHub集成。

目标：执行既有D17/F15和双端版本冲突需求，补齐人工创建、编辑、本人搜索与卡片收藏。基线main为7e64ea0；用户要求继续下一功能，延续上传及合并main偏好。GraphFlow工具发现结果为空，采用已有计划与聚焦代码，不声称更新图谱。

方案：复用Spring Boot卡片接口、MySQL及现有Vue/小程序页面。相比引入全文检索服务或把所有编辑都转为AI任务，当前卡片字段上的参数化关键词查询和直接人工保存最符合本轮需求。D18布局与层级、D19关系图不在本次范围。

## 固定接口契约

- `POST /api/v1/cards`：title（非空白，最多255）、content（非空白，最多8000）、summary（可空，最多2000）、subject（可空，最多64）、tags（可省略或null，默认[]，最多10个非空白且各64以内，去首尾空白并按Locale.ROOT小写去重，保存原大小写）。201返回现有CardView。
- 人工创建由服务端赋origin=MANUAL、generationMode=MANUAL、version=1、isEdited=false，不捏造任务、候选键或来源；task_id/candidate_key为NULL。不能由客户端指定模式、版本、所有者、来源或父节点。
- `PATCH /cards/{id}`：version必填>=1，其余上述五字段可选，至少一个改变字段。缺省保留原值；summary/subject显式null或空白清除；title/content/tags显式null拒绝。title去首尾空白，正文保留原始换行与文本。返回200更新卡片，version+1、isEdited=true，origin/generationMode和既有来源不变。未知字段400；归属不符404；版本冲突409 VERSION_CONFLICT并带currentVersion，客户端不能自动覆盖重试。
- `GET /cards?keyword=&subject=&tag=&favoriteOnly=false&page=1&pageSize=20`：keyword最多100，标题/正文包含查询；subject/tag最多64且精确匹配；空白筛选视为不筛选。所有参数组合AND，本人未删卡片，排序updatedAt/id倒序；页码>=1，pageSize1..100，越界422。关键词为字面量（包括%/_/引号），不拼接SQL。favoriteOnly可省略默认false。
- CardView新增favorite布尔；generationMode兼容MOCK/MODEL并新增MANUAL。已有调用CardService.list(userId,page,pageSize)可保留重载，避免破坏内部契约。来源接口人工卡片返回items=[]，UI说明无自动来源；AI来源沿用可看或明确已删除/受限的约定。
- `PUT /favorites/CARD/{id}`和DELETE幂等204；只允许本人未删卡片添加收藏，不改变卡片version。`GET /favorites?type=CARD`分页卡片，type=MATERIAL维持原义，type缺省混合查询并统一排序，未知类型400。Card列表支持仅收藏筛选。复用favorite唯一键并补真实card外键，不能把数据库错误伪装为空。

## 数据与权限

V12放宽knowledge_card的task_id/candidate_key为空以支持真实人工卡片，保留AI确认的唯一键和外键约束；校验人工与AI来源组合的数据库约束须先核对现有记录/测试，不能改写已应用迁移。favorite.card_id补外键。所有新写入口从当前AuthPrincipal取得userId，服务端校验归属与版本。卡片编辑只需锁/条件更新本卡片；不改变来源、关系或父节点，不引入通用权限框架。

## 文件边界及切片

1. 后端：card/CardDtos.java、CardController/Service/Mapper/Row、material/FavoriteService/Mapper/Row、V12及CardManagementIntegrationTest。先通过真实HTTP测试观察缺失入口/搜索/收藏RED，再实现。测试必须真实注册ENABLED用户并清理本组数据；不调用厂商。
2. 客户端：web/src/knowledge.ts、CardsView/CardDetailView、新增CardEditorView及router、必要局部样式；miniprogram/pages/cards现有页面与新增editor页面、app.json、source-presentation；tests新增客户端行为测试。复用现有风格。列表搜索提交重置页码并保持已应用筛选；搜索失败明确报错，不能让旧结果冒充新结果。
3. 编辑：创建与编辑复用一个界面，展示标题、正文、摘要、学科、逗号分隔标签。409保留全部草稿、显示当前服务器版本并提供明确放弃/重载操作；读取服务器版本失败可重试且不自动换版本。草稿在客户端按账户+卡片隔离，成功后清除；不把另一个账号的卡片草稿带入编辑。页面离开提示或本地草稿按已有双端模式选择最小实现。保存失败显示错误且保留输入。
4. 收藏：详情切换，列表标记和仅收藏筛选；请求失败不伪装成功，双击保存/收藏时禁重复提交。收藏不改变内容版本。
5. 展示：人工创建与模型/模拟确认区分；AI已编辑时明确说明正文经过人工修改，来源仍为创建时历史依据，不能表示当前正文逐字出自模型。旧MOCK/MODEL测试保持兼容。

## 验证与集成

- [x] 新增HTTP测试RED：人工创建元数据和无来源、字段边界/未知字段、按版本局部更新/清空、AI编辑保留来源、本人搜索/组合与字面量、归属404、并发双端一成功一409、幂等收藏/混合分页与筛选。
- [x] 最小实现GREEN，并检查D12/D16引用、关联目标版本检查兼容。
- [x] 客户端行为测试RED/GREEN：新建、字段校验、筛选分页、409草稿与服务器版本、收藏、人工/已修改标识。
- [x] 独立规格审查，然后质量审查并关闭有依据的阻塞。
- [x] 源码冻结后独立MySQL执行`mvn -f backend/pom.xml clean verify`，核对Surefire实际数量及0跳过；`node --test tests/*.test.js`和`npm run build`；OpenAPI YAML、diff检查。
- [x] 用独立UI库真实HTTP创建、Web浏览器编辑/筛选/收藏与409，实际小程序页面逻辑通过HTTP查看和冲突；微信开发者工具原生验证延续暂缓，不伪称。
- [x] 更新接口/数据库/进度/证据与任务状态，集成材料就绪；提交、上传及PR合并main状态以Git记录为准，并核对远端树与已测树一致。

真实DeepSeek/腾讯云验收仍待本机密钥，D17人工卡片流程不需要厂商调用，不生成虚假论文资料或效果数据。
