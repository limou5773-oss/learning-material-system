# DeepSeek本机接入配置

D15接入DeepSeek Chat Completions的JSON输出模式，以所选资料的文字生成摘要、标签及带引用的知识卡片。OCR仍由D14单独配置，模型不直接读取用户文件或执行数据库操作。默认处理器disabled，不会自行调用外部模型；mock继续提供本地模拟流程。

## 配置

准备自己的DeepSeek API密钥，不要将密钥发到聊天、截图或GitHub。在项目根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-model.ps1
```

脚本隐藏输入，将MODEL_API_KEY、MODEL_PROVIDER=deepseek、MODEL_NAME和TASK_PROCESSOR_MODE=model保存在Windows当前用户的环境变量，凭证不打印、不写项目文件。这些环境变量持久存在于本机；在Windows账户环境变量设置中可修改或删除。之后重新打开后端终端。脚本不会自动启动worker。

如果只想临时配置当前PowerShell进程：

```powershell
. .\scripts\configure-model.ps1 -Scope Process
# 在同一终端配置本机DB_URL、DB_USERNAME、DB_PASSWORD
$env:TASK_WORKER_ENABLED = 'true'
mvn -f backend/pom.xml spring-boot:run
```

| 环境变量 | 说明 |
| --- | --- |
| TASK_PROCESSOR_MODE | model启用真实生成；mock保留模拟；默认disabled |
| TASK_WORKER_ENABLED | true才领取排队任务，默认false |
| MODEL_PROVIDER | deepseek |
| MODEL_NAME | 默认deepseek-flash，可选deepseek-v4-pro；脚本支持-Model参数 |
| MODEL_API_KEY | 本机密钥，不能放入前端或小程序 |

不配置自定义API URL，固定调用DeepSeek官方HTTPS地址。用户输入不能替换端点、HTTP头或工具。截止2026年10月25日，官方文档当前模型标识为deepseek-flash和deepseek-v4-pro，后续名称变化以厂商文档为准；代码不将旧deepseek-chat作为默认值。

## 使用与核对

先用自编文字笔记创建整理任务并同意外部处理。后端向DeepSeek发送当前所选固定版本文字和整理要求，可能产生API费用；图片/扫描PDF先从启用的OCR得到文字，OCR图片外发与模型文字外发是两次不同处理。

结果经结构与来源校验进入待确认状态。页面显示真实模型生成及模型信息，仍需核对摘要、卡片事实与原文引用，再确认保存。模型生成错误或引用不存在时会被拒绝，最多两次请求尝试包含一次重试或格式修复；不会回退为假成功。精确原文引用存在也不能证明整张卡片正确。

请求记录保留模型标识、提示版本、请求ID、token数和耗时。缺少的token数与未知费用保持NULL；不能把token量直接当成人民币费用。取消后外部请求可能仍产生费用，迟到结果不写入正式业务数据。

真实联调需要本机存在密钥。若没有密钥，只能完成本地协议、测试桩及缺配置分支验证，不能将本地测试当作DeepSeek真实生成效果或论文实验结果。

官方参考：[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[JSON输出](https://api-docs.deepseek.com/guides/json_mode/)、[模型信息](https://api-docs.deepseek.com/quick_start/pricing/)。
