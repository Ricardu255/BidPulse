package com.bidpulse.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 源码卫生检查：这些写法在 demo 里能跑，但在生产里会绕过日志与异常处理。
 *
 * <p>只扫描 {@code src/main/java}；测试代码允许用 {@code System.out} 做临时诊断。
 */
class SourceHygieneTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "System\\s*\\.\\s*(out|err)\\s*\\.\\s*print"
                    + "|\\.printStackTrace\\s*\\(");

    @Test
    @DisplayName("生产代码不使用 System.out/err 或 printStackTrace")
    void noConsoleOutputOrRawStackTraces() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(MAIN_SOURCES)) {
            files = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertFalse(files.isEmpty(), "没有扫描到任何 Java 源文件，工作目录可能不对");

        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (FORBIDDEN.matcher(lines.get(i)).find()) {
                    violations.add(MAIN_SOURCES.relativize(file) + ":" + (i + 1));
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "生产代码应使用 SLF4J 日志并保留异常上下文，发现: " + violations);
    }
}
