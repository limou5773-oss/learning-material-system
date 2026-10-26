# D21 用户管理与会话撤销实施计划

> 使用writing-plans、subagent-driven-development、security-review及测试驱动开发；先对权限/并发设计独立审查，再后端和Web实现，根代理统一真实验证及GitHub集成。

**目标：** 执行既有D21/F19，提供Web管理员用户分页/启停/原因审计，停用撤销全部双端会话，禁止自行提权和默认明文管理密码。

**基线：** main3bba032b042f49d081bee5cb81919f8edac2dfd3；分支codex/d21-user-admin。GraphFlow发现为空，不能context/plan/index，定点读取需求、auth和新admin入口。

**架构：** 沿用Spring Security逐请求从DB读角色/状态，admin URL服务端ADMIN隔离；V15只增加用户状态审计所需review_record及单行admin_control_lock。直接短事务完成启停、会话撤销和审计；单行锁串行化管理员状态变化及首次初始化，用户行按ID顺序锁。登录按用户行锁与启停串行，防止停用漏撤销并发新会话。无新依赖、角色编辑API、私有资料查看或小程序管理后台。

## 固定接口和业务语义

- `GET /api/v1/admin/users?keyword=&status=&page=1&pageSize=20`：ADMIN才可访问，普通用户403、无效会话401。UserPage沿用PageView；UserView严格七字段`id,username,displayName,role,status,hasWechatBinding,createdAt`，不返回passwordHash、令牌摘要、openid、密钥或资料正文。hasWechatBinding沿用当前未接入的false，时间UTC带Z，ID十进制字符串。
- keyword去首尾空白，空白不筛选，最多100 UTF-16；按用户名/显示名称字面子串检索，SQL参数绑定并转义LIKE通配符。status可空/空白无筛选，仅ENABLED/DISABLED，未知422；page>=1/pageSize1..100，业务越界422、类型错误400；按created_at DESC/id DESC。数据库失败不降为空集合。
- `PATCH /api/v1/admin/users/{id}/status`严格单一JSON对象`{status:'ENABLED'|'DISABLED',reason:string}`。错误JSON类型、未知字段、尾随内容400；缺失/null、空白reason/无效status或reason超过1000 UTF-16为422。reason trim保存；不得接收actor/userId/role等字段。无效/不存在目标404。
- 返回200 UserView；只修改status/updated_at，保留账号、角色、密码和资料。每次合法请求都写USER_STATUS审计（同值也是一次明确管理员操作，before/after可相同）；DISABLED总是撤销该用户全部未撤销会话，ENABLED从不复活旧token。created_at不变。
- 禁止停用当前操作账号409/SELF_DISABLE_FORBIDDEN，禁止停用最后启用管理员409/LAST_ENABLED_ADMIN；可以启停其他管理员但不提供授予ADMIN角色接口。最后一个管理员的检查优先于自停用时，允许返回LAST_ENABLED_ADMIN，只要两个约束均保持。两个管理员互停时必须只成功一个，等待者复核已失效身份而失败，至少保留一位启用管理员。
- 管理写事务先锁admin_control_lock单例，再按ID升序锁actor/target，重新验证actor仍为ENABLED/ADMIN、principal.sessionId仍属于该账号且未撤销/未过期；已失效401、失去角色403，防止过滤器通过后等待锁期间身份失效。sessionId只来自AuthPrincipal，客户端不可提供。GET遵循通常已开始请求语义，不承诺中断已在处理的请求；停用提交后的新鉴权立即401。
- 状态更新、全会话撤销和审计同事务，审计失败全部回滚。review_record保存operator_id/target_user_id/action=USER_STATUS/reason/before_status/after_status/created_at；不记录密码、token或资料正文。D22再增加审核相关字段，不预建审核队列或任意正文访问能力。
- AuthService.login在读取账号状态、校验密码与插入会话期间持该用户行锁（完整当前行，避免MVCC旧状态）；与停用串行。停用先提交则登录失败；登录先提交则新会话随停用一起撤销。重新启用后只允许新登录，原有WEB/MINIPROGRAM token始终无效。不要新增app_user全局锁或改变任务/卡片流程。

## 首个管理员初始化

- 默认关闭的启动初始化：`ADMIN_BOOTSTRAP_ENABLED=false`；显式true才读取`ADMIN_BOOTSTRAP_USERNAME/PASSWORD/DISPLAY_NAME`创建一个新的ADMIN/ENABLED账号。没有默认用户名或密码，没有Web初始化/提权接口。
- 用户名沿用注册规则6..32英数下划线并规范小写；密码沿用8..64且UTF-8<=72字节，非空白；显示名非空白<=64。配置不满足则明确失败，错误信息和日志不能包含密码。不要使用会在异常/toString中展开原密码的配置输出。
- 初始化使用同一admin_control_lock，在同一事务检查任何ADMIN角色记录是否已存在；存在则拒绝显式初始化（包括disabled），不得重设密码、重新启用旧管理员或把同名普通用户升级。用户名被占用直接拒绝。两个初始化并发最多创建一个；编码BCrypt后保存，不存明文。
- 启动成功后移除初始化开关及密码，后续正常启动不会改账号。后端代理负责配置、服务和启动runner；根代理负责交互隐藏密码的Windows启动辅助脚本及说明，不把本机测试凭证提交仓库。

## Web

- ADMIN导航显示“用户管理”，新/admin/users页面；普通用户直接进入时显示无权限页且不请求管理API。路由同时检查已登录和当前客户端角色，服务端权限是最终依据。不增加小程序管理入口。
- 用户表仅七字段必要信息，支持关键词、状态和分页；筛选重置页1，高页空仍可上一页。每行选择为启停对象后，显示账号/当前状态/目标状态，输入原因并显式确认。自停用入口禁用并说明，服务器约束仍有效。
- 保存失败保留选中账号、目标状态及原因；409不自动重试。保存成功与后续列表读取失败分开显示，成功更新后不假装写失败。在途禁用相关控件，未保存原因离开/切换需确认。只文本渲染，无v-html。
- route/account/token/role变化清除账号列表与草稿，迟到响应或旧401/403不能作用于新会话；当前会话401退出并回登录，当前403显示无权限，不伪装空用户。

## 分工与检查单

### 0 设计审查（只读独立代理）

- [x] 对状态/登录/会话/首次初始化锁顺序、最后管理员保护、审核与后台边界、配置凭证做对抗性检查；修正真实缺口后实施，不因假想情况扩展范围。

### 1 后端代理（独占Maven）

负责backend新增admin包/V15、必要auth映射和login锁/SessionMapper全撤销及复核查询、SecurityConfig admin matcher、bootstrap配置/服务/runner、application.yml/.env.example最小新增；测试AdminUserIntegrationTest/首次管理员测试。不得Web/mini/docs/脚本/Git。

- [x] 先真实RED：普通用户403、未登录401、七字段脱敏、字面关键词/分页/状态、严格JSON与原因；状态+撤销+审计原子；目标不存在/自停/最后管理员。
- [x] 真实注册帐号+HTTP验证WEB/MINI token停用后401、重新启用不恢复旧token且新登录成功；恶意注册/管理角色字段拒绝。
- [x] 登录与停用两顺序、两个管理员互停、已通过过滤器但被撤销的等待写请求、审计故障回滚；用可证明已到达的DB屏障或线程同步，不靠sleep或屏障超时假绿。
- [x] bootstrap默认无动作、显式创建/BCrypt可登录、无效配置/同名普通用户/已有ADMIN均不修改、并发只一项成功；输出不含密码。测试仅隔离库，清理audit/guard支持行按正确顺序。
- [x] 当前任务/确认/卡片回归与登录安全回归，冻结并释放Maven，报告实际RED/GREEN，无Git提交。

### 2 Web代理

负责web/src管理页/route/逻辑/无权限页、App.vue/router.ts、tests/web-admin-users.test.js，不改backend/mini/docs/Git。

- [x] 实际SFC/MemoryRouter RED→GREEN：普通角色隐藏/拒绝/不调用API，ADMIN列表筛选/分页/空与失败、启停原因/确认/成功、409保留、在途锁定、写成功再读失败、脏输入离开、旧响应隔离。
- [x] Web build、自查冻结；无新依赖、虚构用户或伪造成功。

### 3 根代理

- [x] 准备显式初始化说明/隐藏输入脚本和契约/数据库/进度；真实凭证只在项目外测试环境。
- [x] 独立规格→质量审查，实际修复阻塞；最终clean verify实际计数0跳过，Node全量、Web build、YAML/diff。
- [x] 在独立UI库通过真实初始化创建测试管理员（不SQL提升角色），真实注册测试普通账号及双端登录；实际HTTP/浏览器启停、原因与审计、普通403/双端旧token401/重启用后新登录，原Mini Page真实HTTP失效跳登录。截图实际打开。
- [x] 准备提交集成；上传PR合main与远端树一致性将在发布后以实际PR/合并记录核对，任务自有运行进程在验证后清理，发布前不声称已合并。下一项D22审核，不提前实现。

OCR/模型/微信真实厂商及原生微信验收维持既有待办。本轮不触碰论文正文或声称具备任意管理员读取个人资料的权限。
