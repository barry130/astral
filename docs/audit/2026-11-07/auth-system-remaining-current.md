# Auth/system remaining — finite read-only audit

## Coverage and integrity

Exactly **61 FULL / 0 PARTIAL / 0 NOTREAD**: 36 auth, 25 system, selected exclusively from recovery `NOTREAD`. Inventory and native pre-read tuples were persisted before source reads.

Own first pass: **130 contiguous `read` calls, each ≤80 lines; 8,023 raw / 7,279 nonblank lines; 357,723 bytes**. Every file reached explicit EOF. Five separately recorded supplemental calls returned 370 lines; they add no inventory coverage. Platform masking of synthetic test tokens is disclosed, not claimed byte-identical rendered text.

Closing: **61/61 own sources, 164/164 parent-source tuples, and 4/4 frozen reports exact**, including native seven-digit UTC mtimes. Recovery JSON BOM remained byte-exact. Prior 103-file reading and original 86-file structural evidence retain prior attribution.

## Current-source follow-up

The [JSON ledger](<docs/audit/2026-11-07/auth-system-remaining-current.json>) contains all ranges, EOF markers, tuples, per-file notes and qualified findings:

- Capped/failed [token scans](<astral-auth/src/main/java/com/astral/auth/service/impl/TokenServiceImpl.java#L39-L66>) can produce successful revocation without finding an active target; cleanup/global revoke still use unbounded searches.
- [Rule replacement](<astral-system/src/main/java/com/astral/system/controller/NotifyRuleController.java#L347-L373>) passes nullable clears to default `NOT_NULL` entity updates; retained template/platform values may persist.
- Whole server-loaded User writes retain conditional concurrency risk, **not** attacker-entity mass assignment.
- Mail/SMS weight expansion and unfiltered email template-code fallback have conditional capacity/multi-channel effects.
- TOTP failure accounting needs policy review; the login endpoint already declares IP rate limiting. Timing/oracle elimination is not established by equal error codes.
- Domain fallback, null-time sorting and stale comments require review. App log-only acknowledgements versus INAPP persistent reads remain **contract divergence, not a fabricated cross-user exploit**.

Inherited template-save, notification-wrapper, INAPP failure and async-authority findings are separated from own proof. No automatic revert or newly executed test is claimed.

## Acceptance and handoff

**Runtime/end-to-end, HTTP/advice, DB/Redis/cache, startup, cross-client and since-baseline attribution: UNKNOWN.** Existing mocked/internal/helper-oriented tests were only read; they are not external-only public acceptance.

**Jobs: 0.** No source edits, Git, builds/tests, network/browser, real credentials/private keys, task/goal events or memory-plan/handoff/procedure writes.

First remaining source: **none**. Parent independently validates these reports and decides **Lead task30** acceptance. Whole Astral completion is not claimed. This child stops; repairs/tests require separate authorization.
