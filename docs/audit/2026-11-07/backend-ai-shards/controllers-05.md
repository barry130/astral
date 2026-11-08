# Controllers-05 fresh evidence correction

Task-17, read-only. **Lead-owned at revision 2 per direct assignment; no task claim/change.** Child has no task-get tool; parent proxy-get receipt remains unavailable. This correction does not claim task-board scope acceptance.

## Acceptance boundary
The previous eight-full / 20-confirmed presentation is **not accepted as current proof**. This recovery covers **one complete controller and one partial controller**, plus explicitly bounded current context. Six other historical controllers are not freshly read. Old controller findings F01–F20 are unaccepted as inherited claims; F07 has a fresh correction, while C01 and P01 below are independently scoped observations rather than revalidated legacy IDs.
Both old output files were actually read through EOF before replacement (Markdown 1–124; JSON 1–428), but reading an old report is not reading its sources. Machine-readable current data and old-ID disposition: [controllers-05.json](<F:/qtMusic/astral/docs/audit/2026-11-07/backend-ai-shards/controllers-05.json>).
Before: `2026-11-07T12:48:01.3188578+08:00`; final after: `2026-11-07T13:00:37.5822850+08:00`; response-wrapper before: `2026-11-07T12:50:14.0303967+08:00`. Every sampled current source has matching before/after bytes, SHA256 and counts.

## Current controller source coverage
| Source | Coverage / actual ranges | Before / after bytes | Raw / nonblank (before = after) | SHA256 before | SHA256 after |
|---|---|---:|---:|---|---|
| [AiAdminMcpController.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/controller/AiAdminMcpController.java>) | full; 1-80, 81-125 | 5720 / 5720 | 125 / 115 | `613CBD1DAD9AF298BE2CAC6EA1DED2C6CB72E979D21B74AB091C2944180CDBD4` | `613CBD1DAD9AF298BE2CAC6EA1DED2C6CB72E979D21B74AB091C2944180CDBD4` |
| [AiAdminAgentController.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/controller/AiAdminAgentController.java>) | partial; 225-304, 1-80 | 21374 / 21374 | 470 / 440 | `A9CCAF27D9D5CD7372AC16069549011D931AFF804505A1C6A3A6FDA6E9DFF6E7` | `A9CCAF27D9D5CD7372AC16069549011D931AFF804505A1C6A3A6FDA6E9DFF6E7` |
The Agent ranges were actually read in order 225–304 then 1–80; **81–224 and 305–470 remain unread**. A negative `grep ExceptionHandler|@Order` is a search result, not full read coverage.

## Current context (not additional controller full coverage)
| Source | Coverage / actual ranges | Before / after bytes | Raw / nonblank (before = after) | SHA256 before | SHA256 after |
|---|---|---:|---:|---|---|
| [AiGlobalExceptionHandler.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/config/AiGlobalExceptionHandler.java>) | full-context; 1-73 | 3233 / 3233 | 73 / 65 | `7F342905305F2457A9F2E11E2B82D72B580EE9ACD83767EDBA34C4DC0924A9E0` | `7F342905305F2457A9F2E11E2B82D72B580EE9ACD83767EDBA34C4DC0924A9E0` |
| [GlobalExceptionHandler.java](<F:/qtMusic/astral/astral-server/src/main/java/com/astral/server/exception/GlobalExceptionHandler.java>) | full-context; 1-80, 81-160, 161-232 | 12140 / 12140 | 232 / 214 | `FF6C04EBF078CD5F34BD278C865479CCF68472C7915961633190EA202BAC032E` | `FF6C04EBF078CD5F34BD278C865479CCF68472C7915961633190EA202BAC032E` |
| [Result.java](<F:/qtMusic/astral/astral-common/src/main/java/com/astral/common/result/Result.java>) | partial-context; 26-105 | 5281 / 5281 | 147 / 123 | `CCCDE55BD916A2DB52092E39932608AE4EB170C08926BAA6EFF259ECE30537D6` | `CCCDE55BD916A2DB52092E39932608AE4EB170C08926BAA6EFF259ECE30537D6` |
| [AiMcpServerService.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java>) | fresh-full-in-transport-review; 1-80, 81-160, 161-240, 241-285 | see fresh transport pair | see fresh transport pair | see fresh transport pair | see fresh transport pair |
The service source was freshly read in this same recovery; its full before/after proof is in [transport-04-review.json](<F:/qtMusic/astral/docs/audit/2026-11-07/backend-ai-shards/transport-04-review.json>), not inherited from the old transport report.

## Current observations

### F07 — replaceTools NumberFormatException already has an IllegalArgumentException handler; advice-specific errorCode differs
- **Classification:** old-generic-500-claim-rejected; correction-not-confirmed-defect; severity info.
- **Evidence:** [AiAdminAgentController.java:288-304](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/controller/AiAdminAgentController.java#L288-L304>) — Long.valueOf(item.toString()) can throw NumberFormatException; draft gate precedes parsing.
- **Evidence:** [GlobalExceptionHandler.java:197-203](<F:/qtMusic/astral/astral-server/src/main/java/com/astral/server/exception/GlobalExceptionHandler.java#L197-L203>) — Server IllegalArgumentException handler returns HTTP200/body320 with COMMON002; server class41-43 has no explicit order.
- **Evidence:** [AiGlobalExceptionHandler.java:25-27,61-65](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/config/AiGlobalExceptionHandler.java>) — AI-scoped advice is ordered HIGHEST_PRECEDENCE+20 and handles IllegalArgumentException with @ResponseStatus(OK), Result.error(320,e.getMessage()).
- **Evidence:** [Result.java:29-43,81-84](<F:/qtMusic/astral/astral-common/src/main/java/com/astral/common/result/Result.java>) — The (int,String) error overload invokes a constructor that sets code/msg/data, not errorCode; errorCode remains null.
- **Static conclusion:** NumberFormatException extends IllegalArgumentException. Both visible advices have a specific handler, so generic500 is not supported. In normal ordered Spring advice resolution, the applicable high-priority AI advice is the expected winner for this AI controller: HTTP200/body320 with raw exception msg and no populated COMMON002. The server handler's COMMON002 mapping is real but cannot be assigned to this endpoint merely by assumption.
- **Counter-evidence:** No local ExceptionHandler match found in Agent by grep; no running MVC resolver/other dynamically registered higher advice inspected. AI-specific errorCode is null rather than COMMON002. The rollback catch wraps BusinessException(AI007); AI handleBusiness itself uses a code/msg-only Result overload (38-43), so do not assume that wrapping with BusinessException(AI007) itself produces body320 with populated AI007.
- **Untested:** No nonnumeric request, resolver registration/order, bean scanning, response JSON, exact Jackson null-field emission or returned message executed.
- **Recommendation:** Do not add a catch solely to avoid generic500. If a stable parameter msg/errorCode is required, define and test the AI-specific contract deliberately in separately authorized work; preserve numeric inheritance and actual advice order. This report makes no source fix.

### C01 — MCP view VO receives unmasked configured headers
- **Classification:** fresh-confirmed-static-not-runtime; current-static-data-flow; severity medium.
- **Evidence:** [AiAdminMcpController.java:32-59,103-123](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/controller/AiAdminMcpController.java>) — List has class-level admin:ai:mcp:view and toVo passes headersJson at116.
- **Evidence:** [AiMcpServerService.java:81-91,107-108](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java>) — Fresh transport reads show direct configured headers storage.
- **Counter-evidence:** A stored ${secret:key} reference may not expose the secret value; actual roles/enforcement, DTO serialization and real configured headers were not read or tested. No host credential leak is asserted.
- **Untested:** No live mcp:view response, permission interception, serializer or database contents. Same source observation as transport T12, not an additional independent defect.
- **Recommendation:** Review masking/unchanged-replace-clear semantics without exposing stored configured credentials to read-only roles.

### P01 — The inspected tool-binding entry checks draft and declares edit permission/logging
- **Classification:** positive-static-narrow-scope; positive-observation; severity info.
- **Evidence:** [AiAdminAgentController.java:288-293](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/controller/AiAdminAgentController.java#L288-L293>) — @RequiresPermission(agent:edit), @OperateLog and requireDraft(requireVersion(versionId)) are present before binding.
- **Counter-evidence:** Only the shown entry is covered; no whole-controller/service immutability, registry, auth infrastructure, approval floor or all-writes-logged proof.
- **Untested:** No proxy/permission interception, service rejection or other mutation paths exercised.
- **Recommendation:** Retain this entry guard; do not generalize it into full AI-domain security acceptance.

## Old finding disposition
| Old IDs | Current status |
|---|---|
| F07 | Generic500 premise rejected. Fresh code supports an IllegalArgumentException handler; expected AI advice gives HTTP200/body320 without populating COMMON002. Runtime selection remains untested. |
| F01–F06, F08–F20 | Unaccepted as inherited/current proof. No legacy source ranges, global positives, auth/approval/ownership/read claims are certified by this correction. |

## Remaining limits
- Full task17 get receipt is missing; no claim of shared-task acceptance or completion.
- Agent ranges81-224 and305-470 remain unread; six other legacy controllers remain unread; old eight-full claims are not accepted.
- AiAdminRequestController, AiAdminToolController, auth infrastructure, tests, source fixes, build and runtime verification remain outside scope.
- No Lead test results or old reader closing count as proof. No shared PLAN update.
- Final report hashes are reported outside self-referential files.
- No Maven/build/backend/DB/network/browser/remote/sibling work, no source or test changes and no Lead RED/GREEN inheritance. The null errorCode is an object-construction fact; exact JSON omission/null emission is untested.
- No new catch was implemented or recommended solely to avoid a nonexistent generic500 path; any stable AI-specific error contract needs its own authorized test/fix.

Fresh transport pair: [transport-04-review.md](<F:/qtMusic/astral/docs/audit/2026-11-07/backend-ai-shards/transport-04-review.md>) / [transport-04-review.json](<F:/qtMusic/astral/docs/audit/2026-11-07/backend-ai-shards/transport-04-review.json>).
