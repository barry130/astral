# Task-8 compilation coordination

## Current source lease

The Lead owns task-8 at revision 3 and delegates only the Qt, feedback, CMS, AI and plugin-runtime main/test Java directories and the backend-domains report to the independent task-8 agent. These directories are writable during this source stage. Storage, shared modules, schema files, migrations, root build, authentication and frontends remain outside this lease.

No plugin or whole-server Maven invocation is running. The Lead's isolated authentication reactor (`-pl astral-auth -am`) does not compile task-8 plugin sources. The agent may continue source changes until it explicitly announces a stage handback and freezes its own directories. A plugin/server build must not overlap task-8 source changes.

## Handback and regression format

Report exact changed paths, public production-boundary test class names, test method counts, and any dependency or schema request. The Lead runs regression classes with Java 25 and current upstream modules, for example:

```powershell
mvn -pl astral-plugin -am -DskipCodeGen=true -Dtest=QtVersionServiceTest,PluginLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Those class names are command examples, not claims that such classes exist. Use the actual delivered names. Do not run Maven or global formatting from task-8. Freeze Java source and tests before the Lead starts a plugin/server compile, and resume only after collected output releases the freeze.

## Established constraints

Use Java 25, Spring Boot 4.1.2, Jackson 3 and MyBatis-Plus 3.5.17 with the current BOM and explicit imports. Do not add dependencies or alter versions without Lead integration. No remote push, commit, real database, real credentials, production endpoints, email, SMS or uploads. Memory inventories and old handbacks are not reviewed coverage. The old backend-domains summary currently enumerates nineteen candidates (10 Qt + 2 feedback + 2 CMS + 3 AI + 2 plugin); reconcile any eighteen/nineteen count before publishing its replacement.

Team messaging cannot address the independent subagent ID in this session. This file provides a filesystem coordination fallback; source safety does not depend on an unacknowledged message.
