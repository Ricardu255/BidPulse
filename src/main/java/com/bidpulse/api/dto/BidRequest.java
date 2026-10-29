package com.bidpulse.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 竞价请求 —— 流量方在用户打开 App 时发来的一次竞价。
 */
@Data
public class BidRequest {

    /** 广告位编码 */
    @NotBlank(message = "不能为空")
    @Size(max = 32, message = "长度不能超过 32")
    private String slotCode;

    /** 地域编码，如 110000 */
    @NotBlank(message = "不能为空")
    @Size(max = 16, message = "长度不能超过 16")
    private String region;

    /**
     * 年龄段，映射到 ad_target.dim_type=2 的取值。
     * 传原始年龄（如 28）由服务端换算成 25-30，避免调用方各写一套映射。
     */
    @Min(value = 0, message = "应在 0~120 之间")
    @Max(value = 120, message = "应在 0~120 之间")
    private Integer age;

    /** 1 男 2 女 0 未知 */
    @Min(value = 0, message = "只能为 0/1/2")
    @Max(value = 2, message = "只能为 0/1/2")
    private Integer gender;

    /** 年龄段，直接传 ad_target 的原值时优先于此字段 */
    @Size(max = 64, message = "长度不能超过 64")
    private String ageBucket;

    /** 兴趣标签，逗号分隔 */
    @Size(max = 255, message = "长度不能超过 255")
    private String interests;

    /** 广告偏好类目。M1 用它做粗筛，M2 起定向条件走倒排求交 */
    @Size(max = 32, message = "长度不能超过 32")
    private String categoryCode;

    /**
     * 用户标识（可选），用于实时频次控制。
     *
     * <p>不传时不做频次限制：匿名流量没有稳定的用户维度，按 IP / 设备号拼一个伪 ID
     * 更容易误伤（NAT 后的大量用户共享出口 IP）。由流量方在登录态或设备态下传入。
     */
    @Size(max = 64, message = "长度不能超过 64")
    private String userId;

    /**
     * 竞价结果详情，演示时展示给用户看。
     * M9 才会被前端真正用起来，M1 先在接口返回里带着，便于冒烟时肉眼核对。
     */
    @Data
    public static class Result {
        private String requestId;
        /** 是否返回了广告 */
        private Boolean hasAd;
        /** 是否为兜底广告（M5 引入）：true=依赖故障/超时/预算耗尽时返回的保底广告，不计费 */
        private Boolean fallback;
        /** 胜出广告 */
        private AdInfo winner;
        /** 候选集大小，检索返回行数 */
        private Integer candidateCnt;
        /** 定向过滤后数量 */
        private Integer matchCnt;
        /** 出价降序后的前 N 条，M1 为 10 */
        private List<AdInfo> topCandidates;
        /** 计费方式：0 一价 1 二价 2 兜底（兜底不计费，payPrice 为 null） */
        private Integer priceMode;
        /** 实际成交价（元）。二价下低于胜出者出价，演示时与 winner.bid 对照 */
        private java.math.BigDecimal payPrice;
    }

    @Data
    public static class AdInfo {
        private Long adId;
        private Integer advertiserId;
        private String title;
        private String categoryCode;
        private java.math.BigDecimal bid;
    }

}
