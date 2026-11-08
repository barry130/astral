# ToolPanel formatting review — current type-stage

**Mechanically equivalent formatting applied; procedural exception remains.** This bounded review is not behavioral TDD, independent Standards/Spec acceptance, task completion, or whole-Astral certification.

## Change and proof

Only [ToolPanel](<astral-front/src/components/ai/admin/ToolPanel.tsx#L731-L737>) changed: three existing JSX text children became explicit strings (`{' + '}`, `{' / '}`, and `{'\uFF1BSQL \u53EA\u5141\u8BB8\u5355\u6761'}`), followed by single-file configured formatting. The SQL expression preserves the baseline emitter's Unicode spelling and its separate space child. Six other hunks only wrap attributes; total +43/−11 lines, 958→990.

Seven isolated spellings were tested before production editing. Only the three-expression escaped candidate passed both complete emitted-JavaScript profiles before and after formatting. Actual final bytes match that candidate; configured Tailwind-plugin formatting is idempotent. The byte-identical original comparator (SHA256 `7e1ca895c2dfbc071105ffd6957cbbff9bdd1fb0b116dbc830ebe58e02c4415e`) also directly compared the real final source: project ES2017/ESNext and public-loader ES2022/CommonJS both equal. Full-file normalization remains unchanged; identifiers, statements, literals and JSX child arrays/boundaries are preserved.

## Actual gates

Node `D:/Python/python3109/Scripts/node.exe` v24.19.0; TypeScript5.9.3, ESLint9.39.4, Prettier3.9.10. Cwd: `F:/qtMusic/astral/astral-front`.

```text
NODE node_modules/typescript/bin/tsc --noEmit --incremental false
NODE --test tests/form-contract.test.cjs
NODE node_modules/eslint/bin/eslint.js src/components/ai/admin/ToolPanel.tsx
NODE node_modules/prettier/bin/prettier.cjs --check src/components/ai/admin/ToolPanel.tsx
```

All final exits0: tsc0 diagnostics; original13 pass13/fail0; lint0; format0. Baseline format exit1 was reproduced, not a behavioral RED.

## Receipts and limitations

[Structured receipt](<docs/audit/2026-11-07/frontend-toolpanel-format-current.json>) links exact commands, helper/source/emit hashes, native100ns fingerprints and live-read ranges in [new ignored artifacts](<target/form-toolpanel-format-20261107-4d57981d-e529-4385-b9f1-cfb6916aa98a/>). All17 phase2 references,12 additional immutable files and179 old-artifact files have zero byte/native-time drift. Final SHA256: `a8cf43b14c3fcec8769b4c8a2d4a20011873b9a7264eb1404ad7f220baebaa5a`.

**Read-order deviation:** [UI guide](<docs/UI_GUIDE.md#L81-L160>)81–160 initially returned middle-pruned; complete20-line rereads occurred after source editing. Earlier progress overstated pre-edit coverage and was explicitly corrected to Lead. All guides and both source versions are now fully self-read; final990/990 includes every changed range. This does not retroactively satisfy the required pre-edit sequence. Fingerprints prove sampled endpoints, not a continuous lock. No Git, builds, browser, network, tests/config changes, shared task/goal/PLAN updates or commits; Lead owns integration and lease handoff.
