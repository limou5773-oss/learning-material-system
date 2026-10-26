# D20 站内通知实施计划

> 使用writing-plans、subagent-driven-development和测试驱动开发。后端、Web、小程序按固定契约分工；根代理统一文档、真实验证、两阶段独立审查及GitHub集成。

**目标：** 执行既有D20/F18，任务进入WAIT_CONFIRM或FAILED时同事务生成本人站内通知；双端分页、未读筛选、已读切换及跳转任务详情。

**方案：** 沿用Spring/MyBatis/MySQL，V14新增notification。直接在现有短事务的状态成功转换处调用通知服务，唯一键去重；无需轮询扫描任务或引入事件总线/消息队列。状态和通知必须一起提交或一起回滚。双端手动刷新，不增加后台轮询。

**范围：** 微信绑定、订阅授权和模板条件尚不具备，本轮只启用站内渠道，新通知channelStatus=NOT_REQUESTED。页面明确“微信订阅消息尚未开通，站内通知正常”；没有真实微信发送、PENDING/SENT/FAILED假状态或外部调用。按照既有计划记录渠道受限原因，不让其阻止站内交付。成功/取消通知及D22审核通知不在本轮必做范围，不追溯生成历史终态通知。

基线main70abfbd6f872638f449153be8eb7d9cfbcce0f67，分支codex/d20-notifications。GraphFlow工具发现为空，不能调用context/plan/index，按需求及定点入口检查。

## 固定契约与业务规则

- NotificationView：`{id,type,content,target:{type:'AI_TASK',taskId:string},isRead,createdAt,channelStatus:'NOT_REQUESTED'}`。ID始终十进制BIGINT字符串，时间UTC带Z；类型RESULT_READY/TASK_FAILED。正文服务端固定文案，不拼原笔记、模型原文、异常堆栈、openid或密钥。target为白名单业务参数，不接收/返回任意URL。
- `GET /api/v1/notifications?unreadOnly=false&page=1&pageSize=20`：PageView `{items,page,pageSize,total}`；page>=1，pageSize1..100，按created_at DESC/id DESC。限制当前登录用户，空集合是正常结果，DB错误不能伪装为空。普通未登录401。unreadOnly按布尔true/false查询，错误参数400；业务分页范围422。
- `PUT /api/v1/notifications/{id}/read-state`：严格JSON `{isRead:boolean}`，额外字段/错误JSON类型400，缺失/null422。本人存在返回200 NotificationView，陌生/不存在/无效ID统一404；true幂等保留首次read_at，false清read_at。不能改正文、归属、target或channelStatus，不新增记录。更新时间不改变createdAt，列表按创建时间排序。
- V14 notification：id/user_id/task_id/type/dedupe_key/content/target_json/read_at/channel_status/created_at。UNIQUE(user_id,dedupe_key)，稳定键`task:{id}:WAIT_CONFIRM`或`task:{id}:FAILED`；同任务重复状态不新增、不覆盖用户已读状态。ai_task补UNIQUE(id,user_id)，notification组合FK(task_id,user_id)指向ai_task(id,user_id)，硬删task级联清除通知；用户存在性经任务既有FK保证，未改既有用户硬删限制，正常业务不提供硬删task。组合FK同时保证任务/所有者对应，避免通知INSERT末尾直接userFK取得用户S锁与submit/retry的用户X锁交叉；用真实屏障并发回归验证。本人时间及本人未读索引，必要非空/枚举约束。
- 事件覆盖：TaskCandidateService.finish（MOCK、MODEL及agent最终发布的共用入口）markCandidateReady成功后RESULT_READY；TaskLeaseService.fail和claim超时分支仅状态实际变FAILED后TASK_FAILED；TaskConfirmationService在来源过期的显式提交失效分支实际failStaleConfirmation后TASK_FAILED。新通知与相应状态在同一现有事务；旧epoch/已取消/已结束任务无通知。通知写失败必须回滚状态转换和候选写入，不能catch成成功。
- 不增加app_user排他锁，不改现有材料→任务→卡片锁顺序；通知行唯一约束保护去重，仅处理重复键，其他SQL失败传播。无需增加死锁重试、通用锁或额外客户端状态版本。

## 实施切片与文件归属

### 1 后端（后端代理独占Maven，完成后释放）

创建notification包的Controller/Service/Mapper/Row/Dto及必要严格Boolean解析；V14迁移；修改TaskCandidateService、TaskLeaseService、TaskConfirmationService的依赖注入和明确状态转换后的调用。其余代码不重构。

- [x] 先写NotificationIntegrationTest运行RED：本人读写/越权/严格JSON/分页/已读幂等及未读过滤。
- [x] 写状态生命周期RED/GREEN：真实注册任务经MOCK完成待确认、受控失败、超时、来源版本失效；旧epoch/重复/取消无假通知。并发同事件唯一、已读不被重放重置；用DB触发器实际注入通知写失败，验证任务/候选与通知原子回滚。
- [x] 运行D20及任务相关回归，冻结文件，报告实际测试/日志并释放Maven；不提交Git。

### 2 Web（Web代理）

新增notifications.ts与NotificationsView/Route必要文件，router.ts和App.vue通知入口。不得动backend/mini/docs。沿用现有风格，不加依赖。

- [x] 实际SFC/MemoryRouter行为测试先RED再GREEN，覆盖全量/未读分页、总数跨页变动、已读切换成功后列表一致。
- [x] 明确空/失败/加载/渠道未开通；无v-html。请求在途禁用重复操作，筛选重置page1；高页收到空集合仍可上一页，不伪装完全无通知。
- [x] 点击“查看任务”先真实保存isRead=true，再跳`/ai-tasks/{taskId}`；写失败保留当前页并提示，不跳转；写成功后读失败和写失败分开。只能白名单target参数导航，不读取任意URL。
- [x] 路由/账号变化隔离旧数据及迟到响应，401处理不清新会话；自行build并冻结，不提交Git。

### 3 小程序（小程序代理）

新增pages/notifications/index四文件和必要utils展示，app.json、me/index.js+wxml通知入口；不得动Web/backend/docs或共享api（D19已有旧token401保护）。

- [x] Page真实行为测试RED→GREEN：分页/未读、已读切换、点击先保存再导航、写失败不导航、正确任务路径、渠道未开通及错误重试。
- [x] page>1始终可返回，空页与完全空分开；保存/加载在途锁定，筛选重置首页。离页/账号变更隔离迟到响应，旧401不能退出新token。仅白名单业务target导航。
- [x] 自查并冻结，不运行Maven/提交Git；原生微信验证继续暂缓，Page桩与真实HTTP分开记录。

### 4 根代理验证与集成

- [x] 更新OpenAPI、数据库/README/开发进度与证据；不用论文章节或真实AI实验数据冒充软件验证。
- [x] 独立规格审查→质量审查，关闭高置信阻塞并加针对性回归。
- [x] 冻结后独立MySQL `clean verify`并核对实际0跳过，Node全量、Web build、YAML和diff检查。
- [x] 独立UI库实际MOCK任务待确认及真实未配置处理器失败通知；双端会话列表/读状态一致，浏览器与原Mini Page实际HTTP查阅、未读筛选和跳转，截图实际打开。
- [x] 审查、真实验证与证据具备集成条件。上传、PR合main及远端树一致性在发布后以实际PR/合并记录核对，提交前不声称已合并；任务自有运行进程在验证后清理。

下一项为D21用户管理。真实微信订阅渠道待账号绑定、授权和模板条件满足后，按当前官方文档实现提交后异步发送；渠道失败只影响渠道状态、不能回滚站内通知或任务结果。本轮不增加未经验证的发送适配器。
