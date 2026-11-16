# 装到其他 Agent 里

同一份 skill，三种装法。**格式是开放标准**（https://skill.md/），所以支持 Agent Skills 的工具都能直接吃。

## 1. Claude Code / 其他支持 Agent Skills 的工具（推荐，零改造）

```bash
# 个人级：所有项目可用
cp -r "C:/Users/24100/.workbuddy/skills/ui-motion-playbook" ~/.claude/skills/

# 或项目级：跟仓库走，团队共享（建议提交）
mkdir -p <项目>/.claude/skills
cp -r "C:/Users/24100/.workbuddy/skills/ui-motion-playbook" <项目>/.claude/skills/
```

调用：输入 `/ui-motion-playbook`，或说「给这个页面加点动效」时按 description 自动触发。

> 目录名（或 frontmatter 里的 `name`）就是命令名。`description` 决定自动加载时机。

## 2. Cursor

```bash
cp -r 多agent适配/.cursor/rules/ui-motion.mdc <项目>/.cursor/rules/
```

在 Cursor 里对 AI 说「按 ui-motion 规则做」，或用 `@ui-motion.mdc` 引用。

## 3. Cline / Roo Code

```bash
cp 多agent适配/.clinerules/ui-motion.md <项目>/.clinerules/
```

`.clinerules` 目录里的文件会自动进上下文。

## 4. Codex（OpenAI Codex CLI）

Codex 不读 `SKILL.md`，它用 **`AGENTS.md`**（常驻记忆）+ **`~/.codex/prompts/`**（斜杠命令）。

```powershell
# ① 斜杠命令（推荐，按需召唤，不占常驻上下文）
mkdir -p ~/.codex/prompts
cp 多agent适配/codex/prompts/ui-motion.md ~/.codex/prompts/
# 重启 Codex，之后输入 /ui-motion 即可

# ② 项目级指针（让 Codex 平时就知道有这套规则）
cat 多agent适配/codex/AGENTS.md.snippet >> <项目>/AGENTS.md
# Windows PowerShell: Get-Content ...snippet | Add-Content <项目>/AGENTS.md
```

Codex 的加载优先级（就近覆盖）：

| 层级 | 路径 |
|---|---|
| 个人全局 | `~/.codex/AGENTS.md` |
| 项目 | `<repo>/AGENTS.md` |
| 子目录 | `<repo>/src/AGENTS.md` … 就近优先 |
| 斜杠命令 | `~/.codex/prompts/<name>.md` → 输入 `/<name>` |

**Codex 的三个限制要注意**：

1. 自定义命令**不支持参数**，所以 `ui-motion.md` 写成了自包含 + 先问用户要做什么
2. **新建命令后必须重启** Codex 才生效
3. 全局 `AGENTS.md` 会作用于**所有项目**，动效规则只在做 UI 时有意义 —— 所以建议：**项目根放 3 行指针，完整规则走 `/ui-motion`**

## 5. Gemini CLI / 其他读 AGENTS.md 的工具

```bash
cp 多agent适配/AGENTS.md <项目>/AGENTS.md
# 已有则追加
```

## 6. 都不吃的话（最差情况）

把 `多agent适配/AGENTS.md` 的内容直接粘到对话开头当 system 提示。规则是自包含的，不依赖文件也能用。

---

## 适配文件和原始 skill 的关系

适配文件是**浓缩版**：只保留判断流程和硬规则（约 2KB），因为 rules 类机制通常一次性全量进上下文，塞完整 skill 会吃光预算。

需要细节时，它们指向原始 references 的绝对路径：

```
C:/Users/24100/.workbuddy/skills/ui-motion-playbook/references/
```

**换机器或换用户，这个路径会变**——到时把适配文件里的路径改掉，或者干脆把整个 `ui-motion-playbook` 文件夹一起拷进项目（推荐，路径改成相对路径 `./ui-motion-playbook/references/`）。
