# D19 关联确认与关系图实施计划

> 执行subagent-driven-development：后端、Web、小程序独立切片，先RED/GREEN，再规格与质量审查；根代理统一真实验证、证据与GitHub集成。

目标：完成既有D19/F17。基线main751a46f，用户要求下一个功能，延续上传与合并main偏好。GraphFlow工具发现为空，不能context/plan/index，不声称已更新图谱。沿用已有card_relation、D12/D16人工确认及CardSource权限规则，不新造AI结果。

## 范围和实现选择

复用Spring/MyBatis/MySQL以及Vue/SVG，Web关系图与D18父子图分开。相比加入独立图数据库或完整图编辑框架，当前三种关系、限量节点及点击来源只需既有库和小型SVG。小程序增加已确认关联的列表、说明与来源查阅，不复制Web大图。D20通知不包含本轮。

候选确认双端显示关系类型及方向，保持默认不选；已有确认事务继续原子保存关系，不绕过人工选择。Web新增手工确认关系和删除入口，既有D12/D16保存的关系直接可查。

## 固定接口

- RelationView沿用契约五字段：`id,sourceCardId,targetCardId,type,evidenceText`；类型仅RELATED、PREREQUISITE、EXAMPLE，来源为USER_CONFIRMED。RELATED无向，按较小BIGINT ID为source规范化；PREREQUISITE表示起点是终点的前置知识，EXAMPLE表示终点是起点的示例。后两者保留方向，可与RELATED共存，不把关联当父子层级。
- `POST /api/v1/card-relations`：`{sourceCardId,sourceCardVersion,targetCardId,targetCardVersion,type,evidenceText}`。ID为正BIGINT字符串，版本整数>=1，说明非空白<=2000 UTF-16单元，trim保存；未知字段/错误JSON类型400，业务字段无效422。身份由服务端提供，不允许客户端写origin或关系ID。
- 创建只接受本人未删两卡片，按ID顺序加行锁后检查原始两端版本（规范化交换ID不能交换错版本）。越权/不存在/已删404，版本失效409 `CARD_VERSION_CHANGED`并带首个失效卡片currentVersion；自关联422 `SELF_RELATION`；同规范化端点及类型已存在409 `RELATION_EXISTS`，不覆盖旧说明。成功201 RelationView，不改变卡片version。
- 复用V7唯一键和FK，不加新迁移或通用锁；不能使用app_user排他锁。两卡片按ID锁，兼容D12/D18；仅DuplicateKeyException可转换为明确重复关系409，其他DB异常传播。手工关系允许来源已失效但卡片仍在的本人卡片，来源查看继续隐藏受限/删除原文；说明是人工确认解释，不保证当前正文事实正确。
- `GET /card-relations?cardId=&page=1&pageSize=20`：本人已确认关系，两端均本人未删；cardId给定时需本人可读，否则404；分页1..100，按关系id倒序，匹配任一端。没有关系返回真实空items/total0，DB失败不降为空。RelationPage与既有PageView一致。
- `DELETE /card-relations/{id}`：只删当前账号该关系，存在或已无均204；不暴露他人ID存在性、不删除卡片或来源、不改卡片version，不把实际DB错误伪装成成功。
- `GET /knowledge-graphs?subject=&rootCardId=`：`{nodes:CardView[],edges:RelationView[],hasMore:boolean}`。subject最多64，trim/空白无筛选，按学科精确匹配；rootCardId可选，先验证本人未删。根筛选明确为“中心卡片及直接相邻卡片”，包含入边/出边，不声称全连通分量；若根不匹配所选学科则返回空图。两筛选组合AND。
- 节点最多200、边最多600，分别查询上限+1判断hasMore；根符合范围时优先包含根，其他节点按ID升序，边按id升序。仅返回实际发出节点间的边，两端均本人未删，不返回他人/已删端点或悬空边；有截取就hasMore=true，不能将子集描述为完整图。关系任意合法方向可成环（F17未要求关联DAG），只有自关联禁止；D18父子无环规则不变。

## Web与小程序

- Web新`/knowledge-graph`页面，卡片列表及详情有入口（详情带rootCardId）。图用节点/连线和可访问的关系列表，RELATED无箭头，另两类有方向。点击节点可查看卡片，点击关系展示原说明、两端标题与各自来源，可跳原资料；删除关系需要明确确认，失败保留数据，成功与后续图读取失败分开提示。
- 创建关系先填写两端卡片ID并明确读取核对，服务端CardView快照提供版本；ID变化使快照失效，不能提交旧卡片快照。用户选择类型、填写说明后显式确认。409保留ID/类型/说明，明确重新读取两端后再确认，不自动覆盖。关系写入成功后若重新读取图失败，必须显示已保存且读取失败，不把成功写入当成失败。
- subject/root筛选、hasMore上限提示、空图与真实失败分开；过限不暗示全图。所有请求在途禁用相关输入/删除/选择动作，处理路由切换与迟到响应、账号变化；未保存创建说明离开要确认。来源API失败显示错误与重试，不伪装“无来源”。正文/说明使用安全文本呈现。
- 候选确认页双端在原勾选说明上补类型和方向标签；保持默认不保存与既有版本、人工确认事务。保存中不允许修改候选（已有禁用保护保留）。
- 小程序卡片详情有“已确认关联”入口，新页分页读取该卡片关系；点选后读取两端卡片/来源，显示关系类型、方向和人工说明，来源已删/受限明确标注并隐藏正文。可跳卡片详情及原资料；只读查阅，不增加移动大图与新建/删除流程。

## 文件分工

1. 后端代理：card包新增Relation DTO/Row/Mapper/Service/Controller与Graph查询服务等必要文件，必要将CardService纯view映射改为包内复用以避免节点N+1；不改task事务/其他包/前端/文档。CardRelationIntegrationTest先RED，涵盖权限、版本、方向/逆序去重并发、删除隔离、D12已确认关系可查、图200/600上限+root邻域+无悬边与过滤。复用V7，不需要新migration。
2. Web代理：web/src新增关系图页面/局部组件/纯逻辑、router和CardsView/CardDetail/TaskConfirm最小入口与标签；tests新增Web图/关系行为测试。不碰小程序/后端/docs，不新依赖。实际SFC测试保护在途输入、409保留及写成功后读失败分离，图数据真实来自API，不造成功数据。
3. 小程序代理：miniprogram卡片详情入口、新关系查阅页、app.json、候选确认类型/方向、必要展示工具；tests新增Page行为测试。不碰Web/后端/docs。用真实约定的API桩做RED/GREEN，原生验收继续暂缓。
4. 根代理：文档契约、隔离运行、统一完整检查、真实HTTP与浏览器、来源失效与关系点击证据、审查及GitHub。禁止代理commit/branch/push，当前clean分支codex/d19-relations。

## 检查单

- [x] 后端权限/版本/并发去重/方向/删除/有界图与无悬边RED/GREEN。
- [x] Web类型方向/候选标签/图点击/版本冲突/在途保护与错误状态RED/GREEN。
- [x] 小程序分页、说明、双方来源、失效/请求失败及导航RED/GREEN。
- [x] 独立规格及质量审查，修复末页越界等有依据的阻塞。
- [x] 源码冻结后独立库clean verify217项0跳过；Node全量90项、Web构建、OpenAPI YAML和diff检查。
- [x] 独立UI库实际HTTP、Web浏览器创建/重复/点击依据/图/删除；小程序Page实际HTTP查阅与来源失效。截图实际打开核对，不当作原生微信或真实厂商验收。
- [x] 更新证据与进度，具备提交集成条件。GitHub上传/PR合main及远端树一致性在发布后以实际PR和合并记录核对，不在提交前声称已合并。

DeepSeek/腾讯云真实联调与微信原生验收延续用户暂缓选择；D19不编造论文资料或AI准确率。
