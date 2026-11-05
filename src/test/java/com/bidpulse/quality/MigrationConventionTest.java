package com.bidpulse.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移脚本的约定测试：不连数据库，只检查仓库里的文件本身。
 *
 * <p>真正「重复执行是否幂等」由 CI 对 {@code bidpulse_it} 重跑 baseline + 全部迁移覆盖；
 * 这里拦截的是命名、记录、文档、破坏性语句这类代码评审容易漏掉的问题。
 */
class MigrationConventionTest {

    private static final Path MIGRATIONS_DIR = Path.of("sql", "migrations");
    private static final Path BASELINE = Path.of("sql", "all-schema.sql");
    private static final Path MIGRATIONS_README = MIGRATIONS_DIR.resolve("README.md");

    private static final Pattern FILE_NAME =
            Pattern.compile("^(\\d{8}_\\d{2})_[a-z0-9_]+\\.sql$");

    @Test
    @DisplayName("迁移文件名符合 YYYYMMDD_NN_snake_case.sql")
    void fileNamesFollowConvention() throws IOException {
        List<Path> files = migrationFiles();
        assertFalse(files.isEmpty(), "sql/migrations 下至少要有一个迁移脚本");

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            if (!FILE_NAME.matcher(file.getFileName().toString()).matches()) {
                violations.add(file.getFileName().toString());
            }
        }
        assertTrue(violations.isEmpty(), "迁移文件名不符合 YYYYMMDD_NN_snake_case.sql: " + violations);
    }

    @Test
    @DisplayName("版本号唯一，且文件名排序就是执行顺序")
    void versionsAreUnique() throws IOException {
        Set<String> seen = new HashSet<>();
        for (Path file : migrationFiles()) {
            String version = versionOf(file);
            assertTrue(seen.add(version), "重复的迁移版本号: " + version);
        }
        assertTrue(seen.contains("00000000_00"), "bootstrap 迁移 00000000_00 必须存在");
    }

    @Test
    @DisplayName("每个迁移都写入 schema_migration 记录")
    void everyMigrationRecordsItself() throws IOException {
        for (Path file : migrationFiles()) {
            String version = versionOf(file);
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(sql.contains("INSERT IGNORE INTO schema_migration"),
                    file + " 缺少 INSERT IGNORE INTO schema_migration");
            assertTrue(sql.contains("'" + version + "'"),
                    file + " 的迁移记录没有使用文件名版本号 " + version);
        }
    }

    @Test
    @DisplayName("baseline 的 schema_migration 种子包含全部迁移版本")
    void baselineSeedCoversEveryMigration() throws IOException {
        String baseline = Files.readString(BASELINE, StandardCharsets.UTF_8);
        int seedStart = baseline.indexOf("INSERT IGNORE INTO schema_migration");
        assertTrue(seedStart >= 0, "all-schema.sql 必须包含 schema_migration 种子");
        String seed = baseline.substring(seedStart);

        for (Path file : migrationFiles()) {
            String version = versionOf(file);
            assertTrue(seed.contains("('" + version + "'"),
                    "all-schema.sql 的 schema_migration 种子缺少版本 " + version);
        }
    }

    @Test
    @DisplayName("迁移 README 的版本表不遗漏任何脚本")
    void readmeListsEveryMigration() throws IOException {
        String readme = Files.readString(MIGRATIONS_README, StandardCharsets.UTF_8);
        for (Path file : migrationFiles()) {
            String version = versionOf(file);
            assertTrue(readme.contains("| `" + version + "` |"),
                    "sql/migrations/README.md 的版本表缺少 " + version);
        }
    }

    @Test
    @DisplayName("迁移脚本不含 DROP TABLE / TRUNCATE / 无 WHERE 的 DELETE")
    void migrationsAreNotDestructive() throws IOException {
        for (Path file : migrationFiles()) {
            String sql = stripLineComments(Files.readString(file, StandardCharsets.UTF_8));
            String lower = sql.toLowerCase();

            assertFalse(lower.contains("drop table"), file + " 不应 DROP TABLE，删除结构需走备份/回滚流程");
            assertFalse(lower.contains("truncate"), file + " 不应 TRUNCATE，会丢业务数据");
            assertFalse(lower.contains("drop database"), file + " 不应 DROP DATABASE");

            Matcher delete = Pattern.compile("(?i)\\bdelete\\s+from\\s+[^\\s;]+\\s*(;|$)")
                    .matcher(sql);
            assertFalse(delete.find(), file + " 存在没有 WHERE 的 DELETE");
        }
    }

    @Test
    @DisplayName("bootstrap 负责创建 schema_migration 表")
    void bootstrapCreatesMigrationTable() throws IOException {
        String bootstrap = Files.readString(MIGRATIONS_DIR.resolve("00000000_00_bootstrap.sql"),
                StandardCharsets.UTF_8);
        assertTrue(bootstrap.contains("CREATE TABLE IF NOT EXISTS schema_migration"),
                "bootstrap 必须可重入地创建 schema_migration");
    }

    private List<Path> migrationFiles() throws IOException {
        try (Stream<Path> stream = Files.list(MIGRATIONS_DIR)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }

    private String versionOf(Path file) {
        Matcher matcher = FILE_NAME.matcher(file.getFileName().toString());
        assertTrue(matcher.matches(), "无法解析迁移版本号: " + file);
        return matcher.group(1);
    }

    /** 去掉 `--` 行注释，避免注释里提到 DROP TABLE 时误报 */
    private String stripLineComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment >= 0 ? line.substring(0, comment) : line;
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    @DisplayName("迁移目录存在，避免测试在错误工作目录下静默通过")
    void migrationDirectoryExists() {
        assertTrue(Files.isDirectory(MIGRATIONS_DIR), "请在项目根目录运行测试");
        assertTrue(Files.isRegularFile(BASELINE), "请在项目根目录运行测试");
    }
}
