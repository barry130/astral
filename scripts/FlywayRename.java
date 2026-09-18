import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次性手术工具：Flyway 版本号改为日期序号命名（V1/2/3 -> V20260914001/002/003）后，
 * 同步更新 flyway_schema_history 中已应用记录的版本号（校验和来自文件内容，改名不受影响；
 * 描述保持不变）。凭据从 run-backend.local.bat 读取，不打印。
 * 用法：java FlywayRename <astral仓库根目录>（编译时 classpath 带 postgresql 驱动）
 */
public class FlywayRename {
    public static void main(String[] args) throws Exception {
        Path bat = Path.of(args[0], "run-backend.local.bat");
        Map<String, String> env = new HashMap<>();
        Pattern p = Pattern.compile("set \"([A-Z_]+)=(.*)\"");
        for (String line : Files.readAllLines(bat, StandardCharsets.UTF_8)) {
            Matcher m = p.matcher(line.trim());
            if (m.matches()) env.put(m.group(1), m.group(2));
        }
        String url = env.get("SPRING_DATASOURCE_URL");
        String user = env.get("SPRING_DATASOURCE_USERNAME");
        String pass = env.get("SPRING_DATASOURCE_PASSWORD");
        if (url == null || user == null || pass == null) throw new IllegalStateException("local.bat 缺少数据库配置");

        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(false);
            System.out.println("=== 修改前 ===");
            dump(c);
            // 版本号手术：1/2/3 -> 20260914001/002/003（描述不变，避免 description mismatch）
            String[][] updates = {
                {"1", "20260914001"}, {"2", "20260914002"}, {"3", "20260914003"},
            };
            int total = 0;
            for (String[] u : updates) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE astral.flyway_schema_history SET version = ? WHERE version = ?")) {
                    ps.setString(1, u[1]);
                    ps.setString(2, u[0]);
                    total += ps.executeUpdate();
                }
            }
            System.out.println("更新行数: " + total);
            if (total != 3) {
                c.rollback();
                throw new IllegalStateException("期望更新 3 行，实际 " + total + "，已回滚");
            }
            c.commit();
            System.out.println("=== 修改后 ===");
            dump(c);
        }
    }

    private static void dump(Connection c) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT installed_rank, version, description, type, checksum, success "
                 + "FROM astral.flyway_schema_history ORDER BY installed_rank")) {
            while (rs.next()) {
                System.out.printf("rank=%d version=%s desc=%s type=%s checksum=%s success=%s%n",
                    rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getBoolean(6));
            }
        }
    }
}
