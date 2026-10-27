# D23 统一口径统计大屏实施计划

> 使用writing-plans、subagent-driven-development、test-driven-development与frontend-ui-engineering；独立设计检查后分任务实施，规格审查通过再质量审查，根代理负责手算数据、真实联调及GitHub集成。

**目标：** 执行既有D23/F22，ADMIN看到来源于真实数据库的当前库存和指定日期范围事件，正确区分调用、重试、候选、完成率和未知费用。

**基线：** main `4ce4d0dd03af151252f99a32cea0bd57059f1b53`；分支`codex/d23-statistics-dashboard`。GraphFlow/Context7工具发现为空，不能context/plan/index；定点依据需求第12节、Statistics契约、请求尝试/任务代码；ECharts按Apache官方手册核对模块导入、SVG、resize/dispose。

**架构：** 短只读REPEATABLE_READ事务聚合SQL，一次请求同一快照；不取个人正文，不用模拟增长数字。V17补足真实模型步骤尝试归属及外部尝试币种，历史未知保留NULL。Web使用按需ECharts 6.1.0/SVG，管理路由按需加载，图表有文本/表格可读数据。无小程序大屏、监控任务、自动轮询、预聚合缓存或厂商计价估算。

## 固定接口及指标

`GET /api/v1/admin/statistics?from=...&to=...&timezone=Asia%2FShanghai`，ADMIN才可访问。沿用StatisticsResponse/Statistics/CountBucket/DailyCount/CostSummary。from/to必须显式传递UTC ISO8601以Z结束（格式错误或缺参400）；timezone只Asia/Shanghai，其他422。两边均为上海自然日00:00的UTC时刻，from<to且最多90天，非整自然日/空区间/倒序/超限422。返回归一UTC带Z及Asia/Shanghai，日期桶为YYYY-MM-DD。默认最近7个自然日由Web显式构造：今日减6天至明日00:00（结束不含）；未来日期可查询，不编造数据。

Statistics保留既有16字段（以契约实际为准），仅新增必要`unknownRetryAttempts`，用于历史模型请求缺少逻辑步骤归属时单列，不能把未知当成无重试。所有数量非负整数，完成率number或null，金额BigDecimal转无指数十进制字符串。

- `activeMaterialCount`：查询快照时所有未删除资料，包括ACTIVE/ARCHIVED；`restrictedMaterialCount`：其中RESTRICTED。这两项与日期筛选无关。
- `subjects`：当前未删除资料按trim(subject)分组，null/空白归“未分类”，按count DESC/key ASC，完整返回；不受日期筛选，不含私有正文和用户明细。前端可视区域滚动，不截断总数或凭空产生其他。
- `uploadTrend`：范围内material.created_at事件计数，包括后来逻辑删除者，按上海自然日分桶，返回from至to前一天完整日期（缺天补0）。不能用updated_at或当前有效筛选替代创建事件。
- `taskStatuses`：范围内ai_task.created_at任务的当前状态，QUEUED/RUNNING/WAIT_CONFIRM/SUCCEEDED/FAILED/CANCELLED固定六桶补0；`taskCompletionRate`=SUCCEEDED/(SUCCEEDED+FAILED)，分母0返回null，其他四状态不进分母；前端只格式化，不重算口径。
- `candidateTaskCount`：范围内创建的任务中，存在validation_json.valid=true的ai_result，按任务计一次；CANDIDATE/CONFIRMED/后来INVALIDATED均计“曾产生合格候选”，包括明确MOCK流程测试，不等于真实模型质量或完成数量。无候选失败不计。
- `modelAttemptCount`/`ocrAttemptCount`：分别从model_attempt/ocr_attempt以started_at落在范围且dispatched=1计数，包含重试、FAILED、RUNNING及UNKNOWN。dispatched表示可能发送，不表示厂商成功或账单确认。dispatched=0、缓存、本地工具和ai_tool_call包装步骤都不计，不与任务创建时间联结过滤。
- `retryAttemptCount`：上述范围已发送尝试中，OCR按同任务同page的attempt_no>1；模型按同任务同逻辑step_no的step_attempt_no>1。模型现有attempt_no是任务全局序号，不能用>1把后续正常规划或首次生成算重试。
- `unknownRetryAttempts`：上述模型已发送尝试中step_no/step_attempt_no历史未知的数量，单列提示无法归属重试；已知重试只报告明确记录，不用0填补未知。
- `knownCosts`：对上述两类已发送尝试，只有cost非空且>=0、currency为明确的3位大写字母时按currency分组SUM金额、COUNT knownAttempts；币种之间不加总。MySQL使用REGEXP_LIKE(currency,'^[A-Z]{3}$','c')确保大小写精确。明确提供0允许显示0；没有已知费用返回[]。`unknownCostAttempts`：其余已发送尝试，包含缺失或无效金额/币种；用CASE WHEN已知条件THEN0 ELSE1 END计数，不能用NOT导致NULL漏计。不能补0费用或猜币种，本轮厂商适配器没有金额，不建立token→价格估算。
- `metricNotes`：返回固定中文口径说明，包括当前/期间范围、含重试和UNKNOWN、可能发送非账单、候选含MOCK及后续失效、完成率分母与零值、历史重试未知、未知金额/币种不补0，页面可展开阅读。数据库错误明确500，不伪装空结果。

## V17与模型记录的必要修改

现有模型attempt_no任务全局递增且同一任务可多次规划，没有逻辑步骤归属；cost存在但无currency，不能合法跨币种求和。

- V17给model_attempt增加nullable `step_no`、`step_attempt_no`、`currency`，ocr_attempt增加nullable `currency`。历史列保持NULL，不改已发布迁移或猜测回填；有效币种为3位大写字母，金额仍使用原cost精度。
- ModelGenerationService.request每次短事务已锁当前task，在insert尝试前取该行当前step_count为step_no，按(task_id,step_no)此前尝试数+1为step_attempt_no；同一步内部重试递增，新规划或生成步骤从1开始，旧全局attempt_no及预算不变。必要map字段、查询和真实本地HTTP重试测试一并更新；不能在数据库锁内增加外部调用。
- 必要索引：model_attempt(task_id,step_no,step_attempt_no)支持归属计数（历史NULL不冲突，应用锁保障）；material.created_at、ai_task.created_at、两类attempt.started_at支持范围查询。不引入按天快照表或泛化可插拔统计框架。
- 生产适配器不伪造cost/currency，字段支持明确账单来源的数据；本轮已知金额测试只在隔离软件测试数据中构造并明确标识。

## 实施任务

### 0 独立设计检查
- [x] 独立设计审查通过。确认模型现有全局序号不能算重试、当前持锁step_count可归属步骤、候选是曾经合格且含MOCK。费用按非负金额和精确大写币种校验，未知补集使用NULL安全CASE，保留历史无效数据的未知数量，不修改旧迁移。

### 1 后端代理（独占Maven）

负责backend新增statistics包/V17、必要ModelAttemptRow/Mapper/ModelGenerationService记录及统计和模型测试，不改Web/docs/Git，不另起代理。

- [x] 写StatisticsIntegrationTest真实RED，隔离数据库SELECT1成功才算有效失败；通过项目外d21_local_env.py注入本机凭证，禁止输出state/cnf/env。命令`python ../thesis-workspace/scripts/d21_local_env.py test -Dtest=StatisticsIntegrationTest test -q`。
- [x] 构造可手算测试数据覆盖有效/归档/删除/受限、空/多学科、上海跨日边界及区间右开、同任务多步骤首次请求/明确重试/UNKNOWN/未发送/历史未知、混币种/0金额/缺币种/缺金额、任务六状态/0分母、合格候选与MOCK/失效；断言固定脱敏DTO字段。
- [x] 实现范围校验/SQL聚合/日期补0/完成率/按币种字符串金额，测试普通403、匿名401、非法日期/时区/超限、DB失败500；校验只读事务同快照而非多次独立不一致查询。
- [x] 模型请求归属真实RED→GREEN；使用既有本地HTTP测试验证正常多次规划及首次生成不是重试，同步骤格式修复/可恢复错误才递增步骤尝试，旧预算和全局计数/lease保护不变。历史NULL单列，不回填。
- [x] 运行相关model/agent/OCR及原统计新测试，冻结并报告实际命令/数量，释放Maven，不提交Git。

### 2 Web代理

负责web/与tests/统计测试；不改backend/docs/Git。可新增精确锁定echarts6.1.0依赖，既有样式基础上实现统计页，不做全站重设计。

- [x] 先Node真实SFC/路由/图表生命周期RED，再实现ADMIN导航“统计大屏”及`/admin/statistics`按需加载；普通角色隐藏且不请求接口。模板和公共接口风格沿用既有ADMIN页面，token/role/route变化清除数据并忽略迟到成功及旧401/403。
- [x] 日期必填，结束不含，显式上海转换；默认近7天/可近30天，最多90天；显示应用后的实际范围与当前库存不受日期筛选。查询中禁重复，错误/空/加载分开，不伪造0完成率/0费用，不悄悄保留旧值冒充新范围。
- [x] 显示当前有效/受限、模型/OCR/已知重试及历史未知、候选和完成率/待确认；上传折线、当前学科条形、任务状态分布使用真实数据，空系列显示明确空状态而不画假扇区。金额分币种，缺失显示“未提供”，未知尝试单列；0分母完成率显示“暂无可计算任务”。
- [x] 使用ECharts按需core+Line/Bar/Pie+必要组件+SVGRenderer，组件有明确尺寸、ResizeObserver resize、卸载dispose；tooltip避免HTML拼接学科输入（renderMode richText或关闭），aria/文本表格替代可键盘访问。图表只负责展示，不重新计算业务指标；响应式桌面及窄屏可读。
- [x] 实际SFC测试默认参数、日期区间、数据/空/500/401/403、角色导航、刷新/范围变化、换号/离页迟到响应；图表选项从真实props生成，生命周期resize/dispose测试；Node全量和Web build，冻结报告。

### 3 根代理：真实证据与发布

- [x] 更新接口契约、统计说明/需求/数据库/进度，解释尝试表来源、步骤归属、历史未知及厂商无金额；官方资料仅引用相关ECharts手册，无凭空资料。
- [x] 独立规格→质量审查，修复阻塞；最终clean verify、Node全量、Web build、YAML引用和包括新增文件的diff检查。实际测试计数，不以工具无结果当通过。
- [x] 在独立统计验证库构造明确软件测试数据，手算和SQL对照跨日/含重试/UNKNOWN/混币种/null；空库另核对空状态。实际HTTP和浏览器ADMIN/普通权限、日期查询/空/错误/图表resize，截图逐张打开；这不是真实厂商账单或论文实验。
- [x] 完成tasks/evidence/D23.md、实际截图、说明及下一项D24，完成发布准备。提交后根代理继续上传GitHub、创建并attach PR，按既有授权合并main并核对远端内容树；实际发布结果以GitHub与交付回复为准，不预先声称已合并。清理任务自有服务。D24真实AI、微信原生和论文材料仍待验收，不将软件统计桩冒充实测效果。

## 最终边界与真实运行修正

独立审查后补充历史上海夏令时（23/25小时日及跨变化90自然日）和缺timezone400回归；上传改为一次SQL的有界参数化自然日CASE，前端按日期Intl换算，不写死8小时。真实320px导航裁切以小范围现有CSS修正、实际边界和390px图表resize复核。真实空500响应引起英文JSON错误，新增4项API响应RED→GREEN，在解析层保留HTTP状态与cause并明确中文报错，不伪装空数据；独立规格/质量复审通过。最终后端277、Node182、Web构建通过，真实软件手算数据17字段逐项一致。所有截图已打开核对，发布前包括新增文件的diff检查由根代理执行。
