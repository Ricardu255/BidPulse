package com.bidpulse.console.query;

import com.bidpulse.api.BizException;
import com.bidpulse.api.dto.BidTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 决策轨迹只读查询。
 *
 * <p>轨迹随 {@code bid_log} 异步落库，可能比竞价应答晚到几百毫秒；控制台在收到
 * 竞价结果后立即拉取可能短暂 404，前端按「稍后重试」处理即可。
 *
 * <p>与概览一致，查询走控制台专用只读连接池，不占用竞价主池。
 */
@Slf4j
@Service
public class ConsoleBidTraceService {

    /** requestId 由服务端生成，固定 32 位小写十六进制（UUID 去掉连字符） */
    private static final Pattern REQUEST_ID = Pattern.compile("[0-9a-f]{32}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ConsoleBidTraceService(@Qualifier("consoleReadJdbcTemplate") JdbcTemplate jdbc,
                                  ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public BidTrace trace(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw BizException.badRequest("requestId 必须是 32 位小写十六进制");
        }

        String json;
        try {
            json = jdbc.queryForObject(
                    "SELECT trace_json FROM bid_log WHERE request_id = ?",
                    String.class, requestId);
        } catch (EmptyResultDataAccessException e) {
            throw BizException.notFound("竞价记录不存在：" + requestId);
        }

        if (json == null || json.isBlank()) {
            throw BizException.notFound("该竞价没有决策轨迹（仅控制台发起的竞价会采集）");
        }

        try {
            return objectMapper.readValue(json, BidTrace.class);
        } catch (Exception e) {
            // 轨迹是诊断数据：解析失败说明写入格式坏了，返回 503 而不是把脏 JSON 透给前端
            log.warn("决策轨迹 JSON 解析失败 requestId={}: {}", requestId, e.getMessage());
            throw BizException.unavailable("决策轨迹解析失败");
        }
    }
}
