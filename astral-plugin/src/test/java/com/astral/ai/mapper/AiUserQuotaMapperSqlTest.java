package com.astral.ai.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：{@link AiUserQuotaMapper#addWithinLimits} 的三个上限参数必须显式声明 jdbcType。
 *
 * <p>背景（线上真实故障）：三个上限都允许为 null（表示该维度不限），而 MyBatis 对 null 参数
 * 默认按 {@code JdbcType.OTHER} 绑定。PostgreSQL 使用强类型协议，{@code (? IS NULL OR ...)}
 * 这种裸参数没有比较对象可供推断类型，服务端会直接拒绝执行：</p>
 *
 * <pre>
 * ERROR: could not determine data type of parameter $5
 * </pre>
 *
 * <p>该错误只在「上限为 null」时触发，即 settle / release 的调用路径必然失败，
 * 因此更容易被忽略——额度结算与释放会静默记 warn，计数永远累加不上。
 * 修复方式是为每个上限参数补 {@code jdbcType}，让驱动在 null 时也带上明确类型。</p>
 *
 * <p>本测试锁定修复，防止后续有人「简化」掉 jdbcType 而重新引入该故障。</p>
 */
class AiUserQuotaMapperSqlTest {

    /** 上限参数 -> 其列在 PostgreSQL 中的类型 */
    private static final String[][] NULLABLE_LIMITS = {
            {"reqLimit", "INTEGER"},
            {"inLimit", "BIGINT"},
            {"outLimit", "BIGINT"},
    };

    private static String addWithinLimitsSql() throws Exception {
        Method method = AiUserQuotaMapper.class.getMethod("addWithinLimits",
                Long.class, int.class, long.class, long.class,
                Integer.class, Long.class, Long.class);
        Update update = method.getAnnotation(Update.class);
        assertTrue(update != null, "addWithinLimits 应当由 @Update 驱动");
        return String.join("\n", update.value());
    }

    @Test
    void everyOccurrenceOfNullableLimitDeclaresJdbcType() throws Exception {
        String sql = addWithinLimitsSql();
        for (String[] entry : NULLABLE_LIMITS) {
            String param = entry[0];
            String jdbcType = entry[1];
            Pattern any = Pattern.compile("#\\{" + param + "\\b[^}]*}");
            Matcher matcher = any.matcher(sql);
            int occurrences = 0;
            while (matcher.find()) {
                occurrences++;
                String placeholder = matcher.group();
                assertTrue(placeholder.contains("jdbcType=" + jdbcType),
                        "占位符 " + placeholder + " 必须声明 jdbcType=" + jdbcType
                                + "，否则 PostgreSQL 无法推断 null 参数类型");
            }
            // 每个上限在 SQL 中出现两次：一次 IS NULL 判断、一次阈值比较
            assertEquals(2, occurrences,
                    param + " 应恰好出现 2 次；若 SQL 结构变了，请同步复核 jdbcType");
        }
    }

    @Test
    void noBareNullablePlaceholderRemains() throws Exception {
        String sql = addWithinLimitsSql();
        for (String[] entry : NULLABLE_LIMITS) {
            String param = entry[0];
            assertFalse(sql.contains("#{" + param + "}"),
                    "存在裸占位符 #{" + param + "}；必须写成 #{" + param + ",jdbcType=...}");
        }
    }

    @Test
    void nullLimitsMeanUnlimited() throws Exception {
        String sql = addWithinLimitsSql();
        for (String[] entry : NULLABLE_LIMITS) {
            String param = entry[0];
            assertTrue(sql.contains("#{" + param + ",jdbcType=" + entry[1] + "} IS NULL"),
                    "上限为 null 时应视为不限，需要 " + param + " IS NULL 短路");
        }
    }
}
