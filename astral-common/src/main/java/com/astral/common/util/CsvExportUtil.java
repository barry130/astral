package com.astral.common.util;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/**
 * CSV 导出工具（管理端数据导出统一出口）
 *
 * <p>安全要点：</p>
 * <ul>
 *   <li>输出带 UTF-8 BOM，Excel 直接打开不乱码</li>
 *   <li>值内引号/逗号/换行按 RFC 4180 转义</li>
 *   <li>公式注入防护：以 = + - @ 开头的单元格前置单引号（Excel 会把这类值当公式执行，
 *       是 CSV 导出的经典注入面）</li>
 * </ul>
 */
public final class CsvExportUtil {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CsvExportUtil() {
    }

    /**
     * 构建 CSV 内容
     *
     * @param headers   表头
     * @param rows      数据行
     * @param extractor 行 → 单元格取值（按 headers 顺序）
     * @return 带 BOM 的 CSV 字节
     */
    public static <T> byte[] build(String[] headers, List<T> rows, Function<T, Object[]> extractor) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headers.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(headers[i]));
        }
        sb.append("\r\n");
        if (rows != null) {
            for (T row : rows) {
                Object[] cells = extractor.apply(row);
                for (int i = 0; i < cells.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(escape(toDisplay(cells[i])));
                }
                sb.append("\r\n");
            }
        }
        // BOM：Excel 识别 UTF-8
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    private static String toDisplay(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDateTime ldt) {
            return TS.format(ldt);
        }
        return String.valueOf(value);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        // 公式注入防护：Excel/WPS 会把 = + - @ 开头的单元格当公式
        if (!v.isEmpty() && (v.charAt(0) == '=' || v.charAt(0) == '+' || v.charAt(0) == '-' || v.charAt(0) == '@')) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
