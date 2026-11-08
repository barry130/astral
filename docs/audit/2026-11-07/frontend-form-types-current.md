# Form type-only repair — task32

## Scope and result

Only [Form.tsx](<F:/qtMusic/astral/astral-front/src/components/antd-compat/Form.tsx>) changed in production. Default values are `Record<string, unknown>`; typed instances, literal/tuple/dotted field lookup, watch results, nested patches, generic props/render utilities, rules, errors and child/event boundaries have finite types. No explicit `any`, suppressions, compiler/rule weakening or caller edits.

Submit/validation/event/preserve/touched/render/async ownership is unchanged, including synchronous `onFinish` invocation and forwarding arbitrary caught failures. Complete emitted-JavaScript trees match after ignoring positions/comments/redundant parentheses; raw emitted bytes differ in formatting. This mechanical comparison is not a behavior test.

## Frozen evidence

Source baseline → final: **1092 → 1154 raw lines; 986 → 1043 nonblank; 35557 → 38984 bytes**. SHA-256: `45f49f5f…` → `b41489c6…`. Exact hashes, native 100ns timestamps, snapshots and read attribution are in [the JSON ledger](<F:/qtMusic/astral/docs/audit/2026-11-07/frontend-form-types-current.json>).

Mandatory source baseline was read in 14 unpruned ≤80-line chunks through EOF1092. Mandatory guides were initially middle-pruned, then fully reread in smaller unpruned chunks. Final source was not separately reread end-to-end; edited boundaries were inspected.

## Gates — installed Node24

- Enforced single-file ESLint: **83 errors → 0**, warnings0.
- Single-file Prettier: **exit0**.
- [Original public regression](<F:/qtMusic/astral/astral-front/tests/form-contract.test.cjs>): **13/13**, skips0; test and loader bytes/timestamps unchanged. No invented behavioral RED.
- Public type snippets: positive diagnostics0; five negative assignments correctly rejected.
- Nonincremental main-frontend TypeScript: baseline **exit0**, final **exit2**. All **40 diagnostics in 10 caller files** are outside Form; exact locations and messages are retained.

## Lead follow-up / limits

Give untyped config/sequence, storage, qt/release, notification-rule and AI forms explicit value interfaces; type standalone `Form.Item` render utilities; narrow genuinely dynamic keys. Do not edit runtime logic or cast unknown data to satisfy callers. Source/test/config hashes stayed stable across final gates.

No Git, builds, browser, network or production checks ran. Supplied a39/task9 WIP attribution was not independently Git-read. Parenttask9, full frontend acceptance and Root acceptance remain unfinished. All owned jobs were collected.
