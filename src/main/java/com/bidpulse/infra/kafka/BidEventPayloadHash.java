package com.bidpulse.infra.kafka;

import com.bidpulse.event.BidEvent;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 竞价事件载荷指纹：把 {@link BidEvent} 编码成稳定、无歧义的字节串后取 SHA-256。
 *
 * <p><b>为什么不能直接对原始 JSON 取哈希</b>：同一语义的 JSON 可能有不同写法
 * （字段顺序、空白、{@code 1.2} vs {@code 1.20}）。如果按原始字节比较，
 * 正常的重投会被误判成「载荷冲突」，把事件错误地打进 DLT。
 *
 * <p><b>编码规则</b>：
 * <ul>
 *   <li>字段顺序固定，新增字段必须同步修改本类（见 {@link #encode(BidEvent)}）；</li>
 *   <li>字符串用「长度 + 内容」编码，避免拼接歧义；{@code null} 与空串不同；</li>
 *   <li>{@link BigDecimal} 先 {@code stripTrailingZeros().toPlainString()}，
 *       所以 {@code 0.0200} 与 {@code 0.02} 视为同一金额；</li>
 *   <li>前缀 {@code v1} 表示编码版本；改变编码语义时必须升级版本。</li>
 * </ul>
 *
 * <p><b>契约</b>：同一个 {@code eventId} 必须对应同一份业务载荷。生产端若要做字段
 * enrichment，必须先升级本类或剔除不影响语义的字段，否则消费端会按冲突转 DLT。
 */
public final class BidEventPayloadHash {

    /** 编码版本；改变字段集合或编码方式时递增。 */
    private static final String VERSION = "v1";

    private BidEventPayloadHash() {
    }

    /** 返回 64 位小写十六进制 SHA-256。 */
    public static String sha256Hex(BidEvent event) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法；走到这里说明运行环境被裁剪过。
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
        byte[] hash = digest.digest(encode(event).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    /**
     * 固定顺序编码全部业务字段。
     *
     * <p>字段增删、顺序调整都会改变历史哈希，属于不兼容变更：必须同时升级
     * {@link #VERSION} 并安排 processed_event 回填/清空策略。
     */
    static String encode(BidEvent event) {
        StringBuilder sb = new StringBuilder(256).append(VERSION);
        append(sb, event.getEventId());
        append(sb, event.getRequestId());
        append(sb, event.getTs());
        append(sb, event.getRegion());
        append(sb, event.getUserId());
        append(sb, event.getAdId());
        append(sb, event.getAdvertiserId());
        append(sb, event.getWinBid());
        append(sb, event.getPayPrice());
        append(sb, event.isFallback());
        append(sb, event.isHasAd());
        append(sb, event.getPriceMode());
        return sb.toString();
    }

    private static void append(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("|~");
            return;
        }
        sb.append("|s").append(value.length()).append(':').append(value);
    }

    private static void append(StringBuilder sb, Long value) {
        if (value == null) {
            sb.append("|~");
            return;
        }
        sb.append("|n").append(value);
    }

    private static void append(StringBuilder sb, Integer value) {
        if (value == null) {
            sb.append("|~");
            return;
        }
        sb.append("|n").append(value);
    }

    private static void append(StringBuilder sb, long value) {
        sb.append("|n").append(value);
    }

    private static void append(StringBuilder sb, int value) {
        sb.append("|n").append(value);
    }

    private static void append(StringBuilder sb, boolean value) {
        sb.append("|b").append(value ? '1' : '0');
    }

    private static void append(StringBuilder sb, BigDecimal value) {
        if (value == null) {
            sb.append("|~");
            return;
        }
        // 金额只比较数值：1.20 与 1.2、0.0200 与 0.02 必须得到同一指纹。
        String plain = value.stripTrailingZeros().toPlainString();
        sb.append("|d").append(plain.length()).append(':').append(plain);
    }
}
