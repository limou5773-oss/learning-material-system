# 后台 / 管理系统的导航交互

## 一次点击，六种回应

用户点一下菜单，系统可以给出的六种层级的反馈。**按入口数量与功能重要性挑，不要全塞。**

| # | 名称 | 效果 | 适用 | 实现要点 |
|---|---|---|---|---|
| 01 | 滑动高亮块 | 选中态是一个色块，切换时滑到新位置，而不是硬切 | 主导航、标签页 | 见下方实现 |
| 02 | 就地展开子菜单 | 子项在父项下方展开，后面栏目一起往下让位；切到另一组时上一组收回 | 二级导航、分组 | 展开动画用 `grid-template-rows: 0fr → 1fr`，不要 `height: auto`；子项轻微错峰；**同一时间只保留一组展开**，否则侧栏会被越撑越长 |
| 03 | 顶部下落面板 | 一级导航改为顶部菜单，点击后整块面板从上方落下 | 一级项 ≤6、需要大预览区 | 面板宽度贴合内容，加图标强化层级；换项时外层面板保留、内层平滑切换，**不能闪白**；别只靠 hover——点击与键盘都要能打开；离开区域或 Esc 收起 |
| 04 | 底部悬浮停靠栏 | 低频功能收进底部一条常驻栏 | 移动端、工具类 | 只放 ≤5 个且不是主流程；按指针距离连续调整图标大小，相邻图标让位；名称显示在上方；**放大不能盖住相邻入口，也不能挡住正文操作按钮**；移动端保留稳定的点击尺寸 |
| 05 | 命令面板 | `Ctrl/Cmd + K` 搜索所有入口 | 入口 >15、层级深 | 支持拼音/首字母模糊匹配；列表随输入实时缩小，上下键移动选中，**回车确认后才跳转**；关闭后焦点回到原处。中文输入法的回车是选字，**不能在输入法组词期间触发执行** |
| 06 | 状态点和计数徽标 | 未读/待办用小圆点或数字挂在菜单项右侧 | 有真实待办数时 | 数字 >99 显示 `99+`；数量更新时数字滚到新值；**必须来自真实接口**，不能写死；确认已读后才清除，**不能鼠标一经过就消失**；清除时缩小退出，不改变入口位置 |

## 01 滑动高亮块的实现（Web）

原理：在导航容器里放一个绝对定位的色块，位置由**选中项的实际几何**驱动。

```ts
// 选中项变化时重算一次；展开/折叠、窗口 resize 后延迟再算一次（等过渡走完）
function syncSlider() {
  const nav = navigation.value
  const active = nav?.querySelector('.is-selected') as HTMLElement | null
  if (!nav || !active) return
  const navRect = nav.getBoundingClientRect()
  const rect = active.getBoundingClientRect()
  slider.value = {
    top: `${rect.top - navRect.top + nav.scrollTop}px`,
    left: `${rect.left - navRect.left + nav.scrollLeft}px`,
    width: `${rect.width}px`,
    height: `${rect.height}px`,
    visible: true,
  }
}
```

```css
.nav { position: relative; }                     /* 滑块的定位基准 */
.nav-slider {
  position: absolute; z-index: 0; border-radius: 14px;
  background: var(--brand-soft); pointer-events: none;
  transition: top var(--motion-feedback) var(--motion-ease),
              left var(--motion-feedback) var(--motion-ease),
              width var(--motion-feedback) var(--motion-ease),
              height var(--motion-feedback) var(--motion-ease),
              opacity var(--motion-press) var(--motion-ease);
}
/* 菜单项抬到滑块之上 */
.nav > .group, .nav > .link { position: relative; z-index: 1; }
/* 选中项自身背景交给滑块绘制，避免双重上色 */
.nav .link.is-selected { background: transparent; }
@media (prefers-reduced-motion: reduce) { .nav-slider { transition: none; } }
```

四个必须注意的点：

1. **加 `nav.scrollTop` / `scrollLeft` 修正**，否则导航内部滚动后位置会错。
2. **`position: relative` 只加在导航的直接子级**，别加在深层容器上，否则 `offsetTop` 基准会跑偏（所以用 `getBoundingClientRect` 差值而非 `offsetTop`）。
3. **选中项背景要置空**，否则滑块滑动时目标项已经亮着，滑动感消失。
4. **占位元素加 `aria-hidden="true"`**，它是纯装饰，不要进无障碍树。

两个容易被忽略的行为：

- **宽度跟随文字长度**，滑块要比普通背景更像"一块会变的底板"；宽度变化与位移同一时长。
- **快速连续切换时从当前位置接着走**，不要重置回起点——这要求每次都读当前 `getBoundingClientRect` 而不是从上一次的值累加。
- 落下时可以带**轻微回弹**（`--motion-spring`），但位移本身不能超过 2-4px 的过冲，否则会像卡通。
- 只动**底色**，别让整列文字跟着晃——文字必须保持稳定。

## 02 折叠展开的正确写法

```css
.branch { display: grid; grid-template-rows: 0fr; transition: grid-template-rows var(--motion-feedback) var(--motion-ease); }
.branch.is-open { grid-template-rows: 1fr; }
.branch > * { overflow: hidden; min-height: 0; }
```

`height: auto` 无法参与过渡，`max-height` 会有"到顶就跳"的顿感，`grid-template-rows` 是当前唯一干净的解。

## 06 计数徽标的落地条件

- 数字必须来自真实接口；没有数据源时**不要**先写死一个 `3` 当占位。
- 加载期间不显示（不要显示 `0`），失败时不显示（不要显示 `!`）。
- 徽标和状态点不要同时用于同一项。

## 左边缘对齐（这条容易被忽略）

侧栏分组标题、菜单图标、菜单文字、子项竖线，四者的左边缘要能对齐成一条视觉轴。落地检查：

- 分组标题 `padding-left` = 菜单项 `padding-left`
- 子级竖线 `left` = 父项图标中心
- 图标与文字间距全站统一为一个值（8/12px 二选一，不要混用）
