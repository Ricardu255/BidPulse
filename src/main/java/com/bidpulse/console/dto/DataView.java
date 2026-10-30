package com.bidpulse.console.dto;

import java.util.List;
import java.util.Map;

/**
 * 数据浏览视图：白名单表 + 列顺序 + 当前页行。
 *
 * <p>{@code rows} 直接使用 JDBC 的列名/别名，前端按 {@code columns} 顺序渲染。
 */
public record DataView(
        String table,
        List<String> columns,
        List<Map<String, Object>> rows) {
}
