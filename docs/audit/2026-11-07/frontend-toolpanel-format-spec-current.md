# ToolPanel format-only — independent SPEC axis

**Bound:** [origin rev2:7](<F:/qtMusic/astral/target/audit-toolpanel-format-axes-candidate-20261107-ed81c73d-98ba-4024-b70b-75bd78c3e000/origin-task35.md#L7>) permits “允许将既有 JSX 文本显式表达为同值字符串以避免 Prettier 拆合” and requires “完整运行时 AST/字面值/children结构与当前类型阶段基线精确一致”. Review compares CURRENT TYPE-STAGE958→990, not Git baseline or unrelated WIP.

## (a) Missing/partial requirements: 1

**SPEC-01 — medium, procedural; worst within SPEC.** Exact [SPEC:19](<F:/qtMusic/astral/docs/audit/2026-11-07/SPEC.md#L19>) line:

```text
- 遵守仓库 `AGENTS.md`，界面改动先读 `docs/UI_GUIDE.md`，沿用现有 shadcn/主题令牌及 axios 出口；不重新引入 antd。
```

The required pre-edit complete read was not satisfied: the initial guide81–160 response was middle-pruned; unpruned repair occurred after editing. [Producer receipt:319–321](<F:/qtMusic/astral/target/audit-toolpanel-format-axes-candidate-20261107-ed81c73d-98ba-4024-b70b-75bd78c3e000/proof/frontend-toolpanel-format-current.json#L319-L321>) explicitly preserves that gap and corrects the earlier overstatement. Later reading cannot retroactively fulfill sequencing. Keep this exception disclosed; no source change can repair the historical event.

## (b) Unrequested behavior/scope creep: 0

No bounded-delta evidence of new behavior, APIs, imports, configuration, dependencies, suppression, or proof relaxation. Five Git U3 hunks group seven logical edits; this is not drift.

## (c) Implemented-looking but wrong requirements: 0

The three explicit text expressions preserve ` + `, `；SQL 只允许单条`, and ` / `, including the separate space child. Six other groups wrap attributes only. Original comparator normalization/options remain intact. Both complete captured emissions independently compare byte-equal: project42,439B and public-loader47,425B; exact SHA/native tuples are in the [receipt](<F:/qtMusic/astral/docs/audit/2026-11-07/frontend-toolpanel-format-spec-current.json>).

## Evidence and bounded verdict

Own unpruned reads: both complete sources958/990, canonical origin10/SPEC58/TEST-SEAMS42, relevant patches/helpers/proof receipts through EOF. Own checks129/0 plus37 unchanged native endpoint tuples; no continuous-lock claim. Inherited only: tsc0/diagnostics0, original13 pass13/fail0/no skips, ESLint0, configured Prettier1→0; receipt validator650+6/0. No gates rerun or behavioral RED invented.

**Verdict:** bounded mechanical format requirements conform; complete procedural SPEC compliance is not established because SPEC-01 remains open. This is neither whole Form/Astral/current-generator nor browser/IME/wheel/real-download/final-gates acceptance. Lead retains integration and overall acceptance.
