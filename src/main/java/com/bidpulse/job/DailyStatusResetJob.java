package com.bidpulse.job;

import com.bidpulse.infra.persistence.AdMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 日预算复位任务。
 *
 * <p><b>为什么需要它</b>：预算耗尽时 {@code ExhaustedBuffer} 会把 {@code ad.status} 写成 3，
 * 检索 SQL 的 {@code WHERE status = 1} 从此排除该广告。但预算本身是按天分桶的
 * （{@code budget:{adId}:{yyyyMMdd}}），跨天后 Redis 里已经是新的一天、额度自动恢复；
 * 如果 DB 状态不复位，广告会在第一天耗尽后<b>永久退出投放</b>，投放量逐日单调衰减。
 *
 * <p><b>为什么只复位「昨天及更早」</b>：条件 {@code updated_at < CURDATE()} 卡在自然日边界，
 * 只放开昨天之前被标记的广告，不会把当天刚耗尽（或测试里手工置 3）的数据提前放回投放。
 *
 * <p>复位失败只记日志、不重试补偿：最坏结果是这些广告今天仍检索不到，下一次调度会再扫一遍。
 */
@Slf4j
@Component
public class DailyStatusResetJob {

    private final AdMapper adMapper;

    public DailyStatusResetJob(AdMapper adMapper) {
        this.adMapper = adMapper;
    }

    /** 每天 00:05 把「昨天及更早」标记为已耗尽的广告复位成在投 */
    @Scheduled(cron = "${bidpulse.budget.reset.cron:0 5 0 * * ?}")
    public void resetExhaustedAds() {
        try {
            int rows = adMapper.resetExhaustedBeforeToday();
            log.info("日预算复位完成：status 3 -> 1 共 {} 条", rows);
        } catch (Exception e) {
            log.warn("日预算复位失败（下次调度重试）: {}", e.getMessage());
        }
    }
}
