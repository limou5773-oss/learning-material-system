# D22 受控审核队列实施计划

> 使用writing-plans、subagent-driven-development、security-review及测试驱动开发；独立设计检查后按任务实施，规格审查通过再做质量审查，根代理负责实际联调与GitHub集成。

**目标：** 实现既有D22/F20，用户主动授权当前资料版本进入审核队列，管理员按理由留痕查看和处置，驳回限制AI发送并通知用户，保留本人查阅和修正权。

**基线：** main cf038bb4dfee652c27a4067e62a745b698651a63；分支codex/d22-content-review。GraphFlow工具发现为空，不能context/plan/index；依据需求、审核接口和相邻实现定点读取。没有自动敏感规则扫描，不将手动送审宣传为自动审核。

**架构：** V16增加content_review并扩展既有review_record/notification，复用Spring Security的ADMIN隔离、admin_control_lock及本地受控文件服务。短事务保证按版本授权、访问审计、处置、任务取消与站内通知原子完成。双端提供本人送审和历史，Web提供管理员队列，不新增小程序管理员后台或外部服务。

## 契约与不变量

- `POST /api/v1/materials/{id}/review-requests`严格`{version:正整数,reason:string}`，reason非空白、最多1000 UTF-16，保存trim值；201。锁本人未删除资料，核对version与页面展示的资料版本一致，否则409，避免并发编辑后把未看到的正文送审。触发类型固定USER_SUBMITTED，scope固定MATERIAL_CONTENT。唯一(material_id,material_version)，同版本重复409。UI说明授权此版本正文和原文件；编辑会使旧授权失效。
- 新增`GET /api/v1/materials/{id}/review-requests?page=1&pageSize=20`，本人分页历史、决定及原因，跨用户/删除/无效id404。GET `/admin/reviews?status=&page=&pageSize=`仅ADMIN；空status不筛选，其他只PENDING/APPROVED/REJECTED，page>=1、size1..100；创建时间/id倒序。非法查询类型400、业务越界422。查询失败不能伪装空。
- Review响应沿用id/materialId/materialVersion/title/triggerType/status/version/reason/decisionReason/createdAt，增加必要ownerId/type/accessScope/isCurrent。title和type送审时快照；列表绝不返回正文、摘要、storage_key或公开文件URL。isCurrent仅当前版本且资料未删除时true，旧PENDING记录保留但不可访问或处置。
- POST `/admin/reviews/{id}/content-accesses`严格`{reason}`：ADMIN、有效会话、PENDING、当前送审版本、MATERIAL_CONTENT全部成立才写CONTENT_VIEW审计并返回ReviewContent `{reviewId,material:MaterialView,fileAccessPath,auditId}`。文件路径为需同会话鉴权的`/api/v1/admin/reviews/{id}/file`，NOTE为null。不改本人身份、不借本人API越权。
- GET `/admin/reviews/{id}/file`每次复核ADMIN/PENDING/当前版本及同操作人同session已有CONTENT_VIEW；无先行授权403、旧版本/已处置409、无文件404。通过FileStorage安全打开后写FILE_VIEW，事务提交后流式返回，no-store/nosniff、安全MIME和固定文件名。每次读取留痕，不记录正文或token。
- PUT `/admin/reviews/{id}/decision`严格`{version:正整数,decision:'APPROVED'|'REJECTED',reason}`。必须同session已有CONTENT_VIEW；锁资料及记录，检查PENDING/current version/review.version，冲突409，不自动重试。APPROVED设NORMAL；REJECTED设RESTRICTED并取消QUEUED/RUNNING/WAIT_CONFIRM任务，活动工具/OCR/模型尝试标UNKNOWN且原因SOURCE_RESTRICTED；不复活任务。review.version加一，material内容version保持不变，仅access_state/updated_at变化；因此驳回后必须本人PATCH新版本才能再次送审。本人可读原文件并编辑，所有AI外发入口仍要求NORMAL。
- 处置同事务写REVIEW_DECISION审计和一条REVIEW_DECIDED站内通知，通知去重键`review:{id}:DECIDED`、target `{type:'MATERIAL',materialId,reviewId}`。既有AI_TASK target字段不变。微信渠道仍NOT_REQUESTED；不得声称真实订阅消息已发送。通知双端标已读后白名单跳本人资料。
- 请求必须单一JSON对象，未知字段/字段JSON类型错误/尾随内容400；缺失/null/枚举或数值越界/空白或超长reason422。ID为正十进制long字符串，否则404。SQL绑定参数。输出文本渲染，不v-html。

## 事务与数据关系

- 管理读取正文/文件/处置短事务依次锁：既有admin_control_lock X → actor app_user FOR SHARE → material X → content_review X → actor auth_session X；在最终session行锁取得后以新读取的clock重新验证账号仍ENABLED/ADMIN和当前session未撤销/未过期，不能只依赖等待前SQL参数时间。初次只读定位review的material_id不可作为最终校验。所有写操作统一material→review顺序。
- actor共享锁避免与既有确认流程material→newCard/user外键共享锁相冲突；复用管理控制锁使D21用户状态变化与审核串行。事务中不调用外部AI/OCR，不持锁传输文件。
- material新增UNIQUE(id,user_id)，content_review通过(material_id,user_id)组合外键且UNIQUE(id,user_id)，不新增晚到的owner app_user外键。review_record.target_user_id改可空，审核审计置NULL，用content_review_id/material_id/session_id定位，operator_id仍指当前actor。USER_STATUS旧审计不变。
- notification.task_id改可空，review_id可空，按type检查任务/审核二择一；(review_id,user_id)组合外键，原任务组合外键保留。不在资料锁后获取owner用户X锁或新增owner用户外键，避免既有任务提交user→material锁反转。
- 审计、通知或取消失败整个处置回滚；不吞DB错误。并发编辑/删除先完成则旧送审409；处置先完成则随后编辑产生新版本、限制仍保留。旧lease不可发布已驳回任务结果。

## 实施任务与验收

### 0 独立设计检查
- [x] 独立检查授权范围、审计、版本语义、锁顺序与通知外键；采用显式提交version防止并发编辑后授权未展示正文，以及最终session锁返回后fresh clock检查。其余短事务/组合外键设计未发现阻塞问题。

### 1 后端（独占Maven，独立代理）

负责backend审核包、V16、必要material/auth/task/notification修改及集成测试；不改Web/mini/docs/Git。

- [x] 先运行真实RED：`python ../thesis-workspace/scripts/d21_local_env.py test -Dtest=ContentReviewIntegrationTest test -q`。新增审核流程测试必须先因缺少功能失败；数据库SELECT1成立才算有效RED。
- [x] 实现本人提交/历史与管理员元数据，测试跨用户404、普通403、列表脱敏、唯一版本、严格请求/分页、UTC/字符串ID。
- [x] 正文/文件访问先留痕且按同session/current版本授权；测试未访问禁止文件/处置、每次文件审计、路径不泄露、旧版本/已删除/已决定拒绝；本人restricted原文件仍能读。
- [x] 测试APPROVED/REJECTED、新版再送审；驳回取消所有未完成状态、活动调用UNKNOWN、阻止新AI及旧lease发布，通知与审计同事务/去重/回滚；原任务通知JSON不变。
- [x] 实际线程/DB屏障验证编辑与处置、任务提交/确认与驳回、管理员撤销/停用和锁等待身份重新检查；不得靠屏障超时假绿。运行相关既有任务/认证/通知回归。
- [x] 冻结实现并报告实际命令、RED/GREEN数量和限制，释放Maven，不提交Git。

### 2 双端页面（独立代理）

负责web/src审核类型/安全文件读取/路由/导航/管理员页面、本人审核组件及mini本人页面、通知目标与tests；不改backend/docs/Git。

- [x] 先实际Node测试RED，再实现：`node --test tests/*review*.test.js`（PowerShell展开用既有项目Node测试命令），`npm --prefix web run build`。
- [x] 双端本人送审理由/明确授权说明、列表状态/版本/处置原因、空与失败分开、409保留草稿并显式刷新；restricted提示只能修正后重新送审，禁止AI按钮，保留原文。
- [x] Web ADMIN入口/路由；默认PENDING，状态筛选/分页、旧版本不可操作。选择项仅元数据；填写查看理由后加载正文，再授权文件；填写处置理由+明确确认。
- [x] auth/token/role/route/筛选/选择/处置变化清除受控正文和ObjectURL，迟到响应和旧401不作用新会话；写成功后列表失败仍说明已成功。在途禁重复和冲突操作，未保存理由离开需确认。错误不显示成空队列。
- [x] 通知MATERIAL目标两端白名单导航，既有AI_TASK不变。实际SFC/mini Page测试覆盖角色隔离、授权调用、409/401/403、陈旧响应与正文清除、取消确认、成功处置、通知跳转。
- [x] 冻结实现/报告测试与build；不造数据、不声称原生微信验证。

### 3 根代理：文档、集成和发布

- [x] 更新docs/接口契约.yaml、需求/数据库设计、README及审核使用说明，固定状态/版本/权限/通知目标；语法/引用验证不重写整份契约。
- [x] 规格审查通过后质量审查；修复可复现问题，最终`python ../thesis-workspace/scripts/d21_local_env.py test clean verify -q`、Node全量、Web build、YAML、diff；使用实际测试计数。
- [x] 独立UI数据库真实登录/本人送审/管理员元数据→正文理由→文件→驳回→通知→本人修正重送→批准；实际HTTP旧版本/无访问/普通角色/跨账号隔离、任务取消和审计SQL核验。实际浏览器截图并打开验证，mini Page真实HTTP，不冒充微信原生运行。
- [x] 完成证据tasks/evidence/D22.md和实际截图、tasks/plan进度及下一项D23，审阅最终diff并准备发布。提交后由根代理继续上传codex/d22-content-review、创建并attach PR，按既有授权合并main并校验远端树一致；实际结果以GitHub PR及交付回复为准，不在提交前声称已合并。清理仅本任务服务进程。真实腾讯OCR/DeepSeek/微信验收继续待本机密钥/条件，不回填虚构论文效果。
