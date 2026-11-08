# Blog SPEC finite final — NOT ACCEPTED

## Scope and coverage

Frozen candidate `1fa5ace8-10fd-4fa7-9841-41ea237d8364`; fixed baseline/HEAD `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`. Empty settled three-dot diff does not erase uncommitted WIP.

The [exhaustive ledger](<F:/qtMusic/astral/docs/audit/2026-11-07/blog-spec-final-1fa5ace8.json>) preserves 118 sources (15,297 raw lines; 566,181 bytes), 174 hunks/78 sections, 33 untracked snapshots, nine original drivers, and eight frozen norms.

Final-own source reads: six FULL drivers, one PARTIAL editor, 111 NOTREAD; 45 calls, 3,257 unique raw lines. Separately attributed inherited FULL112 plus final-own FULL6 yields FULL118, **not an independent fresh FULL118 review**. Seven norms are final-own FULL; AGENTS remains inherited FULL. The pruned UI-guide receipt is excluded and repaired by eight unpruned ten-line reads.

Final-own patch reads cover 3,357 lines/15 hunks/seven sections in 46 calls; inherited 5,734 lines/159 hunks combine to all 9,091 lines/174 hunks/78 sections. Combined patch/norm gaps: none.

## Finding

**P2 — SPEC-FINAL-001: preserve the restored Tab contract in the browser gate.** [Browser driver:291–315](<F:/qtMusic/astral/target/audit-blog-task23-root-candidate-20261107-1fa5ace8-10fd-4fa7-9841-41ea237d8364/snapshot/astral-blog/tests/browser-gate.mjs#L291-L315>) presses ordinary Tab and expects textarea focus to leave, without Escape. [Editor:248–308](<F:/qtMusic/astral/target/audit-blog-task23-root-candidate-20261107-1fa5ace8-10fd-4fa7-9841-41ea237d8364/snapshot/astral-blog/src/components/markdown-editor.tsx#L248-L308>) correctly inserts two spaces/indents blocks; Escape releases native navigation. The required callback therefore retains an incompatible assertion before later Emoji/syntax/scroll checks. This is a confirmed static mismatch, **not runtime reproduction**. A separately authorized candidate should retain navigation assertions after Escape and add ordinary/block/ShiftTab checks; the frozen driver was neither changed nor executed.

## Finite stop and boundaries

`fullAxis=false`, `independentFullAxis=false`, `axisAccepted=false`; parent acceptance remains open. Independent next read: [frozen .dockerignore](<F:/qtMusic/astral/target/audit-blog-task23-root-candidate-20261107-1fa5ace8-10fd-4fa7-9841-41ea237d8364/snapshot/astral-blog/.dockerignore>), offset 1, limit 80. Every independent source/norm/patch gap remains in the ledger.

Native pre/post checks cover sources118, norms8, support7, prior SPEC reports6, patch, closing, and validation: zero errors. They prove sampled equality only; remaining02’s late-pre caveat persists. Runtime remains UNKNOWN: no browser, services, tests, builds, generators, network, or database operations. Trusted IME, physical wheel/layout, downloads, cookie ordering, cross-tab behavior, and production remain unverified. Root-owned tasks28/12, goal, and whole acceptance are unchanged.
