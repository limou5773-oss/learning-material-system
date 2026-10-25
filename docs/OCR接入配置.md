# 腾讯云OCR本机配置

本次D14使用腾讯云文字识别的**通用印刷体识别 GeneralBasicOCR**，API版本2018-11-19，中英文混合。暂不接表格行列结构、英语专项或阿里云适配器。厂商调用与业务通过OcrClient隔离，之后可以另加适配器。

## 开通与密钥

在腾讯云控制台开通文字识别服务，准备可调用OCR的SecretId和SecretKey。不要把密钥发到聊天、截图或GitHub。当前代码只从后端进程环境读取凭证，默认OCR_ENABLED=false，不会自行发起OCR请求。

Windows在项目根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-ocr.ps1
```

脚本会隐藏输入，只将OCR_SECRET_ID、OCR_SECRET_KEY、OCR_PROVIDER=tencent及OCR_ENABLED=true写到**Windows当前用户的环境变量**；不写项目文件，不打印密钥。当前用户的环境变量会持久保存在本机，之后可在Windows“编辑账户的环境变量”中修改或删除。新启动的后端终端会读取这些配置，原有终端需要重新打开。

若只希望在当前PowerShell进程中临时配置，可使用：

```powershell
. .\scripts\configure-ocr.ps1 -Scope Process
# 在同一个终端中设置本机DB_URL、DB_USERNAME、DB_PASSWORD后启动后端
$env:TASK_WORKER_ENABLED = 'true'
$env:TASK_PROCESSOR_MODE = 'mock'
mvn -f backend/pom.xml spring-boot:run
```

不使用Windows脚本时，直接在后端的运行环境配置以下四个变量即可：

| 变量 | 含义 |
| --- | --- |
| OCR_ENABLED | true才开启OCR；false保持D13行为 |
| OCR_PROVIDER | tencent |
| OCR_SECRET_ID | 本机SecretId |
| OCR_SECRET_KEY | 本机SecretKey |

## 联调范围

使用自制或有使用权的清晰图片、模糊图片和扫描PDF页进行联调，检查文字、页码、质量提示与实际请求记录。调用可能消耗腾讯云额度或产生费用，费用不明确时系统不会补为0。

仓库`tests/fixtures/`已提供自编的`d14-ocr-clear.png`、`d14-ocr-blurred.png`和单页纯图片PDF`d14-ocr-scan.pdf`，参考文字在`d14-ocr-expected.txt`。这些是输入样本和人工写好的对照文字，不是OCR运行结果。扫描PDF已经检查没有文字层；可用于验证扫描页是否进入OCR。生成脚本`create_d14_samples.py`可指定本地中文字体重新生成，依赖Pillow、reportlab和pypdf。

图片和PDF页的原图数据会发送给腾讯云OCR；只发送当前用户已同意处理的资料。OCR原文还需人工核对，摘要和卡片生成此阶段仍为MOCK，不是完整智能体或真实大模型效果。

未提供凭证时，只能完成本地协议、异常和业务测试，不会据此标注真实厂商联调通过。配置好后可告知“已配置”，再由开发流程使用自制样本核验，密钥值无需提供。

接口参考：[通用印刷体识别](https://cloud.tencent.com/document/api/866/33526)、[签名方法v3](https://cloud.tencent.com/document/api/213/30654)。
