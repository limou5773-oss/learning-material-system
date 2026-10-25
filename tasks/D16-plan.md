# D16 受限智能体规划与反馈实施计划

目标：执行既有D16/F11需求，DeepSeek提出有限工具动作，服务端校验并执行，根据结果反馈再规划；三类目标可解释工具选择，达限结束，越权动作无效。用户新要求强调最小有效改动、外部边界校验、不吞错误及不静默降级。

基线main d00a685。GraphFlow工具本轮仍不可用，已尝试发现；采用聚焦现有接口。使用writing-plans和subagent-driven-development执行已有开发计划，先RED/GREEN，再独立规格及质量审查。沿用用户明确的厂商密钥稍后配置，不能声称真实DeepSeek或腾讯云验收。

## 方案

- 显式TASK_PROCESSOR_MODE=agent启用循环；disabled/mock/model保持现有约定。沿用DeepSeek JSON客户端，不增加厂商、任意端点、隐藏思维记录或通用插件框架。
- 规划输出严格JSON：`{"actions":[{"tool":"extract_pdf_text","arguments":{}}],"stopReason":null}`。actions最多5；停止时actions=[]且stopReason为SOURCE_UNREADABLE/NO_USABLE_SOURCE/UNSUPPORTED_REQUEST之一。未知字段、重复键、尾随JSON、非法工具和参数直接拒绝。规划模型只提出动作；资料ID/版本/页段/身份/路径/epoch由服务端注入。
- 白名单prepare_source（NOTE）、extract_pdf_text（PDF）、recognize_image（当前IMAGE或本次PDF无文字页）、search_personal_materials（用户允许后）、generate_summary_cards、validate_candidate。除检索外arguments必须为空；检索严格keywords非空<=80、subject为null或非空白且<=64、limit为1..10。工具前置状态必须成立，不能未读正文就生成、未生成就校验、重复发出生成。
- 规划返回一批动作，依次执行，动作失败则停止本批，将明确错误反馈下一次规划；batchTools允许后续动作由前面成功动作满足依赖，每条仍实时校验。生成候选后只允许校验或停止。无文字PDF可转OCR，OCR失败后可明确停止。不吞数据库/权限/来源变化等不可恢复错误，不伪造空检索或成功。越权工具/参数直接失败。
- 总预算沿用8个工具步骤（包括planner），最多4次规划请求、最多8次模型请求（规划与生成合计，跨epoch累计）、最多2次生成请求（含修复）。保持300秒执行时限与OCR20请求预算。总预算内仅对瞬时模型故障有限重试；鉴权/权限/参数错误不重试。达限报明确错误，不无限循环。
- V11扩展model_attempt：purpose区分GENERATION/PLANNER，允许agent全任务最多8次编号；D15仍由业务保证最多2次，原记录默认GENERATION。规划复用D15的调用/响应元数据和UNKNOWN生命周期规则。task config记录processorMode和对应预算，部署后模式/模型漂移明确失败，不静默切换。
- 工具反馈由服务端构造，记录可观察动作、目标及错误/数量/页码，不记录模型隐藏推理或任意模型理由。规划上下文只含任务目标、受限补充说明、类型/所选范围、当前状态、允许工具和有限反馈/片段，来源文字视为待处理数据。
- `search_personal_materials`采用参数化关键词搜索本人已保存知识卡片的标题/正文/标签，支持可选学科，最多10条，每条提供版本和最多1200单元的片段并标明截取。卡片或来源已删除、受限、非本人时不可召回；数据库错误不得当成空结果。返回真实空列表可明确反馈；不称向量检索。
- 关联卡片只作上下文及建议，不允许成为主资料的伪引用。MODEL候选仍引用本次主资料片段。agent生成JSON在D15字段上增加必需relations数组，每项`sourceIndex`（0起）、`targetCardId`、`evidenceText`；服务端映射为已有CandidateRelation，RELATED类型及relationKey/target版本由服务端填。目标只允许本轮检索所得可访问本人卡片，不接受模型填写用户/版本/数据库操作。没有召回时relations只能为空。
- 调用前复验检索上下文，发布前锁定/复验关联目标版本及权限；正式关系仍由已有确认事务按用户所选原子保存，D19关系图展示不提前实现。用户不允许检索时不搜索、不把额外卡片发送外部服务；UI同意文案说明相关片段的外发范围。
- agent生成阶段暂存已校验候选；只有模型选择validate_candidate且服务端再次校验并发布后进入WAIT_CONFIRM，不能通过规划动作直接写正式卡片。

## 文件与协作边界

后端执行子任务：task包规划循环/模型请求账本/来源工具拆分/TaskCandidateService关系校验/配置快照/V11与AgentIntegrationTest，遵循ENABLED真实注册路径；不改根代理拥有的ModelOutputProtocol。

根代理：PersonalMaterialSearchService/Mapper及测试、ModelOutputProtocol.parseAgent、文档/配置脚本、统一验证与GitHub集成。客户端子任务：web/miniprogram和Node测试。

共享契约（根代理将提供）：

```java
// task.PersonalMaterialSearchService
public record Match(String cardId, int cardVersion, String title, String snippet, boolean truncated) {}
List<Match> search(long userId, String keywords, String subject, int limit);
void checkAvailable(long userId, List<Match> matches); // 调用前校验，失败明确报错
void lockMaterials(long userId, long primaryMaterialId, List<Long> cardIds); // 主+关联资料按ID预锁
void lockAndCheck(long userId, List<Match> matches); // 发布事务中校验，按cardId排序加锁
// task.ModelOutputProtocol
CandidateResult parseAgent(String content, AiTaskRow task, TaskSourcePreparation.Prepared prepared,
                          AiTaskDtos.GenerationInfo info, List<PersonalMaterialSearchService.Match> matches);
```

agent生成系统提示须要求relations字段和上述受限JSON格式；D15 parse入口不变，旧mode=MODEL仍严格不接受新增字段。

## 执行清单

- [x] 写规划/循环及预算等业务RED，实现并验证文字NOTE/PDF、图片、混合PDF OCR、失败反馈调整/明确停止、三目标和越权/取消/epoch。
- [x] 个人检索与agent关联JSON先RED，严格本人/访问/版本和精确来源契约；修复后GREEN。
- [x] 双端步骤、检索同意及关联展示兼容，Node测试先RED后GREEN，不宣称真实厂商成功。
- [x] 独立规格及质量审查通过；冻结源文件后clean verify、Node、Web构建、缺密钥真实HTTP与浏览器验证通过。
- [x] 同步配置、接口、数据设计、进度、证据及GitHub集成材料；提交/合并状态以Git记录为准。
- [ ] 本机配置密钥后的真实规划/PDF/图片与工具失败调整厂商验收（用户此前选择稍后配置，真实结果不可编造）。
