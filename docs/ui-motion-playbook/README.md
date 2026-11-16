# ui-motion-playbook

一套 UI 动效与交互设计规范，写成 AI Agent 可直接加载的 skill。

来源：6 个动效教学视频（逐条语音转写 + 抽帧核对）+ Carbon Design System 官方规范 + 实测参数。

## 目录

```
ui-motion-playbook/
├── SKILL.md                     入口：六步工作流、触发词
├── references/
│   ├── effects-catalog.md       16 种动效 + 6 种遮罩层 + 5 种加载形态
│   ├── motion-spec.md           来源与置信度、时长/缓动表、四条按钮提示词
│   ├── ai-prompting.md          「跟 AI 说」句式库、反模式对照
│   ├── review-checklist.md      视觉规则原文、去 AI 味四条、三层自检
│   ├── admin-navigation.md      后台菜单六种回应 + 滑动高亮块实现
│   └── transcripts.md           6 份完整转写稿（未删改，带时间戳）
├── assets/                      4 张原始截图（视觉规则卡、按钮提示词卡、菜单总览）
└── adapters/                    其他 agent 的适配文件
    ├── README.md                各工具安装路径
    ├── AGENTS.md                通用（Codex / Gemini / 多数 CLI）
    ├── .cursor/rules/           Cursor
    ├── .clinerules/             Cline / Roo Code
    └── codex/prompts/           Codex 斜杠命令
```

## 安装

### WorkBuddy / 支持 Agent Skills 的工具

```bash
cp -r ui-motion-playbook ~/.workbuddy/skills/     # WorkBuddy，个人级
cp -r ui-motion-playbook ~/.claude/skills/        # Claude Code，个人级
# 或放项目内：<项目>/.claude/skills/
```

调用：`/ui-motion-playbook`，或说「给这个页面加点动效」时按 description 自动触发。

### Codex

```bash
mkdir -p ~/.codex/prompts
cp adapters/codex/prompts/ui-motion.md ~/.codex/prompts/
# 重启 Codex，输入 /ui-motion
```

项目常驻规则：把 `adapters/codex/AGENTS.md.snippet` 追加到项目根 `AGENTS.md`。

### Cursor / Cline

```bash
cp adapters/.cursor/rules/ui-motion.mdc <项目>/.cursor/rules/
cp adapters/.clinerules/ui-motion.md    <项目>/.clinerules/
```

详见 `adapters/README.md`。

## 核心主张

**动效不是装饰，是状态变化的可视化。** 加之前先过「动效三问」：

1. 是否表达了一次状态变化？
2. 是否缩短了等待的感知？
3. 是否提供了空间定位？

三条全否 → 不加。这一问能筛掉大部分「AI 味」。

## 三条硬指标

| 项 | 值 |
|---|---|
| 微交互 / hover / 入场 / 弹窗 | 90-140 / 150-200 / 200-300 / 200-260 ms |
| 只动 | `transform`、`opacity`、`filter`（动高度用 `grid-template-rows`） |
| 必须支持 | `prefers-reduced-motion: reduce` |

## 来源与置信度

| 来源 | 内容 | 置信度 |
|---|---|---|
| 6 条视频（转写 + 抽帧核对） | 效果清单、判据、口诀、四条按钮提示词原文 | 高（原话） |
| Carbon Design System — Motion | 时长令牌 70/110/150/240/400/700ms、三条缓动曲线 | 高（官方规范） |
| 标注 `≈` 的区间 | 骨架屏周期、跑马灯速度、加载条周期 | 低（经验值，可调） |

所有数字在 `references/motion-spec.md` 的「来源与置信度」表中可反查。
