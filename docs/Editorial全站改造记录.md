# Editorial 全站改造记录

日期：2026-10-31　范围：`web/src/style.css`（新增全局层）+ `LoginView.vue` / `RegisterView.vue`（结构同构化）
验证：`304/304` 前端测试通过、`vue-tsc` 0 错误、`vite build` 成功、8 个页面实机截图核对

---

## 一、这一轮解决什么

登录页确立的 Editorial 方向，此前只存在于一个页面的 `scoped` 样式里。这轮做两件事：

1. **收尾**：修掉上轮列的 3 条遗留（窄屏页头溢出、冗余入口、按钮字距动画）。
2. **铺开**：把「衬线展示标题 + 方角单据 + 规则线」这套排印语法提升为全局层，让工作台与管理端不再是另一个世界。

原则保持不变：**只改表现层**，不动结构、类名、`aria-*`、表单绑定与接口调用。因此 304 条断言 DOM 的测试一条没改就全绿。

---

## 二、三项遗留的修复

| # | 问题 | 修法 | 验证 |
|---|---|---|---|
| 1 | 390px 下 `创建账号` 被裁出屏幕 | 620 断点内收小品牌字号 15→13px、图标 34→30px、注册按钮内边距 11/20→7/12、header gap 26→12 | 390px 实拍完整可点 |
| 2 | 登录页页头还挂着「登录」入口 | `.header-links a.router-link-exact-active { display: none }`，用 Vue Router 自带的 exact-active 类隐藏当前页入口；`has(a:only-child)` 顺手收起多余 gap | 登录页只剩「创建账号」，注册页只剩「登录」 |
| 3 | 按钮 hover 变字距引起亚像素重排 | 去掉 `letter-spacing` 过渡，改 `background` + `box-shadow` + `translateY(-1px)`，并补 `:active` 回弹 | 只动 `transform` / `opacity` 类属性 |

第 2 项用 CSS 而非 `v-if`，是因为 `App.vue` 的页头在多条测试路径里被渲染，改结构有风险；CSS 隐藏不改变 DOM，测试不受影响。

---

## 三、全局 Editorial 层（`style.css` 末尾）

### 1. 排印

```css
h1 {
  font-family: var(--serif-display);   /* 思源宋体 → Songti SC → SimSun */
  font-weight: 500;
  letter-spacing: .025em;              /* 中文取正字距，负值会挤笔画 */
  color: #1b2a3f;                      /* 暖近黑，不用纯黑 */
}
```

全局 `h1` 一次性覆盖了三类此前各写各的标题：`.section-heading h1`（列表页）、`.detail-panel h1`（详情页 30px）、`.confirmation-layout h1`（确认页）。这是本轮最省力也最见效的一条——之前详情页标题还是无衬线粗体，和列表页的衬线标题并列时会明显「两个世界」。

### 2. 页头规则线

`.section-heading` 加 `padding-bottom: 16px` + `border-bottom: 1px`，让每个页面的标题区都压一条通栏细线，形成刊物页眉的收口。原来标题与内容之间只有空气，现在有了结构。

### 3. 方角化

| 令牌 | 原值 | 新值 | 影响面 |
|---|---|---|---|
| `--r-lg` | 14px | 6px | `.materials-panel`、`.el-card`、对话框 |
| `--r-md` | 10px | 6px | 输入框、按钮、标签容器 |
| `--r-sm` | 8px | 4px | 小徽标 |
| `--el-border-radius-base` | 10px | 6px | Element Plus 全组件（按钮 / 输入 / 选择器 / 分页 / 提示条） |

`--line-strong` 同步调到 `#dfe6f0`，边框更「硬」。列表卡片悬停从「上浮 + 阴影」改为「描边变蓝 + `box-shadow: 0 0 0 1px`」——纸不会浮起来。

### 4. 认证页共用层

登录页那 239 行 scoped 样式提升为全局 `.editorial-auth`，`:deep()` 展开为普通后代选择器，无前缀类（`.masthead` / `.kicker` / `.rule-row` / `.index-points` / `.card-head` / `.card-issue`）统一加 `.editorial-auth` 前缀避免污染全站。

`RegisterView` 改造成与登录页同构：页眉 `No.02 — 2026`、`CREATE ACCOUNT` 色标、编号索引（随手记录 / 集中整理 / 关联知识）、同一套底线式输入与方角按钮。两页现在读起来像「同一本刊物的第 1 页与第 2 页」。

### 5. 列表卡片的密度修正

卡片被 grid 拉到等高后，内容少的卡会在摘要与标签之间拉开一段空白。处理：

```css
.document-grid .material-item-meta { flex-wrap: nowrap; overflow: hidden; }
.document-grid .material-item-meta .el-tag:nth-of-type(n+5) { display: none; }  /* 只留 3 个标签 */
```

第一版只做了单行，结果标签多的卡把「更新时间」挤出可视区（时间被裁成 `202`）——截图发现后补了标签数量限制。现在每张卡的元信息都是「学科 + 最多 3 个标签 + 右对齐时间」，高度差消失。

---

## 四、逐页核对结果

| 页面 | 变化 | 结论 |
|---|---|---|
| 登录页 | 样式移入全局层，3 条遗留已修 | 正常 |
| 注册页 | 结构同构化，4 字段表单卡比左栏高约 100px | 正常（内容决定，未强行拉平） |
| 资料列表 | 标题 26→30px 衬线、页头线、方角卡、元信息单行 | 正常 |
| 资料详情 | 标题由无衬线粗体变衬线，与列表页统一 | 正常 |
| 知识卡片 | 标题衬线、卡片方角、`版本 n` 弱化 | 正常 |
| 整理任务 | 标题衬线、行分隔清晰 | 正常 |
| 消息通知 | 标题衬线、未读竖条、方角 | 正常 |
| 个人中心 | 标题衬线、概览卡方角、右栏 288px | 正常 |
| 管理端 ×4 | 标题衬线、表格方角外框、筛选控件统一 | 正常（统计页环形图图例仍偏挤，见下） |

---

## 五、仍然存在的问题

1. **统计页环形图图例重叠**：图例由 canvas 内部绘制，在 190px 宽的卡片里与圆环压在一起。要修得改绘制逻辑（图例移到图下方或改用 HTML 图例），不在本轮样式层范围内。
2. **统计页首行三卡宽度目测不齐**：`repeat(3, minmax(0,1fr))` 理论上等宽，实拍第一卡偏窄。需要单独抓 DOM 尺寸确认是布局问题还是截图误读。
3. **注册页左右栏高度差**：表单 4 个字段必然比左侧文案长，未做「补内容填平」的强行平衡。
4. **宋体在小字号下偏细**：全局只有 `h1` 用衬线，`h2` 及以下仍是无衬线，这是刻意的「一扬一抑」。若后续把 `h2` 也衬线化，需要同步调字号与字重，否则会糊。

---

## 六、回退方式

- 全站层：`style.css` 末尾三段（`Editorial 全站层` / `全站一级标题` / `列表卡片元信息`）整段删除即回到上一版。
- 认证页：删除 `.editorial-auth` 段，`LoginView.vue` / `RegisterView.vue` 去掉 `editorial-auth` 类名，并把 `masthead / kicker / rule-row / index-points / card-head` 结构回退。
- 单页回退不影响其他页：全局层只作用于既有类名，没有新增选择器依赖。
