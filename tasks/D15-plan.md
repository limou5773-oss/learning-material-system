# D15 DeepSeek结构化生成实施计划

目标：依照既有D15计划，接入用户选择的DeepSeek，生成带可核验引用的摘要、标签和知识卡片，经过人工确认再保存。

架构：复用现有NOTE/PDF/OCR来源提取和确认流程；默认处理器disabled，显式TASK_PROCESSOR_MODE=model才调用真实模型，mock保留。固定DeepSeek官方HTTPS接口，不提供模型可用的数据库写入或工具执行能力。D14真实腾讯云验收仍待凭证，不用本地OCR桩替代。

基线：main 3b5883e。GraphFlow及Context7本轮无可用工具，已尝试发现；聚焦现有任务/候选/确认契约。执行采用subagent-driven-development，先失败测试、后实现，最后独立规格和质量审查。

## 设计

- 使用DeepSeek Chat Completions JSON模式，固定https://api.deepseek.com/chat/completions；截至2026-10-25官方文档列出deepseek-flash与deepseek-v4-pro，默认deepseek-flash，关闭thinking，stream=false，max_tokens=4096。模型标识可通过MODEL_NAME配置，API密钥只从MODEL_API_KEY读取；MODEL_PROVIDER=deepseek。未配置明确失败，不回退假生成。
- HTTP传输：5秒建连、45秒完整响应总超时、2MiB响应上限、禁重定向；严格验证JSON、finish_reason=stop、非空正文、安全模型/请求标识和非负token数。错误不包含原始响应、提示、密钥或异常cause。模型tool_calls必须拒绝，输出不能执行任何动作。
- 模型只返回严格JSON：顶层summary、tags、cards；每张卡片title、content、summary、subject、tags、sources；每条引用chunkId、quoteText。不让模型填写taskId、generationMode、候选键或offset；服务端生成候选键并在已提供来源中定位精确原文，推导UTF-16 offset。拒绝未知字段、重复键、尾随JSON、缺字段、空值、过长文字、伪造/其他任务/未选页chunkId、非原文引用。
- 候选generationMode=MODEL，保留MOCK兼容；generationInfo记录provider、model、promptVersion。页面、正式卡片、确认入口明确区分真实模型与模拟生成；均提示需要核对原文。
- 输入只有有权访问、同意处理的所选固定版本文字来源，最多30片段/60000 UTF-16单元；图片未OCR时不把标题当正文供真实模型生成。NOTE超过真实处理上限明确失败，不沿用mock最多1500单元的截断。
- 外部I/O不持锁；调用前与发布前按资料→任务锁顺序复验用户ENABLED、所有权、正常访问、版本、输入/文件快照、租约与epoch。取消、删除、失租、停用和来源变化不发布迟到结果。
- V10 model_attempt记录每次可能发送的请求、epoch、provider、requested/response model、prompt版本、requestId、token数、错误、耗时、费用NULL；每任务最多2次尝试（跨epoch累计），含一次可重试异常或一次格式修复；鉴权/无配置不重试。取消/删除/租约清理与旧epoch隔离沿用OCR规则。
- 生成成功只形成WAIT_CONFIRM，确认仍原子/幂等保存。JSON有效不等于事实正确或引用足以支持卡片，界面保留人工核对提示。

## 共享Java接口

根代理拥有model包、应用配置、本机脚本/文档。后端子任务拥有task包、V10、相关生命周期与集成测试。客户端子任务只改web/miniprogram及Node测试。

```java
package com.learningmaterial.system.model;
public interface ModelClient {
    String providerKey();
    Result generate(String model, String systemPrompt, String userPrompt);
    record Result(String content, String model, String requestId, Long inputTokens, Long outputTokens) { }
}
// ModelException(String code, boolean retryable, boolean dispatched)
// getters code(), retryable(), dispatched(); message only safe code
// ModelSettings(String provider, String model)
```

候选DTO：CandidateResult追加GenerationInfo generationInfo（provider、model、promptVersion三个字符串），原11参数构造器兼容MOCK旧用例。正式卡片generationMode已有字段按MODEL显示；供应商信息通过候选generationInfo与请求账本记录，不声称旧卡片具有此信息。

## 执行步骤

- [x] 本地HTTP传输测试先RED，再实现DeepSeek客户端和默认安全配置。
- [x] 业务先RED：严格结构、伪引用、格式修复、模型记录、请求预算、真实注册用户、取消/删除/失租/停用/来源变化，接通MODEL模式与确认保存；保留原MOCK回归。
- [x] 双端按generationMode呈现真实/模拟结果，模型与质量信息、错误、外部处理同意文案一致，Node测试先RED。
- [x] 独立规格审查后进行质量审查，修复缺陷并全量Maven verify、Node与Web构建，真实HTTP检查缺密钥分支。
- [x] 核对本机配置状态（仅存在性，不输出值），用户已明确密钥稍后配置；无密钥时配置指引/脚本就绪并保留真实厂商待验收，不填造结果。
- [x] 更新接口、数据设计、README、进度与证据，完成提交与GitHub推送准备；按前序集成方式合入main。

官方参考：[模型标识](https://api-docs.deepseek.com/quick_start/pricing/)、[JSON输出](https://api-docs.deepseek.com/guides/json_mode/)、[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)。

- [ ] 配置密钥后的真实DeepSeek小样本与计费记录对照验收（用户选择稍后进行）。
