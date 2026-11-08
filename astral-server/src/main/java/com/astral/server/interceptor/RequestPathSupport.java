package com.astral.server.interceptor;

/**
 * 请求路径归一化与前缀判定工具。
 *
 * <p><b>为什么不能拿 {@code request.getRequestURI()} 直接 startsWith</b>：Spring MVC 在把请求
 * 路由到控制器之前，会先用 {@code PathContainer} 把路径解析成「段 + 矩阵参数」，
 * 矩阵参数（{@code ;a=b}）被剥离、重复斜杠按段处理，于是
 * <b>「能命中某个控制器的路径集合」严格大于「原始 URI 字符串以该前缀开头」</b>。
 * 认证/授权链路若用原串做前缀比较，就会出现「框架认为是这个区，安全代码认为是另一个区」：</p>
 * <ul>
 *   <li>{@code /api/v1/admin;x/system/user/page} 会命 {@code /api/v1/admin/**} 的控制器，
 *       但原串不满足 {@code startsWith("/api/v1/admin/")} —— 管理端身份门禁被跳过；</li>
 *   <li>{@code //api/v1/admin/system/user/page}、{@code /api/v1/admin/system/user/page/} 同理，
 *       是否等价取决于容器的斜杠规范化，安全判定不该依赖它。</li>
 * </ul>
 * <p>本类把这些形态折叠成唯一规范形态，供安全判定使用：
 * 去查询串/片段、剥矩阵参数、折叠重复斜杠、去尾斜杠（根路径除外）。
 * <b>不做百分号解码</b>——解码会把 {@code %2f} 变成新的分隔符、引入新的绕过面，
 * 而未解码的原样字符串在 Spring 侧也不会被当作路径分隔符。</p>
 *
 * <p><b>放行类判定不要用归一化</b>：归一化会让「更多的写法」满足条件，用在「免认证白名单」上
 * 等于放宽；因此 {@code AuthInterceptor} 对「跳过认证」的分支仍用原始 URI（fail-closed），
 * 只对「必须保护」的分支用归一化路径（fail-safe）。</p>
 */
public final class RequestPathSupport {

    private RequestPathSupport() {}

    /**
     * 归一化请求路径。
     *
     * @param rawUri {@code request.getRequestURI()} 原始值，可为 {@code null}
     * @return 以 {@code /} 开头、无重复斜杠、无矩阵参数、无尾斜杠的路径；空值归一为 {@code "/"}
     */
    public static String normalize(String rawUri) {
        if (rawUri == null || rawUri.isEmpty()) {
            return "/";
        }
        // 截掉查询串与片段（getRequestURI 本不含它们，但本方法是纯函数，防御性处理）
        int end = rawUri.length();
        int query = rawUri.indexOf('?');
        if (query >= 0) {
            end = query;
        }
        int fragment = rawUri.indexOf('#');
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }

        StringBuilder sb = new StringBuilder(end);
        int i = 0;
        while (i < end) {
            char c = rawUri.charAt(i);
            if (c == ';') {
                // 矩阵参数：丢到本段结束（下一个 '/' 之前）
                while (i < end && rawUri.charAt(i) != '/') {
                    i++;
                }
                continue;
            }
            if (c == '/') {
                // 折叠连续 '/'
                if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '/') {
                    sb.append('/');
                }
                i++;
                continue;
            }
            sb.append(c);
            i++;
        }

        if (sb.length() == 0) {
            return "/";
        }
        if (sb.charAt(0) != '/') {
            sb.insert(0, '/');
        }
        if (sb.length() > 1 && sb.charAt(sb.length() - 1) == '/') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 判断路径是否落在某个前缀下（按<b>路径段边界</b>，不是裸 startsWith）。
     *
     * <p>裸 {@code startsWith} 会把兄弟前缀算进来：{@code "/api/v1/app/user"} 的判定
     * 会把 {@code "/api/v1/app/users/..."} 也算作 app 用户区（或反过来漏判），
     * 于是「新增一个相似前缀的接口」就可能默默换掉鉴权区。按段边界比较可杜绝这类漂移。</p>
     *
     * @param path   归一化路径（{@link #normalize} 的输出）
     * @param prefix 前缀，允许带尾斜杠（内部会归一）
     * @return 相等或位于其子路径下返回 {@code true}
     */
    public static boolean under(String path, String prefix) {
        if (path == null || prefix == null || prefix.isEmpty()) {
            return false;
        }
        String normalizedPrefix = prefix;
        if (normalizedPrefix.length() > 1 && normalizedPrefix.charAt(normalizedPrefix.length() - 1) == '/') {
            normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
        }
        if (path.equals(normalizedPrefix)) {
            return true;
        }
        return path.length() > normalizedPrefix.length()
                && path.startsWith(normalizedPrefix)
                && path.charAt(normalizedPrefix.length()) == '/';
    }
}
