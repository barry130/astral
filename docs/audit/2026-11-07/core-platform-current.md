# Core platform — current-code bounded static review

## Scope and claimsBounds

- Fixed comparison point: `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`; initial and closing HEAD are the same commit. The subject is the **dirty working tree**, including untracked authored files, not an empty three-dot diff. Git name/status, numstat and zero-context hunk metadata were inspected. Findings describe current code; a changed file does **not** establish that each finding was introduced by WIP.
- Independently inventoried the four authorized modules' POM, authored main/test Java and resources; added the root [POM](<../../../pom.xml>), [Checkstyle configuration](<../../../checkstyle.xml>) and [SpotBugs exclusions](<../../../spotbugs-exclude.xml>) as references. No previous coverage ledger or review conclusions were inherited. Broad initial glob listings exposed excluded path names only; excluded contents were not opened. Closing globs used absolute-root, module-anchored patterns.
- Every inventory file was fingerprinted before and after: SHA256, bytes, physical raw lines and nonblank lines. A terminal newline is not an additional empty physical line. **188/188 fingerprints are unchanged; no inventory addition/deletion was observed.** This proves endpoint stability, not absence of transient edits or runtime correctness.
- **67 FULL, 0 PARTIAL, 121 NOTREAD; 9,830 raw / 8,945 nonblank lines semantically read.** Each FULL file was read from offset 1 in consecutive chunks no larger than 80 lines through EOF. Normalized per-file ranges and fingerprints are in the companion [JSON](<core-platform-current.json>). Hashing unread files does not upgrade them to FULL.
- Standards separately read through EOF: [AGENTS](<../../../AGENTS.md>) 1–534, [coding guide](<../../CODING_GUIDE.md>) 1–207, [components guide](<../../../COMPONENTS_GUIDE.md>) 1–496; their 1,237 lines are not included in authored inventory/read counts. Historical assertions inside these documents are requirements/context, not independently verified results.
- No source/test/POM/lock changes; no Maven, DB, Redis, backend, HTTP, browser, login, network research, staging, commit or push. Only these two audit deliverables were written. Tests below were **read, never executed**. Lead owns full coverage, runtime/public-seam Tests, independent Standards/Spec axes, build/Git lanes and acceptance.
- Outside-scope/unread dependencies include DAO SQL/entities and serialization metadata, permission aspect/resolvers, schema implementation and plugin lifecycle. Framework internals and deployment configuration were not inspected. No SQL atomicity, actual secret exposure, startup outcome, HTTP exploit, API/DATA enforcement or deployment-wide uniqueness is certified.
- Labels: `confirmed-local-static` = a visible code/contract fact, possibly conditional on an explicitly stated input/interleaving; `candidate` = additional requirement/runtime/dependency evidence is needed; `positive` = a bounded safeguard, not whole-system approval. Priorities are triage suggestions, not reproduced incident severities.

## Actual coverage

| Scope | Inventory files | Raw | Nonblank | FULL | Read raw | Read nonblank |
|---|---:|---:|---:|---:|---:|---:|
| astral-common | 24 | 2,087 | 1,841 | 8 | 797 | 701 |
| astral-monitor | 39 | 4,238 | 3,865 | 19 | 3,003 | 2,745 |
| astral-sequence | 32 | 3,675 | 3,363 | 20 | 3,185 | 2,913 |
| astral-server | 90 | 8,929 | 8,085 | 17 | 2,426 | 2,195 |
| Root configuration references | 3 | 419 | 391 | 3 | 419 | 391 |
| **Total** | **188** | **19,348** | **17,545** | **67** | **9,830** | **8,945** |

The JSON is authoritative for per-file counters/ranges. FULL does not mean defect-free. In particular, all migration resources, production/database-example profiles, statistics reporting service, several sequence administration services/controllers and most common utilities remain NOTREAD. There is no full-core or full-resource approval.

## Major risks — exactly 12

### R01 — First-create uniqueness recovery skips requested-type binding
- **Classification:** confirmed-local-static
- **Priority:** P1 conditional correctness
- **Evidence:** [GeneratorFactory:284–334](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L284-L334>); [same-type test:106–119](<../../../astral-sequence/src/test/java/com/astral/sequence/service/GeneratorFactoryTest.java#L106-L119>).
- **Cause:** A null lookup enters the create branch. DuplicateKeyException rereads/caches the winning row at 308–317 but never runs the requested/existing-type comparison at 321–326. Two different-type first callers can follow the winner instead of the losing caller receiving SEQ006; nextResult dispatches the resulting type.
- **Counterevidence:** Ordinary existing rows reject mismatches; only uniqueness conflicts are recovered; disabled rows are checked after both branches. The existing concurrency test only supplies a SIMPLE request and SIMPLE winner.
- **Untested:** Actual uniqueness constraint/transaction visibility and simultaneous DB callers. No claim of duplicate generated IDs.
- **Requirement:** [business-key type locking](<../../../AGENTS.md#L115-L135>) and [factory binding contract](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L34-L39>).
- **Next public seam:** In GeneratorFactoryTest call public nextResult with a SIMPLE request, stub initial select=null, insert=DuplicateKeyException, reread=DATABASE winner; assert SEQ006 and no generator/history calls. Repeat through batchResult. Lead can extend/run this existing test; nothing was executed here.

### R02 — DATABASE batch loop cannot terminate normally at signed-long maximum
- **Classification:** confirmed-local-static
- **Priority:** P1 conditional exhaustion
- **Evidence:** [DatabaseGenerator:85–109](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/DatabaseGenerator.java#L85-L109>).
- **Cause:** If incrementAndGet returns Long.MAX_VALUE, the inclusive `long v <= max; v++` loop wraps v to Long.MIN_VALUE. All signed long values satisfy v<=Long.MAX_VALUE, so normal termination is impossible; allocation failure/interruption of the process may intervene.
- **Counterevidence:** Ordinary returned maxima terminate; initialization attempts are bounded. The mapper may reject overflow/exhaustion, but its SQL was not read.
- **Untested:** Reachability in real DB, JDBC/SQL overflow behavior, memory impact. No live exhaustion attempt was made.
- **Requirement:** [atomic sequence increment contract](<../../CODING_GUIDE.md#L67-L71>) and the bounded returned batch contract at [85–109](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/DatabaseGenerator.java#L85-L109>).
- **Next public seam:** Mock the mapper to return Long.MAX_VALUE for public batch("orders",1); exercise in an isolated fork with a hard process deadline, not an indefinitely running in-process test. Assert a finite one-value result or a defined exhaustion exception after a bounded implementation. Lead owns creating/running this test.

### R03 — Omitted type on a newly created _id key can use non-SEGMENT default
- **Classification:** confirmed-local-static
- **Priority:** P1 conditional invariant
- **Evidence:** [factory:287–300](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L287-L300>), [dispatch:140–147](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L140-L147>), [entity-key provider:47–58](<../../../astral-sequence/src/main/java/com/astral/sequence/config/EntityIdSequenceProvider.java#L47-L58>), [explicit-type test:157–164](<../../../astral-sequence/src/test/java/com/astral/sequence/service/GeneratorFactoryTest.java#L157-L164>).
- **Cause:** The _id guard requires requestedType!=null. A missing type and missing row falls back to configurable defaultType, even when non-SEGMENT.
- **Counterevidence:** [default configuration:181–183](<../../../astral-server/src/main/resources/application.yml#L181-L183>) selects segment. Startup precreation ordinarily supplies SEGMENT. [MetaObjectHandler:103–110](<../../../astral-sequence/src/main/java/com/astral/sequence/config/SequenceMetaObjectHandler.java#L103-L110>) calls SegmentGenerator directly, so ordinary entity INSERT fill is not shown to take this factory branch.
- **Untested:** Nondefault deployment, missing builtin row, plugin ordering; no ordinary entity-fill vulnerability is claimed.
- **Requirement:** [all _id keys must use SEGMENT](<../../../AGENTS.md#L115-L135>).
- **Next public seam:** Existing GeneratorFactoryTest: set defaultType=SIMPLE, stub missing "orders_id", call public nextResult("orders_id",null), capture inserted type; require SEGMENT or an explicit rejection, with no SIMPLE dispatch. Also test blank type.

### R04 — API metric cardinality threshold does not bound concrete URI keys
- **Classification:** confirmed-local-static
- **Priority:** P1 resource/correctness
- **Evidence:** [interceptor:63–70](<../../../astral-server/src/main/java/com/astral/server/interceptor/ApiRequestMetricInterceptor.java#L63-L70>), [collector:26–34](<../../../astral-monitor/src/main/java/com/astral/monitor/service/ApiMetricCollector.java#L26-L34>), [collector:55–68](<../../../astral-monitor/src/main/java/com/astral/monitor/service/ApiMetricCollector.java#L55-L68>).
- **Cause:** The caller passes normalized raw requestURI, not the matched route template. On threshold exhaustion the collector only empties appVersion; computeIfAbsent still inserts every distinct URI. Distinct path IDs can create >20,000 keys even with empty version.
- **Counterevidence:** Platform whitelist, URI length limit, existing-key fast path and periodic drain reduce costs but do not bound URI cardinality. This is a local map-bound failure, not a demonstrated anonymous HTTP attack.
- **Untested:** Reachable variable-ID routes, ingress controls, traffic volume and heap impact.
- **Requirement:** [bounded state guidance](<../../CODING_GUIDE.md#L145-L152>) and [collector's own capacity rationale](<../../../astral-monitor/src/main/java/com/astral/monitor/service/ApiMetricCollector.java#L26-L34>).
- **Next public seam:** Call public ApiMetricCollector.record with 20,001+ distinct valid `/api/v1/.../id-N` URIs, fixed method/status/platform and empty version; drain and count keys. Separately pass a matched-template request through public interceptor afterCompletion to capture its supplied URI. No network needed.

### R05 — Statistics-disable flag and required alert dependency disagree
- **Classification:** candidate
- **Priority:** P1 optional-bootstrap
- **Evidence:** [unconditional controller:48–59](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L48-L59>), [conditional engine:48–52](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L48-L52>), [exposed STAT_ENABLED flag:195–199](<../../../astral-server/src/main/resources/application.yml#L195-L199>).
- **Cause:** AlertAdminController requires AlertEngine while the engine is conditional on astral.stat.enabled=true. The visible configuration allows false, leaving no engine from this definition for that required constructor dependency.
- **Counterevidence:** Default is true; another configuration/bean or excluded scan may alter the actual context. None was inferred from outside scope.
- **Untested:** Real bootstrap, full bean graph and property precedence. Startup failure is a candidate, not a reproduced fact.
- **Requirement:** [engine feature-toggle contract](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L48-L52>) and [published flag](<../../../astral-server/src/main/resources/application.yml#L195-L199>).
- **Next public seam:** Lead builds an isolated ApplicationContextRunner with the actual controller/engine definitions, mock mapper/mail collaborators and astral.stat.enabled=false; assert the intended disabled-mode context behavior. Do not start a real backend or connect services.

### R06 — Whitelisted PATCH-style updates still overwrite omitted concurrent changes
- **Classification:** confirmed-local-static
- **Priority:** P1 conditional lost-update
- **Evidence:** [sequence update:253–299](<../../../astral-sequence/src/main/java/com/astral/sequence/controller/SequenceConfigController.java#L253-L299>), [channel update:125–152](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L125-L152>), [rule update:281–310](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L281-L310>), [PatchValues:32–41](<../../../astral-common/src/main/java/com/astral/common/util/PatchValues.java#L32-L41>).
- **Cause:** Null/omitted fields are resolved from the earlier selected row and then unconditionally included in wrapper SET. An omitted enabled field can overwrite a toggle committed after the read. Whitelisting columns prevents mass assignment, not stale values within the whitelist.
- **Counterevidence:** Sensitive columns are excluded; sequence update is transactional. Neither a transaction annotation nor a plain select proves locking/versioned compare-and-set; mapper behavior remains outside scope.
- **Untested:** Actual isolation/SQL and simultaneous writer outcome. No protected-field escalation is alleged.
- **Requirement:** [explicit warning against stale read-backfill and per-field null checks](<../../../AGENTS.md#L361-L373>).
- **Next public seam:** Invoke public update with omitted enabled, latch the mocked mapper read, perform a concurrent public toggle/update, then capture wrapper SET and apply it to a tiny in-memory row model. Assert omitted fields are absent from SET or conflict is explicit; later verify real isolation through Lead's authorized test lane.

### R07 — Config cache eviction precedes transactional commit
- **Classification:** candidate
- **Priority:** P1 conditional stale-control
- **Evidence:** [transaction entry:249–252](<../../../astral-sequence/src/main/java/com/astral/sequence/controller/SequenceConfigController.java#L249-L252>), [eviction:312–321](<../../../astral-sequence/src/main/java/com/astral/sequence/controller/SequenceConfigController.java#L312-L321>), [cache:72–84](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L72-L84>), [loader/eviction:294–342](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L294-L342>), [eviction test:63–78](<../../../astral-sequence/src/test/java/com/astral/sequence/controller/SequenceConfigControllerTest.java#L63-L78>).
- **Cause:** update calls evictConfigCache before returning from its transactional method. A separate reader between eviction and proxy commit could reload the previous committed row, which then remains cached for up to 60 seconds.
- **Counterevidence:** Successful writes explicitly evict; cache has TTL/size bounds. The read test checks invocation, not after-commit ordering or cache repopulation. DB visibility might differ.
- **Untested:** Proxy transaction boundary, isolation and the actual interleaving; no unconditional stale-disabled issuance claim.
- **Requirement:** [transaction/lock timing guidance](<../../CODING_GUIDE.md#L58-L71>) and [factory immediate disable/cache contract](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L328-L342>).
- **Next public seam:** In a proxied isolated transaction test, pause update after eviction but before commit; call public factory nextResult to load the old row, commit update, then call again and inspect used enabled/config values. Lead owns the transaction adapter and execution.

### R08 — Alert channel list may serialize webhook secret-bearing config
- **Classification:** candidate
- **Priority:** P1 confidentiality
- **Evidence:** [entity-returning list:65–73](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L65-L73>), [config secret use:284–293](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L284-L293>), [parser:11–15](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertConfigParser.java#L11-L15>).
- **Cause:** channelList returns full AlertChannel rows, while config can contain a webhook secret. No controller-side response projection/masking is visible.
- **Counterevidence:** DAO/generated entity Jackson metadata may hide config or mask its secret. That entity is outside authorized scope; no serialized response or real secret was observed. Endpoint has declarative view permission.
- **Untested:** Entity serialization and granted viewer capabilities; actual disclosure is not confirmed.
- **Requirement:** [credentials must not enter response](<../../CODING_GUIDE.md#L86-L91>).
- **Next public seam:** Lead obtains authorized entity/schema evidence, constructs a channel with a harmless marker secret, calls public channelList and serializes using actual Jackson3 configuration; assert marker absent while allowed nonsecret configuration remains available.

### R09 — Async stats success acknowledgment cannot communicate processing drops
- **Classification:** confirmed-local-static
- **Priority:** P2 delivery-contract
- **Evidence:** [controller:43–48](<../../../astral-monitor/src/main/java/com/astral/monitor/api/AppStatController.java#L43-L48>), [async ingestion:75–99](<../../../astral-monitor/src/main/java/com/astral/monitor/service/StatIngestService.java#L75-L99>).
- **Cause:** The controller calls a void async method and returns success. Semaphore rejection returns/drops inside that method; processing exceptions are caught there. Those outcomes cannot become the caller's failure acknowledgment, contrary to the nearby client-retry/no-loss rationale.
- **Counterevidence:** Best-effort telemetry may intentionally permit loss; admission rate limits and batch bound reduce load. Executor submission rejection could still throw before success, but does not communicate later semaphore/processing outcomes.
- **Untested:** Originating durability/retry requirement, actual proxy/executor saturation and client retry logic. No promise of exactly-once ingestion is assumed.
- **Requirement:** [stated retry rationale](<../../../astral-monitor/src/main/java/com/astral/monitor/service/StatIngestService.java#L75-L89>) and [Result success semantics](<../../../AGENTS.md#L419-L441>).
- **Next public seam:** Use the actual proxied ingestion service with blocked harmless mapper stubs to occupy admission permits, call public report for the extra batch, capture Result and processed count. Decide/document best-effort acceptance or require explicit admission failure before acknowledging.

### R10 — Snowflake deployment uniqueness and bounded clock waiting remain unproven
- **Classification:** candidate
- **Priority:** P1 deployment / P2 liveness
- **Evidence:** [node defaults/range:94–107](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SnowflakeGenerator.java#L94-L107>), [per-key state/sequence wrap:127–167](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SnowflakeGenerator.java#L127-L167>), [busy wait:203–207](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SnowflakeGenerator.java#L203-L207>).
- **Cause:** Valid range is not unique allocation across instances; defaults reuse node1/datacenter1. Sequence wrap waits for wall clock advancement without deadline/interruption handling while holding the key lock. Timestamp assembly contains no bizKey, so cross-key numerical equality is possible, but per-key uniqueness is the visible design.
- **Counterevidence:** Deployment may allocate distinct nodes; typical clock advancement ends the spin quickly; >10ms observed rollback throws and range checks fail fast. Cross-key equality is **not** a defect without a global-uniqueness requirement.
- **Untested:** Deployment node assignment, frozen/adjusted clocks, restart persistence, epoch exhaustion and globally unique-ID requirement.
- **Requirement:** [Snowflake range/instance guidance](<../../CODING_GUIDE.md#L145-L150>) and [documented independent bizKey state](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SnowflakeGenerator.java#L211-L215>).
- **Next public seam:** Lead verifies actual node allocation contract, then exercises public next on two instances under a controlled clock through a narrow production-shared clock seam; test sequence-wrap with a fixed clock using an isolated hard deadline. No private-state-only proof of production uniqueness.

### R11 — Optional resource probe timeout does not cancel/bound underlying tasks
- **Classification:** candidate
- **Priority:** P2 degraded-load
- **Evidence:** [DB executor/get:43–76](<../../../astral-monitor/src/main/java/com/astral/monitor/service/DatabaseMonitorService.java#L43-L76>), [DB I/O:82–104](<../../../astral-monitor/src/main/java/com/astral/monitor/service/DatabaseMonitorService.java#L82-L104>), [Redis executor/get:35–68](<../../../astral-monitor/src/main/java/com/astral/monitor/service/RedisMonitorService.java#L35-L68>), [Redis I/O:76–102](<../../../astral-monitor/src/main/java/com/astral/monitor/service/RedisMonitorService.java#L76-L102>).
- **Cause:** Each call starts a new virtual-thread probe. get(3s) times out only the waiting caller; the future is not retained/cancelled and no admission bound is visible. Slow underlying acquisition/queries can accumulate probes under polling. Both static executors have no visible destruction hook.
- **Counterevidence:** Real clients/pools may impose finite timeouts; virtual threads are cheaper; completed probes release connections. Missing resources return without a task. A bounded caller wait is a real positive, not cancellation.
- **Untested:** Driver cancellation/timeout behavior, active-task accumulation and deployment poll rate.
- **Requirement:** [optional monitoring timeout rationale](<../../../astral-monitor/src/main/java/com/astral/monitor/service/DatabaseMonitorService.java#L33-L35>) and [bounded resource guidance](<../../CODING_GUIDE.md#L145-L152>).
- **Next public seam:** Supply blocking fake DataSource/RedisConnectionFactory to public getDatabaseInfo/getRedisInfo; issue repeated calls beyond3s, count outstanding fake acquisitions/tasks, release all latches in finally, and assert a chosen admission/cancellation bound. No real DB/Redis.

### R12 — Scheduled alert delivery does not visibly honor disabled channel
- **Classification:** candidate
- **Priority:** P2 control semantics
- **Evidence:** [editable channel enabled:87–111](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L87-L111>), [fire:163–187](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L163-L187>), [deliver:231–239](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L231-L239>), [manual test:174–188](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L174-L188>).
- **Cause:** fire rereads channel and delivers any nonnull channel; deliver dispatches type/config without enabled gating. Editing channel.enabled to0 is not visibly consumed in that scheduled delivery path.
- **Counterevidence:** Mapper SQL could exclude disabled channels; manual test may intentionally ignore enabled; the channel-disable business semantics were not independently specified. Enabled rule selection is separate.
- **Untested:** DAO behavior and intended difference between scheduled vs explicit test sends.
- **Requirement:** [exposed channel enabled field](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L87-L111>); a stronger disable-delivery requirement must be confirmed by Lead.
- **Next public seam:** Drive public AlertEngine.evaluate with enabled qualifying rule and a harmless disabled-channel mapper result; capture an injected provider's sends. Confirm scheduled delivery is suppressed while public testChannel remains deliberate if that is the agreed contract.

## Additional classified observations — not additional major risks

### L01 — Telemetry producers use inconsistent time sources/zones
- **Classification:** candidate
- **Priority:** P2
- **Evidence:** [ingest zone:41](<../../../astral-monitor/src/main/java/com/astral/monitor/service/StatIngestService.java#L41>), [API bucket:59–60](<../../../astral-monitor/src/main/java/com/astral/monitor/service/ApiMetricCollector.java#L59-L60>), [server errors:79](<../../../astral-monitor/src/main/java/com/astral/monitor/service/ServerErrorRecorder.java#L79>), [cleanup:149–164](<../../../astral-monitor/src/main/java/com/astral/monitor/job/StatAggregationJob.java#L149-L164>), [report default dates:49–54](<../../../astral-monitor/src/main/java/com/astral/monitor/api/StatReportController.java#L49-L54>).
- **Cause:** Ingest reads a JVM system property with Asia/Shanghai fallback; other producers/cleanup/controllers use system-default local time. Spring-only zone configuration would not set the static property.
- **Counterevidence:** A Shanghai-default JVM may align everything. Reporting service and deployment timezone were not read.
- **Untested:** Real bucket/report disagreement, property precedence and timestamp conversion.
- **Requirement:** [hourly aggregation/retention contract](<../../../astral-monitor/src/main/java/com/astral/monitor/job/StatAggregationJob.java#L24-L28>).
- **Next public seam:** Lead runs isolated producer calls under UTC default with no system zone property, captures bucket/occurTime mapper values, and compares public report's selected day after authorized reporting-service review.

### L02 — Metric normalization uses default locale
- **Classification:** confirmed-local-static
- **Priority:** P2 conditional input
- **Evidence:** [normalizeMetric:323–324](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L323-L324>), [validation:328–332](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L328-L332>).
- **Cause:** toUpperCase() uses default locale; lowercase client_error_count under Turkish locale can differ from the ASCII supported constant.
- **Counterevidence:** Uppercase canonical input avoids the effect; channel type normalization already uses Locale.ROOT.
- **Untested:** Process locale and actual rejected request.
- **Requirement:** [supported metric validation contract](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L328-L332>).
- **Next public seam:** Call public normalizeMetric("client_error_count") with Locale tr-TR in an isolated JVM; assert canonical CLIENT_ERROR_COUNT, then invoke public rule creation with harmless mapper stubs.

### L03 — Logging settings are nested beneath management
- **Classification:** confirmed-local-static
- **Priority:** P2 observability/configuration
- **Evidence:** [YAML:242–258](<../../../astral-server/src/main/resources/application.yml#L242-L258>), [request-ID filter:55–70](<../../../astral-server/src/main/java/com/astral/server/filter/RequestIdFilter.java#L55-L70>).
- **Cause:** Indentation puts logging and pattern beneath management; it does not declare root logging.level or logging.pattern.console. The stated request-ID pattern/default-level intent is not configured at the standard root property path in this file.
- **Counterevidence:** Other profiles, environment or Spring defaults may configure logging; request-ID header/MDC still exists. No actual log was captured.
- **Untested:** Effective logging configuration and trace propagation across async tasks.
- **Requirement:** [default SLF4J/INFO guidance](<../../CODING_GUIDE.md#L156-L160>) and [request tracking contract](<../../../AGENTS.md#L395-L397>).
- **Next public seam:** Load this YAML with the actual Boot property loader in an isolated test; assert logging.pattern.console/logging.level.com.astral vs management.logging.* keys, then capture one filter-bound log using the effective test configuration.

### L04 — Several permission literals have three rather than four required segments
- **Classification:** confirmed-local-static
- **Priority:** P2 standards
- **Evidence:** [sequence config:193–196](<../../../astral-sequence/src/main/java/com/astral/sequence/controller/SequenceConfigController.java#L193-L196>), [monitor class:29–32](<../../../astral-monitor/src/main/java/com/astral/monitor/api/MonitorController.java#L29-L32>), [statistics:46–48](<../../../astral-monitor/src/main/java/com/astral/monitor/api/StatReportController.java#L46-L48>).
- **Cause:** admin:sequence:edit, admin:monitor:view and admin:statistics:view lack a distinct resource/operation pair required by the current four-part standard.
- **Counterevidence:** They are still declarative permission annotations; legacy registrations/grants may intentionally use these literals. No authorization bypass follows merely from segment count. Shared generation routes have an authenticated contract, not an automatic per-operation-permission requirement.
- **Untested:** Permission registry, migration/grant alignment and API/DATA enforcement.
- **Requirement:** [encoding and synchronized rename](<../../../AGENTS.md#L326-L351>); [shared sequence authentication](<../../../COMPONENTS_GUIDE.md#L392-L399>).
- **Next public seam:** Lead inventories actual registered codes/grants, then invokes affected public controller methods through the actual permission proxy with API-only/DATA-only/neither subjects before choosing a coordinated literal+migration change.

### L05 — High-water alignment is visible but lifecycle/SQL guarantee is not
- **Classification:** candidate
- **Priority:** P1 boundary
- **Evidence:** [plugin precreation:149–187](<../../../astral-sequence/src/main/java/com/astral/sequence/plugin/SequencePlugin.java#L149-L187>), [alignment:200–228](<../../../astral-sequence/src/main/java/com/astral/sequence/plugin/SequencePlugin.java#L200-L228>), [pre-Spring schema call:25–48](<../../../astral-server/src/main/java/com/astral/server/AstralApplication.java#L25-L48>).
- **Cause:** Alignment guards MAX+step overflow/raises high-water, but catches failures and proceeds. Visible calls/comments do not prove alignment happens before every first ID issuance or matches actual table/SQL metadata. Main calls schema sync before Spring; implementation may be destructive but was not read.
- **Counterevidence:** Absent/invalid tables are skipped, builtin SEGMENT reserves are created, and overflow is guarded; warning/disable configuration exists. No source deletion or stale allocation was executed/observed.
- **Untested:** Mapper atomicity, migrations/schema equivalence, plugin ordering and actual sync deletion behavior.
- **Requirement:** [high-water and same-migration rule](<../../../AGENTS.md#L115-L135>), [migration boundary](<../../../AGENTS.md#L251-L284>), [pre-Spring sync cautions](<../../CODING_GUIDE.md#L178-L185>).
- **Next public seam:** Lead reviews authorized DAO/schema/lifecycle code, drives public plugin init with MAX(id) beyond current high-water and controlled failure, then invokes public entity fill/sequence issuance; require no collision or explicit failure before serving traffic.

### L06 — Per-bizKey generator state has no visible cardinality bound
- **Classification:** candidate
- **Priority:** P2 resource
- **Evidence:** [segment buffers:64–68](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SegmentGenerator.java#L64-L68>), [Snowflake maps:64–74](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SnowflakeGenerator.java#L64-L74>), [factory auto-create:294–305](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L294-L305>).
- **Cause:** New keys can retain buffers/locks/state; no expiry/cap is visible on those maps. Factory's bounded config cache does not bound generator state or persisted config rows.
- **Counterevidence:** Business-key validation/rate limits/authorization may restrict creation, and naive eviction risks sequence correctness.
- **Untested:** Reachable key diversity, long-lived heap and safe eviction contract.
- **Requirement:** [bounded pooled state guidance](<../../CODING_GUIDE.md#L145-L152>).
- **Next public seam:** With harmless sequence allocator stubs, call public next over many valid keys and measure retained state via a production-shared diagnostic boundary; establish key admission policy rather than proving safety by cache-size introspection alone.

### L07 — Some public exception details and validation dispatch need qualification
- **Classification:** candidate
- **Priority:** P2 confidentiality/error semantics
- **Evidence:** [IllegalArgument handler:197–203](<../../../astral-server/src/main/java/com/astral/server/exception/GlobalExceptionHandler.java#L197-L203>), [channel test exception:182–187](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L182-L187>), [handler declarations:1–80](<../../../astral-server/src/main/java/com/astral/server/exception/GlobalExceptionHandler.java#L1-L80>).
- **Cause:** Arbitrary IllegalArgumentException message and delivery exception message can enter business response text. No dedicated HandlerMethodValidationException/ConstraintViolationException mapping was observed in the fully read handler.
- **Counterevidence:** Observed messages may be safe validation messages; unknown/IllegalState failures are fixed-text. Framework dispatch/controller parameter behavior was not established, so missing handler is not a confirmed 500 regression.
- **Untested:** Sensitive contents from collaborators, active method-validation path and framework resolver precedence.
- **Requirement:** [fixed unknown-error message and response distinctions](<../../../AGENTS.md#L419-L459>), [validation response contract](<../../CODING_GUIDE.md#L111-L127>).
- **Next public seam:** Lead invokes actual public handler/controller path with harmless marker-valued collaborator exceptions and real parameter constraints under MockMvc; assert safe message projection and HTTP200/body320 for expected validation families.

### L08 — Alert evaluation lock/cooldown is local, not distributed arbitration
- **Classification:** candidate
- **Priority:** P2 distributed-delivery
- **Evidence:** [evaluation lock:86–109](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L86-L109>), [cooldown update:207–220](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L207-L220>), [channel deletion check:161–170](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L161-L170>).
- **Cause:** JVM lock cannot arbitrate two instances reading the same last-fired anchor; query/check then delete also does not prove FK-safe concurrent rule binding.
- **Counterevidence:** Single-instance deployment may be intended; database locking/FKs or scheduler ownership could provide protection. Updating only cooldown columns avoids overwriting rule edits.
- **Untested:** Multi-instance policy, actual SQL constraints and duplicate-send requirement.
- **Requirement:** [cross-instance read-modify-write guidance](<../../CODING_GUIDE.md#L67-L71>) and [cooldown intent](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L207-L218>).
- **Next public seam:** Drive public evaluate on two independent engine instances sharing latch-controlled fake rule storage and capture provider sends; separately race public deleteChannel/createRule, then inspect real FK/locking in Lead's authorized lane.

### L09 — Webhook response body is buffered despite not being used
- **Classification:** candidate
- **Priority:** P2 outbound resource
- **Evidence:** [send/body handler:279–299](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L279-L299>).
- **Cause:** BodyHandlers.ofString buffers response body while code only checks status; no response-byte bound is visible.
- **Counterevidence:** Approved origins, no redirects and a10s request timeout constrain targets/time, not necessarily bytes. No oversized response was received.
- **Untested:** Remote response size and HTTP-client timeout/streaming behavior.
- **Requirement:** [approved webhook delivery contract](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L267-L299>) and [bounded resource guidance](<../../CODING_GUIDE.md#L145-L152>).
- **Next public seam:** Lead adds a production-shared injectable transport and tests public deliver with a finite oversized harmless response; assert discarding/bounded body consumption without a live external request.

### L10 — Credentialed wildcard CORS remains an explicit unsafe configuration branch
- **Classification:** candidate
- **Priority:** P2 deployment
- **Evidence:** [CORS:31–43](<../../../astral-server/src/main/java/com/astral/server/config/CorsConfig.java#L31-L43>), [safe default:81–89](<../../../astral-server/src/main/resources/application.yml#L81-L89>), [cookie defaults:102–109](<../../../astral-server/src/main/resources/application.yml#L102-L109>).
- **Cause:** Explicit `*` activates allowedOriginPatterns(*) with credentials. This is an opt-in branch, not the default.
- **Counterevidence:** Default localhost origin, warning and SameSite=Strict; actual cross-origin cookie/CSRF behavior may prevent a claimed exploit.
- **Untested:** Production origin/cookie configuration, filter ordering and exploitability.
- **Requirement:** [no wildcard credentialed CORS guidance](<../../CODING_GUIDE.md#L98-L103>).
- **Next public seam:** Lead configures the actual CorsFilter with wildcard then an explicit list and sends harmless MockMvc preflight/credentialed requests from unapproved origins; compare response headers and actual CSRF gate, without browser/network execution here.

### L11 — Async dispatch can challenge request-counter pairing
- **Classification:** candidate
- **Priority:** P2 observability
- **Evidence:** [counter:23–43](<../../../astral-monitor/src/main/java/com/astral/monitor/service/RequestConcurrencyCounter.java#L23-L43>), [registration:23–26](<../../../astral-monitor/src/main/java/com/astral/monitor/config/MonitorWebMvcConfig.java#L23-L26>).
- **Cause:** Each preHandle increments and afterCompletion decrements; there is no request-owned once marker/AsyncHandlerInterceptor callback. An async dispatch lifecycle with more preHandle invocations than final completions could drift upward; the comment is not lifecycle proof.
- **Counterevidence:** Ordinary synchronous paths pair correctly; framework dispatch behavior was not read/executed; getter clamps negative values.
- **Untested:** Actual Spring7 async/redispatch/cancellation callback sequence.
- **Requirement:** [counter's pairing claim](<../../../astral-monitor/src/main/java/com/astral/monitor/service/RequestConcurrencyCounter.java#L23-L24>).
- **Next public seam:** Lead registers the real counter with a harmless Callable/DeferredResult endpoint under MockMvc, performs asyncDispatch/error/cancel completion, and asserts public getActiveRequests returns zero afterward.

### L12 — Shared async executor naming is not a delivery/capacity guarantee
- **Classification:** confirmed-local-static
- **Priority:** P2 conditional loss/resource
- **Evidence:** [executor choices:69–84](<../../../astral-sequence/src/main/java/com/astral/sequence/config/AsyncConfig.java#L69-L84>), [writer catches/completion:73–104](<../../../astral-sequence/src/main/java/com/astral/sequence/service/SequenceAsyncWriter.java#L73-L104>), [writer statistics:107–128](<../../../astral-sequence/src/main/java/com/astral/sequence/service/SequenceAsyncWriter.java#L107-L128>).
- **Cause:** Platform branch uses DiscardOldestPolicy; virtual branch has no admission bound. Writer catches persistence errors and returns normal completion rather than a delivery-status failure.
- **Counterevidence:** These writes are explicitly noncritical history/statistics; external async proxy usage is correct. Queue size/platform pool reduce ordinary pressure. No silently lost critical notification is asserted from an outside-module caller.
- **Untested:** Saturation, all shared callers' delivery requirements and lifecycle shutdown under real configuration.
- **Requirement:** [named external async executor guidance](<../../CODING_GUIDE.md#L28-L54>) and [noncritical writer contract](<../../../astral-sequence/src/main/java/com/astral/sequence/config/AsyncConfig.java#L13-L18>).
- **Next public seam:** Lead blocks all platform workers, fills the real named executor queue, submits tagged harmless tasks and captures which complete; invoke public writer with failing mapper and inspect its returned completion. Decide explicit best-effort vs required-delivery behavior per caller.

### L13 — Sequence update uses bare controller-level Transactional
- **Classification:** confirmed-local-static
- **Priority:** P2 standards
- **Evidence:** [transaction annotation/entry:248–253](<../../../astral-sequence/src/main/java/com/astral/sequence/controller/SequenceConfigController.java#L248-L253>).
- **Cause:** The annotation lacks rollbackFor=Exception.class and is on a controller method, while the current guide requires an external public Service transaction with that rollback rule.
- **Counterevidence:** Controller invocation can still be proxied; visible BusinessException paths may be runtime exceptions, so an actual partial commit from a checked exception is not demonstrated.
- **Untested:** Effective transaction proxy and checked-exception failure after mutation; no claim that this transaction is wholly ineffective.
- **Requirement:** [transaction placement/rollback rules](<../../CODING_GUIDE.md#L58-L64>).
- **Next public seam:** Lead invokes public update through the actual transaction proxy and tests failure after mutation with a harmless transaction adapter; verify the rollback rule and service boundary against the agreed architecture before changing it.

## Bounded positives and their limits

### P01 — Segment code guards local exhaustion, switching and preload publication
- **Classification:** positive
- **Priority:** positive
- **Evidence:** [switch:176–219](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SegmentGenerator.java#L176-L219>), [allocation:236–276](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SegmentGenerator.java#L236-L276>), [preload:353–379](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SegmentGenerator.java#L353-L379>), [bounded CAS:419–428](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SegmentGenerator.java#L419-L428>), [shutdown:75–79](<../../../astral-sequence/src/main/java/com/astral/sequence/generator/SegmentGenerator.java#L75-L79>).
- **Cause:** Visible CAS increment is bounded by max; monotonic/stale-segment checks reject bad ranges; allocation retry count and overflow guard exist; rejection resets loading and executor has shutdown.
- **Counterevidence:** DAO allocation/optimistic update atomicity is outside scope; map growth remains L06. Static code is not concurrent public-seam proof.
- **Untested:** Actual allocator SQL, many-thread switch, rejected preload, exhaustion and shutdown races.
- **Requirement:** [segment CAS guidance](<../../CODING_GUIDE.md#L145-L148>).
- **Next public seam:** Drive public next/batch against latch-controlled allocator stubs that return overlapping/stale/exhausted ranges; collect caller-observed IDs and verify no duplicates/out-of-range values with bounded completion.

### P02 — Sequence async calls use an external bean and named default executor
- **Classification:** positive
- **Priority:** positive
- **Evidence:** [external calls:151–153](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L151-L153>), [batch calls:214–220](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L214-L220>), [writer/lazy:40–43](<../../../astral-sequence/src/main/java/com/astral/sequence/service/SequenceAsyncWriter.java#L40-L43>), [AsyncConfigurer:52–74](<../../../astral-sequence/src/main/java/com/astral/sequence/config/AsyncConfig.java#L52-L74>), [writer helper call:86–89](<../../../astral-sequence/src/main/java/com/astral/sequence/service/SequenceAsyncWriter.java#L86-L89>).
- **Cause:** Factory calls a distinct writer bean. AsyncConfigurer identifies sequenceAsyncExecutor. The writer helper self-call occurs already within async work, not as a caller-thread replacement for entering the proxy.
- **Counterevidence:** Proxy creation/runtime thread identity is untested; L12 covers delivery/capacity, and comments about other modules are not proof.
- **Untested:** Effective AOP/bean configuration, selected thread and lazy dependency bootstrap.
- **Requirement:** [external public async bean/default/lazy guidance](<../../CODING_GUIDE.md#L28-L54>).
- **Next public seam:** Lead creates the actual proxied factory/writer graph with harmless mapper latches and records caller/writer thread identity through public nextResult and returned writer completion.

### P03 — Authentication uses cached lookup paths and distinguishes state failure from inactive account
- **Classification:** positive
- **Priority:** positive
- **Evidence:** [lookup/pattern:140–144](<../../../astral-server/src/main/java/com/astral/server/interceptor/AuthInterceptor.java#L140-L144>), [zone boundaries:381–399](<../../../astral-server/src/main/java/com/astral/server/interceptor/AuthInterceptor.java#L381-L399>), [exact-child helper:97–110](<../../../astral-server/src/main/java/com/astral/server/interceptor/RequestPathSupport.java#L97-L110>), [account distinctions:451–487](<../../../astral-server/src/main/java/com/astral/server/interceptor/AuthInterceptor.java#L451-L487>), [public preHandle test:81–259](<../../../astral-server/src/test/java/com/astral/server/interceptor/AuthInterceptorPreHandleTest.java#L81-L259>).
- **Cause:** Cached Spring lookup/matched pattern and segment-aware classification avoid simple raw prefix equivalence. IllegalState lookup failure returns503 without logout; inactive accounts get business401 and scoped logout. Existing tests actually call public preHandle with mock collaborators, including parsed/string caches and contextPath.
- **Counterevidence:** Tests mock Sa-Token/serialization and were not run. Renewal threshold is disabled in that test setup; broader APP_PUBLIC paths and explicit exemptions are deliberate, not silently certified safe.
- **Untested:** Actual dispatcher/forwarding/encoding, cookie/CSRF/renewal paths, resolver SQL and real token sessions.
- **Requirement:** [route layers](<../../../AGENTS.md#L251-L267>), [HTTP503 vs business401 distinctions](<../../../AGENTS.md#L419-L459>).
- **Next public seam:** Lead runs the existing AuthInterceptorPreHandleTest plus actual MockMvc dispatch variants (matched patterns/contextPath/matrix params) with renewal enabled and real Jackson3 response serialization; retain no-logout503 assertions.

### P04 — Unified Result and transport/business exception distinctions are visible
- **Classification:** positive
- **Priority:** positive
- **Evidence:** [Result:29–51](<../../../astral-common/src/main/java/com/astral/common/result/Result.java#L29-L51>), [getters:108–145](<../../../astral-common/src/main/java/com/astral/common/result/Result.java#L108-L145>), [business handler:65–78](<../../../astral-server/src/main/java/com/astral/server/exception/GlobalExceptionHandler.java#L65-L78>), [transport/fixed failures:158–230](<../../../astral-server/src/main/java/com/astral/server/exception/GlobalExceptionHandler.java#L158-L230>), [rate-limit Result:81–87](<../../../astral-server/src/main/java/com/astral/server/interceptor/RateLimitInterceptor.java#L81-L87>).
- **Cause:** Common fields/getters match code/errorCode?/msg/data/timestamp; business handler uses HTTP200, with405/413/404 and fixed503/500 distinctions. Rate-limit response uses Result and Jackson3 with HTTP200/body429.
- **Counterevidence:** Actual serialization/resolver precedence not tested; L07 covers specific message/validation candidates. No whole-repository duplicate-Result claim is made.
- **Untested:** Wire JSON, handlers for every framework exception and record-error failure behavior.
- **Requirement:** [unified response and handler table](<../../../AGENTS.md#L419-L459>).
- **Next public seam:** Lead invokes public handler mappings under MockMvc and real ObjectMapper; assert exact HTTP/body fields for business,405,413,404,503,500 and collector failure not masking original error.

### P05 — Webhook policy has explicit origin/IP checks, send revalidation and no redirects
- **Classification:** positive
- **Priority:** positive
- **Evidence:** [URL structure:63–96](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/WebhookUrlPolicy.java#L63-L96>), [DNS/allowlist:104–129](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/WebhookUrlPolicy.java#L104-L129>), [private/metadata blocks:179–237](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/WebhookUrlPolicy.java#L179-L237>), [redirects:77–84](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L77-L84>), [send validation:272](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L272>), [test:35–114](<../../../astral-monitor/src/test/java/com/astral/monitor/alert/WebhookUrlPolicyTest.java#L35-L114>), [configured official defaults:185–193](<../../../astral-server/src/main/resources/application.yml#L185-L193>).
- **Cause:** Policy checks scheme/origin/userinfo/fragment, validates all resolved addresses and metadata/private ranges, revalidates on send, and disallows redirects. Empty direct-bean default is fail-closed; application configuration supplies official origins.
- **Counterevidence:** DNS validation is not connection address pinning; [documented rebinding residual:27–29](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/WebhookUrlPolicy.java#L27-L29>) remains. Tests use injected DNS and were not run. L09 covers response-body consumption.
- **Untested:** Network routing, DNS rebinding and actual egress/firewall policy.
- **Requirement:** [save-time outbound approval rationale](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L331-L350>).
- **Next public seam:** Lead runs existing WebhookUrlPolicyTest through public validate with injected DNS and tests configured official-origin binding; require deployment egress/pinned transport evidence before claiming full SSRF prevention.

### P06 — Client IP, request ID and rate-limit cache have bounded local safeguards
- **Classification:** positive
- **Priority:** positive
- **Evidence:** [IP trust chain:40–69](<../../../astral-common/src/main/java/com/astral/common/util/ClientIp.java#L40-L69>), [platform/version:151–170](<../../../astral-common/src/main/java/com/astral/common/util/ClientHeaders.java#L151-L170>), [request ID:35–70](<../../../astral-server/src/main/java/com/astral/server/filter/RequestIdFilter.java#L35-L70>), [rate cache:48–51](<../../../astral-server/src/main/java/com/astral/server/interceptor/RateLimitInterceptor.java#L48-L51>), [raw URI keys:96–107](<../../../astral-server/src/main/java/com/astral/server/interceptor/RateLimitInterceptor.java#L96-L107>).
- **Cause:** Forwarded headers are only used for trusted direct peers and right-to-left chain resolution; request IDs validate safe1–64 characters/regenerate/cleanup; rate windows have capacity+expiry; platform/version normalization is centralized.
- **Counterevidence:** Default trust includes broad private ranges; version shape does not bound cardinality; raw URI rate keys can partition equivalent path forms, subject to dispatch/route reachability. These are deployment/dispatch boundaries, not reproduced bypasses.
- **Untested:** Actual proxy trust list, forwarded chain, URI aliases, async MDC propagation.
- **Requirement:** [trusted proxy/request-ID rules](<../../CODING_GUIDE.md#L98-L109>), [bounded rate cache](<../../CODING_GUIDE.md#L145-L152>).
- **Next public seam:** Lead exercises public ClientIp.resolve, RequestIdFilter.doFilter and RateLimitInterceptor.preHandle with trusted/untrusted chains, invalid IDs and route-equivalent cached-path variants, checking observed response/counter behavior.

### P07 — Ingest normalizes privacy inputs and cleanup avoids nonpositive retention deletion
- **Classification:** positive
- **Priority:** positive
- **Evidence:** [dimension normalization:139–148](<../../../astral-monitor/src/main/java/com/astral/monitor/service/StatIngestService.java#L139-L148>), [bounded extra:326–343](<../../../astral-monitor/src/main/java/com/astral/monitor/service/StatIngestService.java#L326-L343>), [duplicate increments:231–270](<../../../astral-monitor/src/main/java/com/astral/monitor/service/StatIngestService.java#L231-L270>), [client error metadata:277–299](<../../../astral-monitor/src/main/java/com/astral/monitor/service/StatIngestService.java#L277-L299>), [server-only error fields:70–80](<../../../astral-monitor/src/main/java/com/astral/monitor/service/ServerErrorRecorder.java#L70-L80>), [privacy test:1–66](<../../../astral-server/src/test/java/com/astral/server/StatIngestPrivacyTest.java#L1-L66>), [cleanup:141–187](<../../../astral-monitor/src/main/java/com/astral/monitor/job/StatAggregationJob.java#L141-L187>), [flush isolation:63–128](<../../../astral-monitor/src/main/java/com/astral/monitor/job/StatAggregationJob.java#L63-L128>).
- **Cause:** Ingest normalizes platform/version and bounds serialized extra; server error recorder only sets server-side fields rather than client device/OS fields. Duplicate bucket inserts retry increments; retention<=0 skips that deletion category and table failures are isolated; drained bucket failures do not abort all remaining buckets.
- **Counterevidence:** Client error ingestion explicitly retains deviceId/model/OS/IP and free-text message/stack; there is no universal metadata stripping claim. Version shape does not cap distinct valid values. Mapper atomicity/entity serialization remain unverified; a failed already-drained bucket is not requeued. Privacy test is only static counterevidence until run.
- **Untested:** Public ingestion storage values under all event types, DAO atomic updates, failure-retry/durability and sensitive free-text sanitization.
- **Requirement:** [sensitive response/logging guidance](<../../CODING_GUIDE.md#L86-L91>), [retention disable contract](<../../../astral-monitor/src/main/java/com/astral/monitor/job/StatAggregationJob.java#L141-L160>).
- **Next public seam:** Lead invokes public report/ingestion through proxy with harmless marker fields and captures actual inserts; public cleanup with retention0/-1 must make no corresponding delete; public flush with one failed bucket must continue later buckets and expose agreed loss status.

### P08 — Delegated alert/sequence writes use explicit columns and builtin guards
- **Classification:** positive
- **Priority:** positive
- **Evidence:** [sequence whitelist:290–319](<../../../astral-sequence/src/main/java/com/astral/sequence/controller/SequenceConfigController.java#L290-L319>), [builtin update guard:254–257](<../../../astral-sequence/src/main/java/com/astral/sequence/controller/SequenceConfigController.java#L254-L257>), [builtin toggle:366–370](<../../../astral-sequence/src/main/java/com/astral/sequence/controller/SequenceConfigController.java#L366-L370>), [channel/rule whitelists:139–152](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L139-L152>), [rule whitelist:291–310](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java#L291-L310>), [builtin test](<../../../astral-sequence/src/test/java/com/astral/sequence/controller/SequenceConfigControllerTest.java>).
- **Cause:** Visible update wrappers enumerate allowed columns rather than full entity mass assignment; builtin _id writes are rejected. Ordinary writes are intentionally delegated, not automatically super-only.
- **Counterevidence:** R06 stale allowed-column values and R07 commit timing remain; generated validation and all protected fields were not fully audited outside scope.
- **Untested:** Actual permission proxy, future columns, generated constraints and public concurrent writes.
- **Requirement:** [delegation/super/whitelist distinctions](<../../../AGENTS.md#L350-L373>).
- **Next public seam:** Lead runs the existing builtin tests, then calls public update with extra harmless protected-field markers under an ordinary edit subject and captures actual SET columns; verify execution/escalation configuration remains appropriately guarded through actual API proxy.

### P09 — Optional DB/Redis/mail collaborators use absence-aware providers
- **Classification:** positive
- **Priority:** positive
- **Evidence:** [DB absence:52–65](<../../../astral-monitor/src/main/java/com/astral/monitor/service/DatabaseMonitorService.java#L52-L65>), [Redis absence:41–57](<../../../astral-monitor/src/main/java/com/astral/monitor/service/RedisMonitorService.java#L41-L57>), [mail/SMS absence:243–264](<../../../astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java#L243-L264>), [optional sequence Redis dispatch:356–375](<../../../astral-sequence/src/main/java/com/astral/sequence/service/GeneratorFactory.java#L356-L375>).
- **Cause:** Providers allow missing monitoring collaborators to produce absence DTOs or explicit unavailable errors; optional Redis generation is checked before dispatch.
- **Counterevidence:** This does not establish that the whole application can boot without DB/Redis; required DAO/auth dependencies remain outside scope. R05/R11 retain toggle/task concerns.
- **Untested:** Full bootstrap and provider ambiguity/multiple beans.
- **Requirement:** [lazy/optional collaborator guidance](<../../CODING_GUIDE.md#L28-L54>) and [Redis generator toggle boundary](<../../../AGENTS.md#L230-L247>).
- **Next public seam:** Lead invokes public monitor methods with empty providers and public factory Redis request with absent generator; assert distinct absent/unavailable outcomes without any live resource.

### P10 — Current POM/config references visibly declare upgrade integration points
- **Classification:** positive
- **Priority:** positive
- **Evidence:** [common provided servlet:18–33](<../../../astral-common/pom.xml#L18-L33>), [validation/optional Redis:49–59](<../../../astral-sequence/pom.xml#L49-L59>), [Flyway modules:92–99](<../../../astral-server/pom.xml#L92-L99>), [pagination dependency:114–119](<../../../astral-server/pom.xml#L114-L119>), [root annotation processor/tooling](<../../../pom.xml>), [migration loading:45–62](<../../../astral-server/src/main/resources/application.yml#L45-L62>).
- **Cause:** Read POMs declare servlet provided scope, validation/test dependencies, Boot Flyway/PostgreSQL support and explicit MyBatis pagination artifact; root config declares Java25/tooling. Runtime application selects Flyway and disables spring.sql.init.
- **Counterevidence:** Dependency comments/CVE claims are not externally verified; declarations are not resolved dependency-tree/build or effective-config evidence. Migration/schema contents are NOTREAD.
- **Untested:** Maven resolution, generated-source compilation, effective versions, transitive conflicts and real migration execution.
- **Requirement:** [upgrade caveats](<../../../AGENTS.md#L35-L53>) and [generated validation/schema equivalence](<../../CODING_GUIDE.md#L111-L130>).
- **Next public seam:** Lead's serial Maven lane resolves dependency trees/compiles/tests actual modules and checks generated constraints against reviewed migrations; keep network/service bootstrap outside this child review.

## Handoff and suggested next order

1. Reproduce R01/R03 with the existing public factory test seam; qualify R02 using a process-bounded fake-mapper test.
2. Verify R04 through public record/drain, then R05 with isolated false-property context. These do not need live HTTP/DB/Redis.
3. Exercise R06/R07 with ordered public controller/factory calls and an actual proxy/transaction adapter; evaluate API/DATA independently, not from annotations alone.
4. Obtain authorized entity/schema evidence for R08 and lifecycle/SQL evidence for L05; do not upgrade candidates without them.
5. Resolve telemetry/alert delivery intent (R09/R12/L08/L12), then controlled-clock/probe resource boundaries (R10/R11).
6. Finish the 121 NOTREAD files and independent Standards/Spec axes before any whole-core approval or commit. Suggested existing-test selector, **not run here**: `mvn -pl astral-server,astral-sequence,astral-monitor -am -Dtest=GeneratorFactoryTest,SequenceConfigControllerTest,AuthInterceptorPreHandleTest,AuthInterceptorZoneTest,RequestPathSupportTest,StatIngestPrivacyTest,WebhookUrlPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`.

The companion JSON contains the complete188-file ledger, pre/post fingerprints, normalized semantic ranges, fixed-baseline dirty metadata, all35 classified observations and explicit claimsBounds. Artifact hashes/bytes are sent separately to Lead to avoid self-referential report hashes.
