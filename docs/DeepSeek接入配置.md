# DeepSeek本机接入配置

直接修改原YAML。旧环境配置脚本已移除，功能边界和真实厂商验收要求如下。

D15接入DeepSeek Chat Completions的JSON输出模式，以所选资料的文字生成摘要、标签及带引用的知识卡片。OCR仍由D14单独配置，模型不直接读取用户文件或执行数据库操作。默认处理器disabled，不会自行调用外部模型；mock继续提供本地模拟流程。

## 配置

直接编辑原 `backend/src/main/resources/application.yml`，无需设置环境变量或运行旧配置脚本：

| YAML字段 | 填写方式 |
| --- | --- |
| lms.model.provider | deepseek |
| lms.model.name | deepseek-flash |
| lms.model.api-key | 本机API Key，仅后端使用 |
| lms.task.processor.mode | agent启用智能体；model使用固定生成；mock用于模拟 |
| lms.task.worker.enabled | 准备处理任务时设true |

修改后重启后端，详见[YAML配置](YAML本机配置.md)。API地址由适配器固定使用官方HTTPS端点，不接收用户自定义URL。GitHub中的密码/密钥为空占位，本机已填写的配置单独保留。

## 使用与核对

先用自编文字笔记创建整理任务并同意外部处理。后端向DeepSeek发送当前所选固定版本文字和整理要求，可能产生API费用；图片/扫描PDF先从启用的OCR得到文字，OCR图片外发与模型文字外发是两次不同处理。

结果经结构与来源校验进入待确认状态。页面显示真实模型生成及模型信息，仍需核对摘要、卡片事实与原文引用，再确认保存。D15的model模式最多两次生成请求尝试，包含一次重试或格式修复；不会回退为假成功。精确原文引用存在也不能证明整张卡片正确。

请求记录保留模型标识、提示版本、请求ID、token数和耗时。缺少的token数与未知费用保持NULL；不能把token量直接当成人民币费用。取消后外部请求可能仍产生费用，迟到结果不写入正式业务数据。

真实联调需要本机存在密钥。若没有密钥，只能完成本地协议、测试桩及缺配置分支验证，不能将本地测试当作DeepSeek真实生成效果或论文实验结果。

官方参考：[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[JSON输出](https://api-docs.deepseek.com/guides/json_mode/)、[模型信息](https://api-docs.deepseek.com/quick_start/pricing/)。

## D16：受限智能体模式

仍使用同一DeepSeek密钥，将原YAML的 `lms.task.processor.mode` 设为 `agent` 并开启worker。model模式保留固定提取/生成流程；agent模式按工具反馈继续规划。不能把模拟响应或缺配置分支当成真实效果。

每任务最多8步（包括规划）、4次规划请求、8次模型请求（规划与生成合计且跨epoch累计），生成部分仍最多2次。规划会额外调用模型，可能产生相应用量和费用；记录purpose区分规划与生成，未知费用仍为NULL。

选择“允许检索本人已有卡片”后，智能体可按关键词检索本人可访问卡片，将有限片段发送模型作为关联参考；不勾选则不检索、不发送这些额外片段。关联建议仅能指向本次召回的已有卡片，版本由服务端核对，必须在确认页逐条勾选才保存。当前为关键词检索与关联建议，关系图展示仍在后续D19。

工具仅包括当前资料的提取、OCR、本人卡片检索、候选生成和校验，不能执行任意SQL、命令、外部URL或删除正式资料。步骤说明记录可观察动作，不展示或记录模型隐藏思维。OCR没有有效文字时可明确停止，预算达限也会结束，不保证每项资料都能生成候选。D14/D15/D16真实厂商验收仍待配置凭证。
