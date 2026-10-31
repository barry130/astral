# UI 组件指南（astral-front）

> **写任何界面前先读这一页。** 本仓库的 UI 已经收敛成「一套基元 + 一套业务组件」，
> 新增页面时**优先复用这里的组件**，不要在每个页面里临时手写等价实现。

适用范围：`astral-front/src/**`（Next.js 16 + Tailwind v4 + shadcn/ui）。
后端无关，改动不涉及 Java 侧。

---

## 1. 三层结构（先判断你要的东西属于哪一层）

| 层 | 位置 | 作用 | 什么时候用 |
| --- | --- | --- | --- |
| **设计令牌** | `astral-front/src/app/globals.css` | 颜色 / 圆角 / 阴影 / 间距的唯一定义 | 只在改整体风格时动；页面里一律用 `var(--color-*)` 或语义类，**不写死色值** |
| **基元层** | `astral-front/src/components/ui/*` | shadcn 原子组件（button/input/card/dialog…） | 极少数需要完全自定义外观的场合；业务页基本不直接引用这一层 |
| **兼容层** | `astral-front/src/components/antd-compat/*` | 用 shadcn 复刻的 antd 同名 API | **存量页面**在用（`import { Button, Table, Modal } from '@/components/antd-compat'`）；新代码按 AGENTS.md §4 优先写 `ui/*` + Tailwind |
| **业务组件层** | `astral-front/src/components/*.tsx` | 跨页面复用的组合件（页面头部 / KPI 卡 / 筛选条 / 空态…） | **所有页面**都直接用这一层 |

> 判断规则：**同一段 JSX 出现第二次，就必须上提到 `components/` 并登记到本文档**。

---

## 2. 设计令牌速查（`globals.css`）

### 2.1 品牌与语义色

| 变量 | 浅色 | 用途 |
| --- | --- | --- |
| `--color-brand` | `#0b8f62` | 主色（按钮、选中态、图表主色） |
| `--color-brand-hover` | `#0a7a53` | 主色 hover |
| `--color-brand-soft` | `#e7f7ef` | 主色浅底（图标芯片、选中底色） |
| `--color-brand-text` | `#0b8f62` | 需要品牌色的**文字**（不要用 `--color-brand` 直接染字） |
| `--color-success` | `#10a06a` | 成功 |
| `--color-bg-base` | `#f2f7f4` | 页面底色（薄荷青灰） |
| `--color-bg-tint` | `#f7fbf9` | 卡内浅底分区 |
| `--color-border` | `#e5ede9` | 常规描边 |
| `--color-border-light` | `#eff4f1` | 分隔线（表头、卡片内部分区） |
| `--color-text` | `#14201c` | 主文字 |
| `--color-text-secondary` | `#4d5f58` | 次要文字 |
| `--color-text-tertiary` | `#6b7a75` | 说明文字、表头 |

shadcn 语义变量（`--background` / `--foreground` / `--primary` / `--muted` / `--border` / `--ring` …）与之同步，
所以 `bg-card`、`text-muted-foreground`、`border-border` 这些类可以直接用。

### 2.2 圆角与阴影

- 圆角：`--radius-sm 8px` / `--radius-md 12px` / `--radius-lg 18px` / `--radius-xl 22px`；组件内多用 `rounded-xl`（10–12px）与 `rounded-2xl`（卡片）。
- 阴影一律极柔、偏绿黑：卡片 `shadow-[0_1px_2px_rgba(16,32,26,0.03),0_10px_30px_-18px_rgba(16,32,26,0.18)]`，浮层 `shadow-[0_18px_44px_-16px_rgba(16,32,26,0.28)]`。
- **不要新增色值**：需要新色先改进令牌，再改进组件。

### 2.3 深色模式

`[data-theme='dark']` 下全部令牌都有对应值；写样式时只用令牌，深色自动生效。
主题键：`astral:theme`（`astral-front/src/app/providers.tsx`）。

---

## 3. 业务组件清单（`astral-front/src/components/`）

### 3.1 页面骨架

| 组件 | 文件 | 说明 |
| --- | --- | --- |
| `PageHeader` | `page-header.tsx` | 页面标题栏。`title` 必填，`description`/`actions`/`eyebrow`/`children` 可选。`actions` 放右上按钮组 |
| `FilterBar` | `filter-bar.tsx` | 列表页筛选条。`children` = 筛选项；`actions` = 右侧按钮；`split` 用于「左右两组内容」 |
| `PageToolbar` | `filter-bar.tsx` | 右对齐动作行（单独一行放按钮时用） |
| `SectionCard` | `section-card.tsx` | **带标题的区块卡片唯一实现**（标题 + 描述 + 右上 `actions` + `contentClassName`）。页面里不要再手写 `<Card><CardHeader><CardTitle>…`；只有「标题行内还要塞统计数字/切换等自定义内容」时才退回 `Card`。标题带图标时写成 `title={<span className="flex items-center gap-2"><Icon className="size-4" />标题</span>}`（直接写 Fragment 会让 svg 独占一行） |
| `DataTable` | `data-table/DataTable.tsx` | 标准数据表（列定义 + 分页 + 移动端自动切卡片列表） |
| `TablePagination` | `data-table/TablePagination.tsx` | 标准分页器（每页条数 + 跳页），不经过 `DataTable` 时直接引入 |
| `ResizableTable` | `ResizableTable.tsx` | 列宽可调 + 列设置的表格（后台列表页主力） |
| `TableCardList` | `TableCardList.tsx` | 移动端卡片式列表（`DataTable` 内部自动调用，页面一般不直接用） |

### 3.2 指标与数据展示

| 组件 | 文件 | 说明 |
| --- | --- | --- |
| `StatCard` | `stat-card.tsx` | **KPI 卡唯一实现**。`label` / `value` / `icon` / `tone` / `progress` / `sub` / `spark` / `size`（`default` 大卡、`compact` 小卡） |
| `StatTile` | `stat-card.tsx` | 卡内浅底数值块（小标题 + 数值 + 备注） |
| `RatioBar` | `stat-card.tsx` | 细比例条。`percent` + `tone` 或 `color` |
| `TrendBadge` | `trend-badge.tsx` | 环比涨跌标签。`current` / `previous` / `invert`（越大越差时反转配色） |
| `Sparkline` | `sparkline.tsx` | 迷你走势线（内联 SVG，采样 <2 时画虚线占位） |
| `InfoList` / `InfoRow` | `info-list.tsx` | 描述列表。`InfoRow` 两种形态：`between`（详情卡，值右对齐）/ `inline`（个人资料，标签定宽） |
| `EmptyState` | `EmptyState.tsx` | 空状态（薄荷圆底图标 + 标题 + 说明 + 可选操作） |
| `ErrorState` | `ErrorState.tsx` | 加载失败态 |
| `TableSkeleton` | `TableSkeleton.tsx` | 表格骨架屏。`DataTable` / `ResizableTable` 首次加载（无数据）时已自动渲染，页面无需手动调用 |
| `markdown-editor` | `markdown-editor.tsx` | Markdown 编辑器（CMS 使用） |

### 3.3 全局与壳层

| 组件 | 文件 | 说明 |
| --- | --- | --- |
| `GlobalRequestLoading` | `GlobalRequestLoading.tsx` | 全局请求进度条 |
| `NoticeBell` | `NoticeBell.tsx` | 顶栏通知铃铛 |
| `NoticeManagement` | `admin/NoticeManagement.tsx` | 通知管理面板 |
| AI 相关 | `ai/*` | `ChatPanel`、`useAiChat`、`sse`、`AnswerBlocks`、`RichText` |
| 存储相关 | `storage/FileManager.tsx` | 文件管理器 |

### 3.4 色调约定

`StatCard` 的 `tone` 只有四档：`normal` / `success` / `warn` / `danger`。
配套工具函数（同在 `stat-card.tsx`）：

- `usageTone(value, warn, danger)`：资源使用率，越线才着色。
- `ratioTone(value, good, warn)`：成功率类，越高越好。
- `STAT_TONE_TEXT` / `STAT_TONE_BAR`：需要自己拼 className 时取色用。

**数值颜色只表达状态**，不要拿 `tone` 当主题色用。

---

## 4. 兼容层（`@/components/antd-compat`）导出清单

> **定位**：这是「让存量 antd 页面零改动继续跑」的过渡层，不是新代码的方向。
> 新增页面按 AGENTS.md §4 应优先直接写 `components/ui/*` + Tailwind；
> 业务组件层（第 3 节）和存量页面继续从兼容层引入即可。
> 无论哪种，**禁止** `import … from 'antd'`（`package.json` 中已无 antd）。
> 图标一律从 `@/components/antd-compat/icons` 引入。

- **基础**：`Button`
- **布局**：`Card` `Space` `Row` `Col` `Divider` `Typography`
- **反馈**：`message` `notification` `Spin` `Empty` `Alert` `Result` `Tooltip` `Popconfirm` `Pagination`
- **表单**：`Form` `useForm` `useWatch` `createFormInstance`
- **输入**：`Input` `InputNumber` `Select` `Switch` `Checkbox` `Radio` `RadioButton` `RadioGroup` `AutoComplete` `DatePicker` `RangePicker` `TreeSelect` `Upload` `UploadDragger` `Image` `InputPassword` `InputTextArea` `InputSearch` `InputGroup`
- **弹层**：`Modal` `Drawer` `Tabs`
- **展示**：`Tag` `Statistic` `Timeline` `Badge` `Descriptions` `Table`

> `Statistic` 仍保留用于兼容旧页面，但**新的指标卡一律用 `StatCard`**。

### 4.1 与基元层的关系

`ui/` 下的原子组件（`button` `input` `card` `dialog` `table` `tabs` `badge` `progress` `select` `tooltip` …）
只应被 `antd-compat/*` 和本文档第 3 节的业务组件引用。
如果页面里需要某个基元而没有对应的兼容层包装，**先考虑加包装**，不要直接引 `ui/*` 破坏一致性
（现有少数历史直引，如 `astral-front/src/app/dashboard/layout.tsx`，属存量，不扩大）。

---

## 5. 复用规则（评审会看这几条）

1. **先查再写**：动手前先翻本文档第 3、4 节，确认没有现成组件。
2. **同段 JSX 出现第二次 → 上提**到 `components/`，并在本文档登记（文件、props、一句用法）。
3. **禁止行内样式改版式**：不写 `style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}`，
   用 `className`（`space-y-4`、`mb-4`、`flex`…）或组件 props。
4. **禁止在页面内定义 `StatCard` / `TrendBadge` / `InfoRow` / `FilterBar` 之类的同名组件**——名字撞车说明你该复用它。
5. **禁止写死色值**（`#52c41a`、`text-emerald-600` 之类）：走令牌或 `tone`。
   图表色用 `var(--color-brand)` 对应的十六进制（echarts 无法读 CSS 变量，统一写 `#0b8f62` / `#10a06a`）。
6. **页面级编排留在页面里**：`GridCell`、`OverviewItem` 这种只服务一个页面的小组件可以留在该页，
   但一旦第二个页面要用，立刻上提。
7. **新组件必须自带类型**：导出 `XxxProps` 接口，props 全可选时给默认值，不接收 `any`。

---

## 6. 页面模板（照抄即可）

```tsx
'use client';

import { FilterBar } from '@/components/filter-bar';
import { PageHeader } from '@/components/page-header';
import { StatCard } from '@/components/stat-card';
import { Button, Card, Input } from '@/components/antd-compat';
import { PlusOutlined, ReloadOutlined } from '@/components/antd-compat/icons';

export default function DemoPage() {
  return (
    <div className="space-y-4">
      <PageHeader title="示例列表" description="一句话说明这个页面干什么" />

      {/* 指标条（可选） */}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard size="compact" label="总数" value="128" />
        <StatCard size="compact" label="今日新增" value="7" tone="success" />
      </div>

      <Card>
        <FilterBar
          actions={
            <>
              <Button icon={<ReloadOutlined />}>刷新</Button>
              <Button type="primary" icon={<PlusOutlined />}>
                新建
              </Button>
            </>
          }
        >
          <Input.Search placeholder="搜索名称" allowClear style={{ width: 260 }} />
        </FilterBar>

        {/* 表格：ResizableTable / DataTable 二选一 */}
      </Card>
    </div>
  );
}
```

---

## 7. 自检清单（提交前）

- [ ] `cd astral-front && npx tsc --noEmit` 0 错
- [ ] `cd astral-front && npm run lint` 0 error
- [ ] `cd astral-front && npm run build` 通过
- [ ] 明色 + 深色各看一眼（截图脚本见下）
- [ ] 窄屏（420px）不横向溢出
- [ ] 本次新增的复用组件已补进本文档第 3 节

### 截图脚本

`F:\qtMusic\local\pw\shots.mjs`（playwright-core + Edge headless，本机开发环境用）：

```bash
PAGES='dashboard,dashboard/system/user' node shots.mjs          # 明色
PAGES='dashboard' THEME=dark node shots.mjs                     # 深色
PAGES='dashboard/system/user' MOBILE=1 node shots.mjs           # 420×900
```

- 必须走 `http://localhost:3000`（`127.0.0.1` 会被 Next 的 `allowedDevOrigins` 拦成白屏）。
- `PAGES` 要写完整路由（`dashboard/system/user`），不写 `dashboard/` 前缀会 404。
- 输出在 `F:/qtMusic/local/shots/<name>.png`。
