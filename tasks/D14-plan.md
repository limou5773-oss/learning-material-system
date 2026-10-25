# D14 OCR适配实施计划

目标：图片和所选扫描PDF页通过OCR获得可核验文字来源，记录真实请求尝试、质量、缓存、失败和未知费用。沿用F10及D14计划，默认关闭外部OCR；真实厂商联调需要用户在本机配置凭证，未完成前不标记D14整体验收通过。

开发基线：已合并的main，e6bc701；D12和D13已合并。本地已用GitHub API导入并校验两个合并提交的原始Git对象和文件树。GraphFlow与Context7本轮不可用。

## 设计与接口

- 提供可替换的`OcrClient`，用户选择阿里云或腾讯云，本次实现腾讯云通用印刷体识别GeneralBasicOCR（2018-11-19），支持中英文及返回置信度；固定HTTPS地址，只提交图片字节，不接受用户URL。SecretId/SecretKey仅从环境变量读取，使用TC3-HMAC-SHA256签名；不再实现百度适配器。
- `OCR_ENABLED=false`默认保持D13行为；显式启用后图片进入recognize_image，PDF仅对所选范围内无文字层的页调用OCR。没有厂商或密钥明确失败，不伪造识别结果。生成摘要/卡片仍为MOCK，真实模型留在D15。
- OCR图片由受控存储读取；扫描PDF页受控渲染。每张长边不超过4096像素、最多1200万像素，编码为不超过1MiB的JPEG；不处理用户任意路径或页段外页面。
- `ocr_page_cache`以material/version/page唯一（图片page=0），保存providerKey、TEXT/EMPTY、全文、置信度和安全请求ID。只复用一致缓存，不覆盖；厂商/预处理版本不一致要求新资料版本。
- OCR来源使用独立编号区域3_000_000+page*100+part，extractionMethod=OCR；图片来源pageNo为空，PDF保留实际页码。每片段2000 UTF-16单元、总30片段/60000文本单元，保护代理对边界，超限不截断。
- `ocr_attempt`单独记录各页各次请求：epoch、provider、page、attemptNo、dispatched、状态、错误、请求ID、耗时，费用未知为NULL。工具动作记录不当作厂商请求次数；缓存命中不增加外部调用数。
- 单页最多2次尝试，全任务最多20次；仅明确可重试的超时、429和暂时服务错误有限重试。未配置和本地校验失败标为未发送OCR；鉴权错误响应或超时可能已发送，费用保持未知。
- 网络和图像处理不持数据库锁；请求前及结果发布前验证租约、取消、访问、所有权、资料版本、文件标识。按资料→任务加锁发布缓存/来源，迟到响应不形成业务数据，但已发出尝试保留UNKNOWN记录。
- 空结果不生成假正文；图像全空明确失败，混合PDF可保留成功页并列出空识别页。缺失置信度明确未知，低于0.8提示人工核对。

共享Java契约（父代理负责ocr包，后端执行者负责task包）：

```java
package com.learningmaterial.system.ocr;
public interface OcrClient {
    String providerKey();
    Result recognize(byte[] image);
    record Result(String text, Double confidence, String requestId) { }
}
// OcrException: public ctor(String code, boolean retryable, boolean dispatched)
// getters: code(), retryable(), dispatched(); exception message仅安全错误码。
// OcrSettings: record OcrSettings(boolean enabled)。
```

## 执行清单

- [x] 传输适配器先写失败测试；实现固定端点、TC3签名、严格有界响应、总超时、禁重定向、字段/置信度校验及安全错误，测试不连接真实厂商。
- [x] 图片/扫描页、缓存与请求账本先写失败测试，再接入任务流水线。覆盖空结果、低置信度、缓存命中、有限重试、预算、跨用户/来源变化/取消/租约与epoch迟到响应。
- [x] 更新双端来源类型、页码与OCR错误说明；保留模拟生成标识，补逻辑测试。
- [x] 独立规格与质量/安全审查；运行后端全量、Node、Web构建和自制样本页面联调。
- [ ] 用户本机配置厂商凭证后，以有使用权的清晰/模糊图片及扫描PDF进行真实OCR联调，记录结果；未做则明确保留待验收项。
- [x] 同步配置说明、契约、数据设计、进度及证据，完成GitHub推送准备。是否整体验收以真实联调结果为准，不用模拟输出替代。

参考：[腾讯云通用印刷体识别文档](https://cloud.tencent.com/document/api/866/33526)。
