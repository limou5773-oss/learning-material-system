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

结果经结构与来源校验进入待确认状态。页面显示真实模型生成及模型信息，仍需核对摘要、卡片事实与原文引用，再确认保存。D15的model模式最多两次生成请求尝试，包含一次重试或格式修复；不会回退为假成功。精确原文引用存在也不能证明整张卡片正确。

请求记录保留模型标识、提示版本、请求ID、token数和耗时。缺少的token数与未知费用保持NULL；不能把token量直接当成人民币费用。取消后外部请求可能仍产生费用，迟到结果不写入正式业务数据。

真实联调需要本机存在密钥。若没有密钥，只能完成本地协议、测试桩及缺配置分支验证，不能将本地测试当作DeepSeek真实生成效果或论文实验结果。

官方参考：[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[JSON输出](https://api-docs.deepseek.com/guides/json_mode/)、[模型信息](https://api-docs.deepseek.com/quick_start/pricing/)。

## D16：受限智能体模式

仍使用同一DeepSeek密钥，配置时指定agent即可：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-model.ps1 -ProcessorMode agent
```

脚本仍只保存配置，不自动开启worker。也可在后端终端显式设置`TASK_PROCESSOR_MODE=agent`，并在准备处理已同意任务时设置`TASK_WORKER_ENABLED=true`。D15的model模式保留固定提取/生成流程；agent模式让模型在白名单内提出下一批工具动作，读取工具反馈后再规划。不得把缺少密钥时的错误分支或本地桩测试当作真实智能体效果。

每任务最多8步（包括规划）、4次规划请求、8次模型请求（规划与生成合计且跨epoch累计），生成部分仍最多2次。规划会额外调用模型，可能产生相应用量和费用；记录purpose区分规划与生成，未知费用仍为NULL。

选择“允许检索本人已有卡片”后，智能体可按关键词检索本人可访问卡片，将有限片段发送模型作为关联参考；不勾选则不检索、不发送这些额外片段。关联建议仅能指向本次召回的已有卡片，版本由服务端核对，必须在确认页逐条勾选才保存。当前为关键词检索与关联建议，关系图展示仍在后续D19。

工具仅包括当前资料的提取、OCR、本人卡片检索、候选生成和校验，不能执行任意SQL、命令、外部URL或删除正式资料。步骤说明记录可观察动作，不展示或记录模型隐藏思维。OCR没有有效文字时可明确停止，预算达限也会结束，不保证每项资料都能生成候选。D14/D15/D16真实厂商验收仍待配置凭证。
