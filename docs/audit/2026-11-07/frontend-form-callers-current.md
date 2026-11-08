# Frontend Form callers — bounded phase 2

Task-32 remains **Lead-owned, in_progress rev3**. Scope: exactly ten leased callers; finite generic/type-only repairs plus runtime-equivalent scoped formatting. No task/goal change or whole-audit acceptance.

## Verified results

| Gate | Baseline → final |
| --- | --- |
| Whole-front nonincremental TypeScript | 40 diagnostics, exit 2 → 0, exit 0 |
| Individual ESLint | 10/10 pass |
| Individual configured Prettier | 9/10 pass; exception below |
| Original public contract tests | 13/13 → 13/13; zero failures/skips |
| Entire emitted JavaScript | 20/20 equal: ten files × two profiles |

All gate invocations used `D:/Python/python3109/Scripts/node.exe`: Node 24.19.0, TypeScript 5.9.3, ESLint 9.39.4, Prettier 3.9.10. No installations/upgrades.

Complete type-erased JS was whitespace-normalized and compared in project ES2017/ESNext and original-loader ES2022/CommonJS profiles. Runtime control flow, literals, submit/validation/events, preserve/touched/async/wheel/navigation/ownership remain unchanged; no invented private behavior tests.

[Form](<F:/qtMusic/astral/astral-front/src/components/antd-compat/Form.tsx>) remains 38,984 bytes, SHA256 `b41489c65ad7e10785c97602f4a46935a05c3c544c6fd8311dbf743b2c43f657`. Seventeen frozen references—including original assertions, loader, configuration, lock and first-phase reports—retain bytes and native 100ns timestamps; baseline snapshots remain intact.

## Read receipts and limitation

All ten initial walkthroughs reached EOF unpruned, with pre-final/during-edit epochs distinguished; physical baseline totals 8,899 raw lines. All final changed ranges and EOFs were reread: 1,231/9,142 final lines, including a complete final dictionary reread. Remaining final-snapshot, shared-Form/API and first-phase-JSON read gaps are explicit, not represented as semantic acceptance.

[ToolPanel](<F:/qtMusic/astral/astral-front/src/components/ai/admin/ToolPanel.tsx>) had baseline/final Prettier exit 1. Its candidate splits/merges original JSX child literals (`" + "` versus `" +"` plus `" "`); both emit profiles reject equivalence. Candidate **not applied**; no formatter bypass or configuration weakening. Only this formatting portion remains unaccepted.

[JSON receipts](<F:/qtMusic/astral/docs/audit/2026-11-07/frontend-form-callers-current.json>) contain the actual 40-location inventory, type/assertion invariants, exact changed/read ranges, before/after byte/SHA/raw/nonblank/native-mtime fingerprints, serial gate records and full-JS proof paths. No Root, whole-frontend semantic, build/browser or final audit acceptance is claimed.
