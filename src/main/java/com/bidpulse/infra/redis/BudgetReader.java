package com.bidpulse.infra.redis;

import com.bidpulse.config.BidPulseProperties;
import com.bidpulse.entity.Ad;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.bidpulse.infra.redis.BudgetAmounts.parse;
import static com.bidpulse.infra.redis.BudgetAmounts.parseCentsOrNull;
import static com.bidpulse.infra.redis.BudgetAmounts.toCents;
import static com.bidpulse.infra.redis.BudgetKeys.DATE_FMT;
import static com.bidpulse.infra.redis.BudgetKeys.budgetKey;
import static com.bidpulse.infra.redis.BudgetKeys.freqKey;
import static com.bidpulse.infra.redis.BudgetKeys.spentKey;

/**
 * 预算/频次的只读查询与惰性初始化。
 *
 * <p>拆出来的目的：控制台快照和二价定价读取都不应该在 {@link BudgetService} 里和
 * “扣减 / 播种 / 对账修复”混在一起；这里的每个 public 方法除了 {@link #getBalance} 的
 * 惰性播种外都不写状态。
 */
final class BudgetReader {

    private final StringRedisTemplate redis;
    private final BidPulseProperties props;

    BudgetReader(StringRedisTemplate redis, BidPulseProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 读取某广告当天的预算状态；只读，不触发播种。 */
    public BudgetService.BudgetSnapshot snapshot(Long adId, BigDecimal dailyBudget) {
        String date = LocalDate.now().format(DATE_FMT);
        String budgetRaw = redis.opsForValue().get(budgetKey(adId, date));
        String spentRaw = redis.opsForValue().get(spentKey(adId, date));
        Long ttl = redis.getExpire(budgetKey(adId, date), TimeUnit.SECONDS);

        Long remaining = parseCentsOrNull(budgetRaw);
        Long spent = parseCentsOrNull(spentRaw);
        if (remaining == null && spent != null && dailyBudget != null) {
            remaining = Math.max(0, toCents(dailyBudget) - spent);
        }
        return new BudgetService.BudgetSnapshot(remaining, spent,
                ttl == null ? Long.valueOf(-1L) : ttl, budgetRaw != null);
    }

    /** 读取某用户当前小时对某广告的频次计数；键不存在时不创建空 Hash。 */
    public BudgetService.FrequencySnapshot snapshotFrequency(String userId, Long adId) {
        String key = freqKey(userId, LocalDateTime.now());
        Object raw = redis.opsForHash().get(key, String.valueOf(adId));
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        Long count = raw == null ? null : parseCentsOrNull(String.valueOf(raw));
        return new BudgetService.FrequencySnapshot(count,
                Math.max(1, props.getFrequency().getMaxPerHour()),
                ttl == null ? Long.valueOf(-1L) : ttl);
    }

    /** 查询当前余额，不存在则按广告日预算初始化（惰性播种）。 */
    public BigDecimal getBalance(Long adId, BigDecimal dailyBudget) {
        LocalDate date = LocalDate.now();
        String d = date.format(DATE_FMT);
        String v = redis.opsForValue().get(budgetKey(adId, d));
        if (v == null) {
            initIfAbsent(adId, dailyBudget, d);
            v = redis.opsForValue().get(budgetKey(adId, d));
        }
        return parse(v == null ? String.valueOf(toCents(dailyBudget)) : v);
    }

    /**
     * <b>只读</b>批量查询当日剩余预算，不做任何播种（key 不存在时按「今天还没消费」处理）。
     *
     * <p>一次 {@code MGET} 取回全部 key（{@code budget}/{@code spent} 交替排列），
     * 避免 fall-through 时对同一批候选反复 GET；任何读取异常都上抛给调用方。
     */
    public Map<Long, BigDecimal> peekBalances(List<Ad> ads) {
        if (ads == null || ads.isEmpty()) {
            return Map.of();
        }
        String date = LocalDate.now().format(DATE_FMT);
        List<String> keys = new ArrayList<>(ads.size() * 2);
        for (Ad ad : ads) {
            keys.add(budgetKey(ad.getId(), date));
            keys.add(spentKey(ad.getId(), date));
        }
        List<String> values = redis.opsForValue().multiGet(keys);

        Map<Long, BigDecimal> out = new HashMap<>(ads.size());
        for (int i = 0; i < ads.size(); i++) {
            String bal = (values != null && i * 2 < values.size()) ? values.get(i * 2) : null;
            String spent = (values != null && i * 2 + 1 < values.size()) ? values.get(i * 2 + 1) : null;
            Ad ad = ads.get(i);

            BigDecimal remain;
            if (bal != null) {
                remain = parse(bal);
            } else if (spent != null && ad.getDailyBudget() != null) {
                long remainCents;
                try {
                    remainCents = toCents(ad.getDailyBudget()) - Long.parseLong(spent);
                } catch (NumberFormatException e) {
                    // 脏数据：退化为满额，至少不会把脏值当成「已耗尽」
                    remainCents = toCents(ad.getDailyBudget());
                }
                remain = parse(String.valueOf(Math.max(0, remainCents)));
            } else if (ad.getDailyBudget() != null) {
                remain = ad.getDailyBudget();
            } else {
                // 日预算缺失的候选无法计费，按 0 处理（不参与定价）
                remain = BigDecimal.ZERO;
            }
            out.put(ad.getId(), remain);
        }
        return out;
    }

    private void initIfAbsent(Long adId, BigDecimal dailyBudget, String date) {
        redis.opsForValue().setIfAbsent(budgetKey(adId, date),
                String.valueOf(toCents(dailyBudget)),
                Duration.ofSeconds(Math.max(1, props.getBudget().getKeyTtlSeconds())));
    }
}
