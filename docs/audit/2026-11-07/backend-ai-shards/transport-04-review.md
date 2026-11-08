# Transport-04 independent current-source review

Task-17, read-only. **Lead-owned at revision 2 according to direct assignment; no shared claim or mutation.** Child catalog lacks task get; the first parent proxy-get request has no full receipt. This is a deliverable under the direct assignment, not a claim that task-board scope/acceptance has been verified.

## Evidence and coverage
**6 full / 0 partial / 0 split transport files; 1,976 raw lines / 1,835 nonblank / 89,067 bytes.** Each file was actually read continuously from line 1 through EOF in 28 `read` calls with requested limit <=80; six subsequent bounded location-check calls are recorded separately (34 total).
Before: `2026-11-07T12:48:01.3188578+08:00`; final after: `2026-11-07T13:00:37.5822850+08:00`. All source SHA256, byte lengths and raw/nonblank counts match. Metadata counting is not reading proof; equal snapshots cannot exclude a transient edit-and-revert.
Old [transport-04.json](<F:/qtMusic/astral/docs/audit/2026-11-07/backend-ai-shards/transport-04.json>) and [transport-04.md](<F:/qtMusic/astral/docs/audit/2026-11-07/backend-ai-shards/transport-04.md>) were actually read to locate/reassess claims only, not endorsed. Their files are unchanged. Machine-readable details: [transport-04-review.json](<F:/qtMusic/astral/docs/audit/2026-11-07/backend-ai-shards/transport-04-review.json>).

| Source | Coverage / actual ranges | Before / after bytes | Raw / nonblank (before = after) | SHA256 before | SHA256 after |
|---|---|---:|---:|---|---|
| [AiMcpServerService.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java>) | full; 1-80, 81-160, 161-240, 241-285; supplemental: 145-224 | 12859 / 12859 | 285 / 263 | `D55CE67F0869F02BEAD68212A23BD59DF13D6CA4AAFA972A6A9221B6336A6655` | `D55CE67F0869F02BEAD68212A23BD59DF13D6CA4AAFA972A6A9221B6336A6655` |
| [AiMcpServerFailureRecorder.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerFailureRecorder.java>) | full; 1-43 | 1759 / 1759 | 43 / 39 | `4042A24EF0C8BEE4BC3717ECDFC1CEA6EB855173AF24A6E9D384471D71738D7C` | `4042A24EF0C8BEE4BC3717ECDFC1CEA6EB855173AF24A6E9D384471D71738D7C` |
| [McpClient.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpClient.java>) | full; 1-80, 81-160, 161-240, 241-320, 321-400, 401-427; supplemental: 161-240, 241-320 | 18451 / 18451 | 427 / 397 | `067A86655CCDACCF6DA03EC895B42278B37B59D0F7AEE7FCD1793EBB69E052B7` | `067A86655CCDACCF6DA03EC895B42278B37B59D0F7AEE7FCD1793EBB69E052B7` |
| [McpToolExecutor.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpToolExecutor.java>) | full; 1-80, 81-160, 161-197; supplemental: 96-175 | 9135 / 9135 | 197 / 181 | `2A099C1D2F5A2D035B56116DB94596D5D7F70DB04746CCAA9EBFAEFC510128E7` | `2A099C1D2F5A2D035B56116DB94596D5D7F70DB04746CCAA9EBFAEFC510128E7` |
| [SqlToolExecutor.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java>) | full; 1-80, 81-160, 161-240, 241-320, 321-400, 401-441; supplemental: 281-360 | 18893 / 18893 | 441 / 414 | `14B919C8321A9BB41DE188BA1FD075D1983BBD9107680ED9764E83396646F28F` | `14B919C8321A9BB41DE188BA1FD075D1983BBD9107680ED9764E83396646F28F` |
| [HttpToolExecutor.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java>) | full; 1-80, 81-160, 161-240, 241-320, 321-400, 401-480, 481-560, 561-583; supplemental: 161-240 | 27970 / 27970 | 583 / 541 | `85455F40A36E4AD2DAC4E3C81436EAEBE086ABAF29C540D264C258A460D27264` | `85455F40A36E4AD2DAC4E3C81436EAEBE086ABAF29C540D264C258A460D27264` |

## Separately counted current context
| Source | Coverage / actual ranges | Before / after bytes | Raw / nonblank (before = after) | SHA256 before | SHA256 after |
|---|---|---:|---:|---|---|
| [AiGlobalExceptionHandler.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/config/AiGlobalExceptionHandler.java>) | full-context-not-transport; 1-73 | 3233 / 3233 | 73 / 65 | `7F342905305F2457A9F2E11E2B82D72B580EE9ACD83767EDBA34C4DC0924A9E0` | `7F342905305F2457A9F2E11E2B82D72B580EE9ACD83767EDBA34C4DC0924A9E0` |
| [GlobalExceptionHandler.java](<F:/qtMusic/astral/astral-server/src/main/java/com/astral/server/exception/GlobalExceptionHandler.java>) | full-context-not-transport; 1-80, 81-160, 161-232 | 12140 / 12140 | 232 / 214 | `FF6C04EBF078CD5F34BD278C865479CCF68472C7915961633190EA202BAC032E` | `FF6C04EBF078CD5F34BD278C865479CCF68472C7915961633190EA202BAC032E` |
| [AiAdminAgentController.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/controller/AiAdminAgentController.java>) | partial-context-not-transport; 225-304, 1-80 | 21374 / 21374 | 470 / 440 | `A9CCAF27D9D5CD7372AC16069549011D931AFF804505A1C6A3A6FDA6E9DFF6E7` | `A9CCAF27D9D5CD7372AC16069549011D931AFF804505A1C6A3A6FDA6E9DFF6E7` |
| [AiAdminMcpController.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/controller/AiAdminMcpController.java>) | full-context-not-transport; 1-80, 81-125 | 5720 / 5720 | 125 / 115 | `613CBD1DAD9AF298BE2CAC6EA1DED2C6CB72E979D21B74AB091C2944180CDBD4` | `613CBD1DAD9AF298BE2CAC6EA1DED2C6CB72E979D21B74AB091C2944180CDBD4` |
| [AiJson.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/common/AiJson.java>) | partial-context-not-transport; 111-188 | 6249 / 6249 | 188 / 170 | `C899E1417B05B48C3167C6B59A20016F042AE13DDDCCFDF1025425D7B6825E46` | `C899E1417B05B48C3167C6B59A20016F042AE13DDDCCFDF1025425D7B6825E46` |
| [AiMcpServerEntity.java](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/entity/AiMcpServerEntity.java>) | full-context-not-transport; 1-66 | 1904 / 1904 | 66 / 50 | `CC9E0D9D086A4200305ABB901A86D653353CBBF9C6CB2961D4D7C9CF354A4CA4` | `CC9E0D9D086A4200305ABB901A86D653353CBBF9C6CB2961D4D7C9CF354A4CA4` |
| [Result.java](<F:/qtMusic/astral/astral-common/src/main/java/com/astral/common/result/Result.java>) | partial-context-not-transport; 26-105 | 5281 / 5281 | 147 / 123 | `CCCDE55BD916A2DB52092E39932608AE4EB170C08926BAA6EFF259ECE30537D6` | `CCCDE55BD916A2DB52092E39932608AE4EB170C08926BAA6EFF259ECE30537D6` |
The response-wrapper before snapshot was sampled separately at `2026-11-07T12:50:14.0303967+08:00`; other context shares the transport before timestamp. Partial context is not full controller/common-source coverage.

## Classified observations (16, not 16 confirmed defects)
`confirmed-static` denotes observed code/contract/control flow only. `tentative-runtime-risk` does not certify exploitability or deployment reachability. All items below include counter-evidence and untested scope. No runtime tests were run.

### T01 — HTTP response bound is checked after full buffering
- **Classification:** confirmed-static-implementation-gap; severity medium.
- **Current loci:** [HttpToolExecutor.java:411-419](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L411-L419>).
- **Evidence:** ofByteArray() completes before bytes.length > maxBytes; the declared 4096..1048576 bound is not a pre-allocation/streaming bound.
- **Counter-evidence:** Oversized completed responses are rejected; request and connect timeouts exist; redirects are NEVER.
- **Untested:** Heap pressure, adversarial infinite/large response, timeout cancellation and actual JDK subscriber behavior were not exercised.
- **Recommendation:** Use a bounded subscriber and cancel on exceeding a byte budget; keep the current post-check as defense in depth.

### T02 — MCP fully buffers JSON/SSE without a response byte budget
- **Classification:** confirmed-static-implementation-gap; severity medium.
- **Current loci:** [McpClient.java:220-231](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpClient.java#L220-L231>); [McpClient.java:285-349](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpClient.java#L285-L349>).
- **Evidence:** ofString(UTF_8) is used before envelope parsing; SSE is parsed from a complete body string, not incrementally as an id-matched event arrives.
- **Counter-evidence:** Connection/request timeout is finite; a server that terminates its response is supported; this is not proof every SSE server hangs.
- **Untested:** No live SSE, memory, cancellation or interoperability run.
- **Recommendation:** Bound bytes and process events incrementally so a matching response need not await end-of-stream.

### T03 — Discovery limits return an incomplete list as authoritative synchronization input
- **Classification:** confirmed-static-control-flow-gap; severity medium.
- **Current loci:** [McpClient.java:97-119](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpClient.java#L97-L119>); [AiMcpServerService.java:162-164](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java#L162-L164>); [AiMcpServerService.java:221-227](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java#L221-L227>).
- **Evidence:** All items in a page are added before the MAX_TOOLS check; one page may exceed 500. A remaining cursor at 20 pages or >=500 items is silently discarded. syncTools then marks all existing-but-untouched rows DISABLED.
- **Counter-evidence:** There is a finite page-count guard; catalogs below both thresholds complete normally; arbitrary malformed servers are not required to be compatible.
- **Untested:** No database rows were changed and no multi-page server was contacted. Whether deployed catalogs reach the threshold is unknown.
- **Recommendation:** Carry completeness/truncation explicitly, fail refresh or avoid absence-based disabling for incomplete catalogs, and bound items while reading the page.

### T04 — The separate HTTP query configuration is validated/collected but unused in request construction
- **Classification:** confirmed-static-contract-gap; severity low.
- **Current loci:** [HttpToolExecutor.java:191-195](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L191-L195>); [HttpToolExecutor.java:227-233](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L227-L233>); [HttpToolExecutor.java:358-421](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L358-L421>).
- **Evidence:** Configuration query entries are validated and their placeholders collected, but doExecute builds only the queryPart extracted from url. It never merges config.query into the outbound URI.
- **Counter-evidence:** URL-embedded query templates are rendered; a configuration relying solely on url works.
- **Untested:** No controller/DTO/UI configuration producer was inspected, no actual request captured.
- **Recommendation:** Implement an explicit collision-safe merge or reject/document unsupported separate query entries.

### T05 — SQL payload omits truncation even after consuming an extra result row
- **Classification:** confirmed-static-contract-gap; severity low.
- **Current loci:** [SqlToolExecutor.java:323-344](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L323-L344>).
- **Evidence:** setMaxRows(maxRows+1); while(resultSet.next() && rows.size()<maxRows) consumes the next row but outputs only rows and rowCount, with no hasMore/truncated indication.
- **Counter-evidence:** Returned row count is bounded and correct for returned rows; there is no false promise of a SQL COUNT result.
- **Untested:** No driver or query execution. Model misinterpretation is conditional, not demonstrated.
- **Recommendation:** Record whether the extra row exists and report truncation explicitly.

### T06 — SQL row limits do not bound cell or payload bytes
- **Classification:** confirmed-static-implementation-gap; severity medium.
- **Current loci:** [SqlToolExecutor.java:328-344](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L328-L344>); [SqlToolExecutor.java:409-434](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L409-L434>).
- **Evidence:** String values are serialized whole and byte[] is fully Base64 encoded; the 1500-character summary bound is applied after building/serializing rows and does not bound payload.
- **Counter-evidence:** At most 200 rows and selected columns are returned; this does not imply every query has large values.
- **Untested:** No large cells, JDBC behavior or downstream payload/audit storage exercised.
- **Recommendation:** Add per-cell and aggregate byte budgets before serialization, with explicit truncation metadata.

### T07 — Duplicate SQL labels overwrite a previously returned column
- **Classification:** confirmed-static-contract-gap; severity low.
- **Current loci:** [SqlToolExecutor.java:380-405](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L380-L405>).
- **Evidence:** Declared names and result labels are lowercased and row.set(normalized,value) repeats the same key for duplicate labels.
- **Counter-evidence:** Case-insensitive matching is documented and intentional; unique aliases avoid collision. Lowercasing alone is not reported as a defect.
- **Untested:** No duplicate-label query executed.
- **Recommendation:** Require unique output aliases or reject collisions rather than silently overwriting.

### T08 — Three executor definitions uppercase required permission codes
- **Classification:** confirmed-static-contract-gap; severity low.
- **Current loci:** [McpToolExecutor.java:104-105](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpToolExecutor.java#L104-L105>); [SqlToolExecutor.java:287-288](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L287-L288>); [HttpToolExecutor.java:339-340](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L339-L340>); [AiJson.java:130-159](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/common/AiJson.java#L130-L159>).
- **Evidence:** definition uses splitCsv, which uppercases; splitPermissionCsv explicitly lowercases and validates required-code shape.
- **Counter-evidence:** This does not prove a permission bypass. The consuming orchestrator and current auth infrastructure were not read or endorsed in this task.
- **Untested:** No permission checks or downstream VO behavior exercised.
- **Recommendation:** Use the existing strict required-code parser; do not admit HELD wildcards/legacy short codes or relax validation.

### T09 — Resolved-address validation is not visibly bound to the HTTP connection
- **Classification:** tentative-runtime-risk; severity medium.
- **Current loci:** [HttpToolExecutor.java:268-311](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L268-L311>); [HttpToolExecutor.java:375-412](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L375-L412>); [McpClient.java:355-377](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpClient.java#L355-L377>).
- **Evidence:** guardHost resolves all addresses and checks them; JDK HttpClient receives the hostname URI, not the validated address set.
- **Counter-evidence:** Private/loopback/link-local/site-local/any-local/multicast and fc/fd IPv6 are rejected; optional exact-host allowlist and NEVER redirects exist. Allowlisting a name is not itself address pinning.
- **Untested:** No DNS, rebinding, TLS/SNI, proxy, special-range or live exploit tests; exact JDK DNS reuse unknown.
- **Recommendation:** Review address-bound egress or a controlled egress proxy while retaining TLS hostname checks; do not describe successful rebinding as proven.

### T10 — Success null assignments may preserve last_error or old configured headers under NOT_NULL updates
- **Classification:** tentative-runtime-risk; severity medium.
- **Current loci:** [AiMcpServerService.java:107-121](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java#L107-L121>); [AiMcpServerService.java:165-172](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java#L165-L172>); [AiMcpServerEntity.java:35-36](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/entity/AiMcpServerEntity.java#L35-L36>); [AiMcpServerEntity.java:50-51](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/entity/AiMcpServerEntity.java#L50-L51>).
- **Evidence:** blank headers and successful lastError are assigned null before updateById; entity declares no per-field updateStrategy override.
- **Counter-evidence:** The source intends clearing. The supplied repository rules describe default NOT_NULL, but actual global mapper configuration/interceptors were not checked; a global ALWAYS policy could change the outcome.
- **Untested:** No SQL generation or write/readback. Persistent stale errors/headers are not treated as runtime-confirmed.
- **Recommendation:** If the actual policy is NOT_NULL, use an explicit intentional null update for these fields; verify without blanket-enabling null updates for all fields.

### T11 — Request-building errors bypass refresh's AiException-only failure recorder
- **Classification:** confirmed-static-control-flow-fact; severity low.
- **Current loci:** [McpClient.java:220-238](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpClient.java#L220-L238>); [McpClient.java:264-268](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpClient.java#L264-L268>); [AiMcpServerService.java:156-169](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java#L156-L169>); [AiGlobalExceptionHandler.java:61-71](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/config/AiGlobalExceptionHandler.java#L61-L71>).
- **Evidence:** baseRequest/header/build is outside send's try; refresh records only AiException. An IllegalArgumentException from building would bypass that record branch.
- **Counter-evidence:** Actual HTTP send exceptions are wrapped AI008. AI advice explicitly maps IllegalArgumentException to HTTP200/body320; it is NOT inherently generic500. Restricted-header behavior remains JDK-dependent.
- **Untested:** No JDK request build, controller dispatch, failure-row or log verification.
- **Recommendation:** Validate reserved/restricted headers and normalize expected configuration failures without claiming every runtime exception should become a parameter error.

### T12 — MCP configured headers are stored and returned without masking
- **Classification:** confirmed-static-data-flow-fact; severity medium.
- **Current loci:** [AiMcpServerService.java:81-91](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java#L81-L91>); [AiMcpServerService.java:107-108](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java#L107-L108>); [AiAdminMcpController.java:32-59](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/controller/AiAdminMcpController.java#L32-L59>); [AiAdminMcpController.java:111-123](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/controller/AiAdminMcpController.java#L111-L123>).
- **Evidence:** headersJson is stored verbatim and passed to the list/detail VO; the list uses the class-level mcp:view declaration.
- **Counter-evidence:** A ${secret:key} reference need not contain a secret value; no real headers were read. Runtime authorization and actual DTO serialization not tested; administrator-configured data is not automatically a host secret.
- **Untested:** No roles, HTTP response, configured credentials or serialization run.
- **Recommendation:** Mask configured values in read-only views and preserve an explicit unchanged/replace/clear edit contract.

### T13 — Error/result text can carry remote details into logs and failure records
- **Classification:** confirmed-static-data-flow-fact; severity low.
- **Current loci:** [McpClient.java:204](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpClient.java#L204>); [McpClient.java:248-256](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpClient.java#L248-L256>); [HttpToolExecutor.java:418-419](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L418-L419>); [AiMcpServerFailureRecorder.java:35-38](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerFailureRecorder.java#L35-L38>); [McpToolExecutor.java:123-139](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpToolExecutor.java#L123-L139>).
- **Evidence:** MCP 404 includes endpoint; HTTP/MCP error bodies are included in failure text; recorder stores up to500 characters; successful MCP content payload is not summary-truncated.
- **Counter-evidence:** Error bodies and summaries are truncated; no actual secrets or downstream ai_tool_call persistence were inspected. Truncation is not redaction, but leakage of a particular secret is not proven.
- **Untested:** No sensitive remote body, configured secret, log sink or downstream model persistence tested.
- **Recommendation:** Use redacted endpoint/status diagnostics and explicit result-retention rules.

### T14 — Remote tool code normalization is not injective; disabled status conflates absence and operator choice
- **Classification:** tentative-design-risk; severity low.
- **Current loci:** [McpToolExecutor.java:174-195](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpToolExecutor.java#L174-L195>); [AiMcpServerService.java:184-214](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java#L184-L214>); [AiMcpServerService.java:221-227](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerService.java#L221-L227>).
- **Evidence:** Case/punctuation folding and64-character truncation can produce equal codes for distinct names. Existing DISABLED records stay disabled when rediscovered.
- **Counter-evidence:** Preserving an operator's manual disable is a sensible policy. Actual database uniqueness constraints were not read; collision rollback behavior is not claimed.
- **Untested:** No conflicting catalog, schema, uniqueness or re-enable workflow tested.
- **Recommendation:** Define collision-safe stable identifiers and distinguish operator-disable from remote absence before changing re-enable behavior.

### T15 — SELECT keyword filtering alone does not establish side-effect-free SQL semantics
- **Classification:** tentative-runtime-risk; severity medium.
- **Current loci:** [SqlToolExecutor.java:60-92](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L60-L92>); [SqlToolExecutor.java:162-200](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L162-L200>); [SqlToolExecutor.java:255-263](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L255-L263>); [SqlToolExecutor.java:319-336](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L319-L336>).
- **Evidence:** The token allow/deny check does not recognize arbitrary function semantics; NEXTVAL or SET_CONFIG is not the forbidden standalone word SET. Connection uses setReadOnly(true), then a manual transaction and rollback.
- **Counter-evidence:** Real PostgreSQL read-only transactions may reject sequence/writing functions, grants may deny others, and local config may roll back. The old blanket claim that nextval/pg_terminate_backend/dblink are actually reachable is not accepted.
- **Untested:** No database/driver/grants/function/extension checks. Application datasource identity is only a comment/parameter contract here; service wiring not reread.
- **Recommendation:** Verify actual JDBC/DB read-only semantics and least-privilege credentials/functions; do not weaken prepared binding or remove rollback.

### T16 — Missing HTTP payloadPath falls back to the whole response; selected string summary bypasses summary limit
- **Classification:** confirmed-static-contract-gap; severity low.
- **Current loci:** [HttpToolExecutor.java:424-455](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L424-L455>).
- **Evidence:** If extraction returns null, an object response becomes the entire payload. A selected string summaryNode uses asString() without SUMMARY_LIMIT.
- **Counter-evidence:** Default whole-response output is supported by design; HTTP maxResponseBytes bounds completed accepted responses. Conditional exposure requires a mistaken path or unusually large selected string.
- **Untested:** No producer contract, payload or downstream display/model test.
- **Recommendation:** Differentiate omitted vs invalid projection paths, and truncate string summaries consistently.

## Positive static controls (not defects)

### P01 — positive-static
- **Evidence:** HTTP and MCP clients use NEVER redirects; host validation rejects the enumerated local/private categories and supports optional exact-host allowlists.
- **Loci:** [HttpToolExecutor.java:115-119](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L115-L119>); [HttpToolExecutor.java:268-311](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L268-L311>); [McpClient.java:61-64](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpClient.java#L61-L64>)
- **Counter-evidence:** Address validation is not a runtime proof of destination pinning (T09).
- **Untested:** No network/TLS/DNS verification.

### P02 — positive-static-with-policy-limit
- **Evidence:** MCP readOnlyOf is serverReadOnly && !Boolean.FALSE.equals(readOnlyHint); explicit false or non-readonly server maps to HIGH/ADMIN_CONFIRM. Missing hint is deliberately accepted when the server is readonly.
- **Loci:** [McpToolExecutor.java:150-171](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/McpToolExecutor.java#L150-L171>)
- **Counter-evidence:** Remote readOnlyHint is not proof of remote behavior; unknown != true. Orchestrator approval enforcement is outside fresh evidence.
- **Untested:** No remote side effects or approval execution.

### P03 — positive-static
- **Evidence:** SQL uses PreparedStatement scalar binding, declared output columns, bounded row/query-time settings, and finally rollback.
- **Loci:** [SqlToolExecutor.java:183-199](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L183-L199>); [SqlToolExecutor.java:319-364](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/SqlToolExecutor.java#L319-L364>)
- **Counter-evidence:** Does not establish payload bytes, least-privilege credentials or all function behavior (T06/T15).
- **Untested:** No JDBC execution.

### P04 — positive-static
- **Evidence:** Failure recording is in a separate REQUIRES_NEW component, bounds error text to500 and warns for zero updated rows; cannot guarantee successful persistence if recorder itself fails.
- **Loci:** [AiMcpServerFailureRecorder.java:27-41](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiMcpServerFailureRecorder.java#L27-L41>)
- **Counter-evidence:** Connection contention/recorder failures remain possible; refresh includes network I/O within its transactional method but exact connection counts/time totals were not measured.
- **Untested:** No transaction/connection pool/failure persistence run.

## Non-inherited claims and remaining limits
- No assertion of 20 confirmed defects or a global absence of High vulnerabilities.
- No asserted hard500 discovery item limit: post-page guard is not a hard item budget (T03).
- No claim both server readonly and a true remote hint are mandatory: absent hint is accepted (P02).
- No inherited HIGH-always-approval, risk-floor update, permission dual-group, testRun or approval snapshot proof; those consumers were not freshly read.
- No claim all secret values cannot land in storage: literal headers are stored; only late-bound references avoid storing the resolved value on that code path.
- No schema collision, nextval bypass, two-connection count or exact refresh60-second-total runtime assertion.
- MCP delete disables and retains tool rows before server removal; counting retained linked rows is not intrinsically meaningless. The old contrary characterization is not accepted.
- JSON envelopes use `asLong()` without requiring numeric type; SSE explicitly requires `isNumber()`. An everywhere-numeric-id assertion is not adopted. Initialization sends a fixed requested protocol version and discards the returned result; negotiation behavior is not runtime-verified.
- Full shared task get receipt missing; Lead ownership/revision only supplied by direct assignment; child made no shared mutation.
- Current auth infrastructure and RequestController/tests are Lead-owned and were not inspected or edited.
- Only six transport sources are counted full; context reads are separately classified and do not extend to the full AI domain.
- No Maven/build/runtime/backend/DB/network/browser/remote/sibling activity; no tests were executed or inherited as proof.
- No external references were fetched and old external-reference quotations were not adopted.
- Artifact hashes are computed on final bytes and delivered outside the artifact; self-hashes are not embedded.
- Neutral memory-log records only report delivery and static limits. No shared PLAN or procedure-memory write.

Paired controller correction: [controllers-05.md](<F:/qtMusic/astral/docs/audit/2026-11-07/backend-ai-shards/controllers-05.md>) / [controllers-05.json](<F:/qtMusic/astral/docs/audit/2026-11-07/backend-ai-shards/controllers-05.json>). Final artifact SHA256 values are reported in handback, not embedded in themselves.
