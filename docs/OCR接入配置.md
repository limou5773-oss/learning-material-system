# 腾讯云OCR本机配置

直接修改原YAML；旧环境配置脚本已移除。

本次D14使用腾讯云文字识别的**通用印刷体识别 GeneralBasicOCR**，API版本2018-11-19，中英文混合。暂不接表格行列结构、英语专项或阿里云适配器。厂商调用与业务通过OcrClient隔离，之后可以另加适配器。

## 配置

准备具有OCR调用权限的腾讯云SecretId/SecretKey，在原 `backend/src/main/resources/application.yml` 中填写：

| YAML字段 | 填写方式 |
| --- | --- |
| lms.ocr.enabled | true |
| lms.ocr.provider | tencent |
| lms.ocr.secret-id | 本机SecretId |
| lms.ocr.secret-key | 本机SecretKey |
| lms.task.worker.enabled | 准备处理任务时设true |

真实摘要/卡片生成还需配置DeepSeek并选择agent/model模式。修改后重启后端。密钥只在本机后端填写，不放前端、不上传GitHub。完整说明见[YAML配置](YAML本机配置.md)。

## 联调范围

使用自制或有使用权的清晰图片、模糊图片和扫描PDF页进行联调，检查文字、页码、质量提示与实际请求记录。调用可能消耗腾讯云额度或产生费用，费用不明确时系统不会补为0。

仓库`tests/fixtures/`已提供自编的`d14-ocr-clear.png`、`d14-ocr-blurred.png`和单页纯图片PDF`d14-ocr-scan.pdf`，参考文字在`d14-ocr-expected.txt`。这些是输入样本和人工写好的对照文字，不是OCR运行结果。扫描PDF已经检查没有文字层；可用于验证扫描页是否进入OCR。生成脚本`create_d14_samples.py`可指定本地中文字体重新生成，依赖Pillow、reportlab和pypdf。

图片和PDF页的原图数据会发送给腾讯云OCR；只发送当前用户已同意处理的资料。OCR原文还需人工核对，摘要和卡片生成此阶段仍为MOCK，不是完整智能体或真实大模型效果。

未提供凭证时，只能完成本地协议、异常和业务测试，不会据此标注真实厂商联调通过。配置好后可告知“已配置”，再由开发流程使用自制样本核验，密钥值无需提供。

接口参考：[通用印刷体识别](https://cloud.tencent.com/document/api/866/33526)、[签名方法v3](https://cloud.tencent.com/document/api/213/30654)。
