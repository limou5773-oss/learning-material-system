# F21 管理员脱敏任务监控实施计划

> 使用 writing-plans、test-driven-development、subagent-driven-development及独立规格/质量审查，沿用tasks计划目录。基线main `b91e3b9b00698555059926aa0855f4f390e127e6`，分支`codex/f21-admin-task-monitor`。用户已确认此项及继续开发，沿用既有F21只读需求。

**目标：** 让管理员定位任务状态和工具执行问题，同时不取得私人输入、输出正文或代用户执行权限。

**架构：** 新增admin包的只读Controller、Service、参数化SQL投影；不复用本人接口DTO/SELECT *，不读取关联资料、用户、候选或调用响应正文。Web复用既有后台样式、路由鉴权和响应失效处理。无新依赖/迁移，不增加模型调用、写操作、自动轮询或小程序管理页面。

## 固定接口契约

### GET /api/v1/admin/ai-tasks

仅ADMIN。返回既有PageView结构`{items,page,pageSize,total}`，默认page=1/pageSize=20，page≥1/pageSize 1—100。按`created_at DESC,id DESC`稳定分页，查询包括后来来源删除或受限的历史任务。

可选`status`为六个任务状态（空值表示全部）；可选`from`/`to`为带Z的UTC ISO时间，按任务创建时间左闭右开，各自可单独提供；同时提供须from<to。时间须在MySQL DATETIME支持的1000—9999年；不强制90天或上海午夜限制。整型/日期格式错误400，业务范围/枚举错误422。无任务正常空页，数据库故障500而非空成功。

每个AdminTask固定12字段，全部required，明确nullable：

```text
id,userId,materialId: 十进制BIGINT字符串
goalType,status: 服务端受控枚举；未知历史值为UNKNOWN
stage: 已知阶段标识或UNKNOWN；未记录为null
stepCount: 整数
errorCode: 已知内部错误标识；未知非空标识为UNRECOGNIZED_ERROR；无错误为null
createdAt: UTC Z时间
startedAt,finishedAt: UTC Z时间或null
durationMs: 起止均存在且非负时finishedAt-startedAt的毫秒值，否则null
```

任务用时是已结束任务从开始到结束的墙钟跨度，包含人工确认等待，不称为模型推理耗时。运行、排队、待确认均无动态补零/实时增长估算。

### GET /api/v1/admin/ai-tasks/{id}/steps

仅ADMIN，id非法或任务不存在404。同样PageView，page/pageSize规则一致，按`step_no,attempt_no,id ASC`分页。只查询ai_tool_call的显式安全投影，父任务存在校验与分页计数/数据在只读REPEATABLE_READ事务内。

AdminTaskStep固定9字段：`stepNo,attemptNo,toolName,providerKind,status,errorCode,startedAt,finishedAt,durationMs`；后三者中finishedAt/durationMs可null，errorCode可null。工具名采用现有7项白名单，类型LOCAL/MODEL/OCR，状态RUNNING/SUCCESS/FAILED/UNKNOWN；未知值显示UNKNOWN，未知错误同上。用时只表示本条工具记录跨度；工具记录数不等于厂商请求次数，调用统计看D23大屏。

两接口严禁返回instruction、config/context JSON、标题/文件名、用户名、outputSummary、inputDigest、usage、候选内容、片段、引用、请求/令牌/密钥。未知自由字符串不能通过“看似元数据”字段穿透。错误/阶段/工具的白名单来自当前实际服务端常量；不改变worker原有协议。仅对admin投影执行这些隐私边界处理，明确UNKNOWN含义，不把故障吞成成功。

## 任务1 后端及真实HTTP测试

- [x] 新增`admin/AdminTaskController.java`、`AdminTaskService.java`、`AdminTaskMapper.java`、`AdminTaskDtos.java`及`AdminTaskIntegrationTest.java`，先测试RED，再实现GREEN。
- [x] 覆盖匿名401/普通403、ADMIN跨账号只读分页、严格字段集合/隐私哨兵、六状态、创建时间边界、稳定排序/页上限/高页空、格式400/语义422/非法id404、未知元数据、精确时间差/null语义。
- [x] 覆盖步骤稳定分页/空步骤/不存在任务、私有本人端点仍404、数据库异常明确500；不加写端点，不修改调度/生成代码。

## 任务2 Web页面及状态测试

- [x] 新增`web/src/admin-tasks.ts`与`admin-task-monitor.ts`（DTO/格式与状态职责可按现有模块精简）、`views/AdminTasksRoute.vue`及`AdminTasksView.vue`；修改App导航和router增加`/admin/ai-tasks`（requiresAuth+ADMIN）。
- [x] 默认全部任务；状态、可选上海创建日边界筛选、分页20条和手动刷新。上海日期转UTC复用`statistics.ts`现有时区算法：只新增公开单日转换入口，避免带入统计90天范围限制。
- [x] 选中任务查看独立步骤分页；筛选/列表分页/刷新清除旧选择，步骤异步返回需核对选择序号；账号/令牌/角色/路由改变清空数据，迟到成功/401/403不能污染新会话。
- [x] 空状态、接口错误、加载禁用、无步骤、尚未结束/未记录时间/未知元数据按文本清楚显示；不渲染HTML、不出现资料/取消/重试/代执行入口；窄屏表格只在容器内滚动。
- [x] 新增`tests/web-admin-tasks.test.js`，真实Vue路由/组件状态验证上述交互与权限，先RED再GREEN。使用现有测试装配模式，不抽象公共测试框架。

## 任务3 文档、验收与发布

- [x] 更新接口契约两个GET及完整投影Schema，需求/验收矩阵/任务清单/README说明F21已实现；保留F02与真实厂商/微信/部署验收待办，不修改历史D24事实记录。
- [x] 真实本机HTTP及浏览器：空列表、跨账号安全记录、筛选/步骤、空页/失败/普通角色、390px布局；测试资料为自编软件样本，截图不得展示私有哨兵正文。
- [x] 执行完整`node scripts/verify.mjs`，记录后端/Node真实计数、打包与Web构建；独立规格审查后质量审查，发现问题先修正。
- [x] 检查完整diff与忽略边界，准备上传、附加PR、按已有授权合并并核对main树；发布结果以GitHub实际记录和交付回复为准。清理本轮服务，保留本机私有报告。

独立规格和质量审查通过，最终完整验收32套件291项后端、280项Node、jar打包与Web构建通过；真实HTTP/浏览器及7张实际截图见evidence/F21.md。发布步骤已备好，由根代理提交后继续执行；具体上传/合并结果以实际GitHub记录为准。
