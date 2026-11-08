package com.astral.server.migration;

import java.sql.SQLException;
import java.util.List;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorDetails;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.exception.FlywayBlockStatementExecutionException;
import org.springframework.stereotype.Component;

/**
 * Corrects three conditional seed failures before their historical statements execute.
 * Published migrations and checksums remain unchanged. Replacements use the migration's
 * connection and transaction; a failed replacement must fail and roll back the migration.
 */
@Component
public final class HistoricalSeedCompatibilityCallback implements Callback {
    private static final String PERMISSION = """
            INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort, status)
            SELECT COALESCE(MAX(id), 0) + 1, 'qt_tester', '轻听测试', NULL, NULL, 0, 41, 1
            FROM sys_permission
            WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'qt_tester')
            """;
    private static final String CHANNEL = """
            insert into sys_notify_rule_channel
                (id, rule_id, channel, template_id, enabled, daily_limit, daily_limit_scope, platform)
            select (select coalesce(max(id), 0) from sys_notify_rule_channel) + 1,
                   r.id, 'EMAIL',
                   (select t.id from sys_mail_template t
                     where t.scene = 'changePasswordByEmail' and t.channel = 'EMAIL' order by t.id limit 1),
                   1, 2, 'RECIPIENT', 'all'
            from sys_notify_rule r
            where r.event_code = 'changePasswordByEmail'
              and not exists (select 1 from sys_notify_rule_channel c where c.rule_id = r.id and c.channel = 'EMAIL')
            """;
    private static final String RECIPIENT = """
            insert into sys_notify_rule_recipient (id, channel_id, recipient_type, recipient_value)
            select (select coalesce(max(id), 0) from sys_notify_rule_recipient) + 1,
                   c.id, 'PAYLOAD_FIELD', 'email'
            from sys_notify_rule_channel c
            join sys_notify_rule r on r.id = c.rule_id
            where r.event_code = 'changePasswordByEmail' and c.channel = 'EMAIL'
              and not exists (select 1 from sys_notify_rule_recipient x where x.channel_id = c.id)
            """;
    private static final List<Replacement> REPLACEMENTS = List.of(
            new Replacement(
                    "20260926001",
                    "V20260926001__qt_permission_codes.sql",
                    "INSERT INTO sys_permission ",
                    PERMISSION,
                    """
                    INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort, status)
                    SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_permission) + 1,
                           'qt_tester', '轻听测试', NULL, NULL, 0, 41, 1
                    WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'qt_tester')
                    """),
            new Replacement(
                    "20261029001",
                    "V20261029001__notify_rule_channel_split.sql",
                    "insert into sys_notify_rule_channel ",
                    CHANNEL,
                    CHANNEL.replace(
                            "from sys_notify_rule_channel) + 1,",
                            "from sys_notify_rule_channel) + row_number() over (order by r.id),")),
            new Replacement(
                    "20261029001",
                    "V20261029001__notify_rule_channel_split.sql",
                    "insert into sys_notify_rule_recipient ",
                    RECIPIENT,
                    RECIPIENT.replace(
                            "from sys_notify_rule_recipient) + 1,",
                            "from sys_notify_rule_recipient) + row_number() over (order by c.id),")));

    @Override
    public boolean supports(Event event, Context context) {
        if (event != Event.BEFORE_EACH_MIGRATE_STATEMENT
                || context.getMigrationInfo() == null
                || context.getMigrationInfo().getVersion() == null) {
            return false;
        }
        return REPLACEMENTS.stream().anyMatch(replacement -> replacement.matchesMigration(context));
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (!supports(event, context) || context.getStatement() == null) {
            return;
        }
        String sql = normalize(context.getStatement().getSql());
        for (Replacement replacement : REPLACEMENTS) {
            if (!replacement.matchesMigration(context) || !sql.startsWith(replacement.prefix())) {
                continue;
            }
            if (!sql.equals(normalize(replacement.original()))) {
                throw new FlywayException("Historical seed statement changed: " + replacement.script());
            }
            try (var statement = context.getConnection().createStatement()) {
                statement.execute(replacement.corrected());
            } catch (SQLException e) {
                throw new FlywayException("Historical seed correction failed: " + replacement.script(), e);
            }
            // Flyway's public exception skips only this statement, after its safe equivalent succeeded.
            String message = "Applied historical seed correction: " + replacement.script();
            throw new FlywayBlockStatementExecutionException(new ErrorDetails(CoreErrorCode.ERROR, message), message);
        }
    }

    @Override
    public String getCallbackName() {
        return "historical-seed-compatibility";
    }

    private static String normalize(String sql) {
        // The three fixed statements have only leading full-line comments, no embedded comments.
        return sql.replaceAll("(?m)^\\s*--[^\\r\\n]*", "")
                .strip()
                .replaceFirst(";\\s*$", "")
                .replaceAll("\\s+", " ");
    }

    private record Replacement(String version, String script, String prefix, String original, String corrected) {
        boolean matchesMigration(Context context) {
            return version.equals(context.getMigrationInfo().getVersion().getVersion())
                    && script.equals(context.getMigrationInfo().getScript());
        }
    }
}
