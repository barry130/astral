# ToolPanel — independent STANDARDS axis

**Verdict: NOT CLEAN (procedural).** Hard findings **1**; introduced code findings **0**; Fowler POSSIBLE findings **0**; worst severity **MEDIUM**. This is not Spec, behavior, integration, or whole-task acceptance.

## Finding S1 — pre-edit reading sequence

[ToolPanel:731–737](<F:/qtMusic/astral/astral-front/src/components/ai/admin/ToolPanel.tsx#L731-L737>) and all six other logical groups were edited before complete, unpruned guide coverage. [AGENTS:299–302](<F:/qtMusic/astral/AGENTS.md#L299-L302>) requires reading the UI guide before any interface change; [UI_GUIDE:3](<F:/qtMusic/astral/docs/UI_GUIDE.md#L3>) states “写任何界面前先读这一页。” Producer evidence explicitly admits that guide lines81–160 were middle-pruned and repaired **after editing**. Later reads cannot retroactively satisfy that prerequisite. Attribution: historical producer process, not this reviewer’s source changes. Preserve the exception; do not perform a dummy edit to erase it.

## Per-hunk judgement

Acceptance compares frozen **type-stage baseline → current**, not Git baseline: seven logical groups, **+43/−11**, represented by **five Git U3 hunks**.

- 731–737: explicit plus/slash/escaped SQL text; no documented naming, duplication, or component-reuse violation identified.
- 793–796, 856–864, 866–874: distinct SQL/header/query fields, not duplicated JSX implementations.
- 901–903: existing schema placeholder wrapped.
- 907–912: existing risk field wrapped. `style={{ flex: 1 }}` conflicts with the inline-layout guidance [UI_GUIDE:168–169](<F:/qtMusic/astral/docs/UI_GUIDE.md#L168-L169>), but exists at baseline884: **preexisting debt**, excluded from introduced findings.
- 954–958: existing textarea props/handler wrapped; no new component definition or message chain.

All twelve fixed Fowler smells were considered; none is applicable to the delta. Escaped display text is not a Mysterious Name; ordinary `Input.TextArea` access is not a Message Chain. Repository compatibility-layer allowances override generic barrel-import advice. Tool-enforced formatting/type/lint issues are not duplicated as manual findings.

## Evidence and limits

Own explicit-EOF reads: current990/990, type-stage958/958, Git958/958; guides990/990. Two pruned guide calls received zero credit and were fully repaired. Captured empty committed diff does not erase actual WIP, including the earlier generic-form typing change.

Captured emitted equality and compiler/tests13/lint/format gates are **mechanical/captured receipts only**, not rerun or browser proof. Large proof inventories were sampled, not fully semantically read; freshness fetch was prohibited. Native fingerprints establish exact sampled endpoints, not continuous locking. The [receipt](<frontend-toolpanel-format-standards-current.json>) records attribution, read ledger, hashes, and gaps.
