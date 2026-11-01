package com.bidpulse.infra.redis;

import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.entity.Ad;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.bidpulse.infra.redis.BudgetAmounts.toCents;
import static com.bidpulse.infra.redis.BudgetKeys.DATE_FMT;
import static com.bidpulse.infra.redis.BudgetKeys.budgetKey;

/**
 * 检索阶段批量预热预算 key。
 *
 * <p>本地 {@code warmedEpochDay} 按天去重；只有 pipeline 成功后才会把广告记为已预热，
 * 避免 Redis 抖动导致当天不再重试。预热失败不影响正确性，计费阶段 Lua 仍会惰性播种。
 */
final class BudgetWarmup {

    private static final int WARMED_CACHE_MAX = 1_000_000;

    private final StringRedisTemplate redis;
    private final BidPulseProperties props;

    /** 已预热的广告 + 预热日期（epochDay）。 */
    private final Map<Long, Integer> warmedEpochDay = new ConcurrentHashMap<>();

    BudgetWarmup(StringRedisTemplate redis, BidPulseProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void warm(List<Ad> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        int today = (int) LocalDate.now().toEpochDay();

        if (warmedEpochDay.size() > WARMED_CACHE_MAX) {
            warmedEpochDay.clear();
        }

        List<Ad> toWarm = new ArrayList<>(candidates.size());
        for (Ad ad : candidates) {
            if (ad == null || ad.getId() == null || ad.getDailyBudget() == null) {
                continue;
            }
            Integer warmedDay = warmedEpochDay.get(ad.getId());
            if (!Integer.valueOf(today).equals(warmedDay)) {
                toWarm.add(ad);
            }
        }
        if (toWarm.isEmpty()) {
            return;
        }

        String date = LocalDate.now().format(DATE_FMT);
        Duration ttl = Duration.ofSeconds(Math.max(1, props.getBudget().getKeyTtlSeconds()));
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) {
                ValueOperations<String, String> ops = operations.opsForValue();
                for (Ad ad : toWarm) {
                    ops.setIfAbsent(budgetKey(ad.getId(), date),
                            String.valueOf(toCents(ad.getDailyBudget())), ttl);
                }
                return null;
            }
        });

        for (Ad ad : toWarm) {
            warmedEpochDay.put(ad.getId(), today);
        }
    }
}
