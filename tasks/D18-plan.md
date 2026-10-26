# D18 Web布局与层级思维导图实施计划

> 执行：subagent-driven-development，独立后端/前端按固定契约RED/GREEN，再规格及质量审查，根代理统一验证和GitHub集成。

目标：完成既有D18/F16，保存Web卡片位置和顺序，支持版本冲突与无环父子层级。基线main为664a0fb。用户要求继续下一功能，延续上传与合并main偏好。GraphFlow发现为空，无法context/plan/index，不声称更新图谱。

架构：沿用既有Spring/MyBatis/MySQL和Vue，新增card_layout迁移V13；原生Pointer Events拖拽，数字坐标/顺序与键盘移动作为替代操作；SVG/HTML展示父子层级。相比增加完整图编辑库或把ECharts当编辑器，当前小型实现无需新依赖。D19关联类型、依据与关系图不在本轮；小程序沿用卡片查阅，不复制Web画布。

## 固定契约

- `GET /api/v1/card-layouts/{layoutKey}`：layoutKey匹配`[A-Za-z0-9_-]{1,64}`，本人布局，正常不存在返回`{layoutKey,layoutVersion:0,items:[]}`；不是数据库异常降级。键区分大小写（迁移列使用binary collation），Web初版只用default。
- `PUT /card-layouts/{layoutKey}`：`{layoutVersion:int>=0,items:[{cardId:string,x:number,y:number,orderNo:int>=0}]}`；完整替换本人该画布，最多200项，cardId不重复、orderNo不重复，坐标有限且位于[-100000,100000]，orderNo不超过2147483647。支持items=[]清空。所有卡片为本人未删；无效引用404，不得落部分数据或静默丢弃。
- 仅version=0能创建缺省布局，从1开始；每次成功整体version+1，旧版本409 `LAYOUT_VERSION_CONFLICT`并带currentVersion（不存在时0）。返回`{layoutKey,layoutVersion,items}`，GET不静默过滤历史失效引用，客户端明确提示并由用户移除后保存；保存layout不改变卡片内容version。
- `PUT /cards/{id}/parent`：`{version:int>=1,parentCardId:string|null}`，parentCardId字段必须存在，null表示根节点；返回现有CardView。归属/已删/不存在404，旧card version409 `VERSION_CONFLICT`+currentVersion；自父/间接环422 `PARENT_CYCLE`，已有祖先失效/成环明确拒绝。成功card version+1/updatedAt更新，正文、标签、来源、origin/generationMode/isEdited保持，不暗示改写模型正文。
- 只接收以上字段，复用Jackson未知字段拒绝和既有ApiException协议；外部输入做必要校验，不吞DB/权限/状态错误，不自动覆盖重试。

## 并发和数据库

V13创建card_layout，user_id/layout_key唯一、JSON坐标、version、UTC时间及user外键；同迁移新增card_organization_lock每用户一行，专用于组织写入互斥。不改写已发布迁移。

布局保存先幂等建立并锁定本人card_organization_lock行，再layout行，最后校验卡片；同账号初次创建与更新串行，避免缺行间隙锁竞态。仅正常不存在返回0；数据库失败仍失败。原拟议app_user排他锁已在真实D12确认屏障回归中复现app_user/knowledge_card死锁，故改用专用组织互斥，避免与新卡片插入的用户外键共享锁冲突，不改变task流程。

层级修改同样先锁组织互斥行，再将子卡片与请求父卡片按ID顺序锁定，检查version与归属。父关系串行保证A→B与B→A并发不能都成功；祖先链用本人未删id/parent轻量映射读取，无需逐层额外锁，避免与D16按ID锁卡片形成交叉锁环。请求父节点也预锁，覆盖FK隐含父行锁。D12确认仅在同批新卡片间设父级，不改已有卡片，保留原事务。没有自动死锁重试或通用锁框架。

## Web交互与范围

- 新`/cards/organize`页，从卡片列表进入，先读取default布局。通过现有卡片分页接口每页100读取当前关键词/学科/标签范围，最多200；若超过200明确要求缩小筛选，不把首200冒充全库。父节点不在当前范围时明确显示“父卡片未载入”，不能假作真实根。
- 布局items是完整快照，保存按钮明确“保存当前画布（N张）”，界面说明保存将以当前范围替换整份default布局；筛选变更或重新读取时，若有未保存位置，先由用户确认放弃。不得无提示丢弃其他筛选范围的旧布局。已保存但当前未载入的卡片ID要有明确提示。
- 可切换画布/层级图。画布显示当前卡片标题、模式及内容摘要，可打开详情。拖拽只调整位置/堆叠顺序，不改变父子；提供坐标输入/顺序按钮或键盘替代。允许负坐标时用显示原点偏移呈现，存储仍为原逻辑坐标。指针取消/失焦及时结束移动，保存/加载期间禁用一切会改变本次提交的操作，避免在途修改丢失。
- 默认未保存卡片排列为网格，保存后刷新能还原；布局409保留本地坐标和旧version，显示服务器version，可重试读取服务器快照并显式放弃本地后载入，不自动覆盖。页面离开有未保存布局提示；不增加跨账号共享的本地缓存。
- 层级设置：选择当前卡片和父卡片（可清为根），提交card version；服务端最终验环，失败保留选择与输入。层级图仅画当前范围的父子关系，选区外父节点有标识。不是D19的知识关联图，不宣称AI生成层级。

## 切片与文件边界

1. 后端代理：card下新增LayoutDtos/Row/Mapper/Service/Controller、CardHierarchyService（可按职责选择等价名字）、CardController/Dto/Mapper必要扩展、V13及CardOrganizationIntegrationTest；不改task、其他模块或前端。用真实HTTP+注册ENABLED用户先RED。测试布局0/既有版本并发、一赢家一409、越权/失效引用、边界和原子性；层级自/间接环、并发互设父只有一成功、旧版本、来源与内容元数据不变。
2. 前端代理：web/src新组织页面、局部components/纯逻辑模块、router和卡片列表入口，tests新增组织行为测试；不改后端/docs/现有业务。真实拖动、坐标/顺序、恢复/冲突、筛选超限及层级解析先RED；复用现有风格，不引入新图框架。可用现有真实SFC+Router测试技术，避免只匹配源码。
3. 根代理：文档/契约、隔离运行、独立完整验证、浏览器截图、审查与GitHub集成。初始clean，in-place codex/d18-layout分支，不新建用户聊天。

## 检查单

- [x] 后端RED/GREEN：布局持久化/并发/归属/边界，层级并发防环及版本保护。
- [x] Web RED/GREEN：拖拽/替代操作、恢复、冲突保留、图层级及范围说明、在途操作锁定。
- [x] 规格审查及质量审查，修复有依据的阻塞。
- [x] 冻结后独立库clean verify，实际Surefire计数0跳过；Node全量、Web类型/构建、OpenAPI YAML及diff检查。
- [x] 独立UI库实际HTTP与浏览器：拖动→保存→重新载入恢复；第二会话布局冲突保留；设置父级/拒绝成环；看真实截图，无真实厂商调用。
- [x] 更新进度/证据及集成材料；提交上传与PR合入main状态以Git记录为准，核对远端树与已测树相同。

真实DeepSeek/腾讯云及微信开发者工具原生验收延续此前暂缓，D18不编造厂商结果或论文实验材料。
