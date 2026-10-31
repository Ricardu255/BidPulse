package com.bidpulse.admin;

import com.bidpulse.api.BizException;
import com.bidpulse.api.PageResult;
import com.bidpulse.api.dto.AdQuery;
import com.bidpulse.api.dto.AdUpsertRequest;
import com.bidpulse.api.dto.TargetItem;
import com.bidpulse.entity.Ad;
import com.bidpulse.infra.persistence.AdMapper;
import com.bidpulse.infra.redis.BudgetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告管理端服务：服务 {@code /admin/ad} 这批 CRUD 接口。
 *
 * <p><b>与检索路径的关系</b>：这里只写 {@code ad} 与 {@code ad_target} 两张表，
 * 不触碰竞价链路。写完之后，下一次竞价检索自然就能命中新数据——
 * 因为检索是<b>直读 MySQL</b> 而非走内存索引，所以这里天然没有「索引更新延迟」的问题。
 */
@Slf4j
@Service
public class AdAdminService {

    /** 1 在投 */
    private static final int STATUS_ACTIVE = 1;
    /** 2 暂停 */
    private static final int STATUS_PAUSED = 2;

    private final AdMapper adMapper;
    private final BudgetService budgetService;

    public AdAdminService(AdMapper adMapper, BudgetService budgetService) {
        this.adMapper = adMapper;
        this.budgetService = budgetService;
    }

    /**
     * 新建广告。
     *
     * <p><b>为什么必须校验定向非空</b>：{@code findCandidates} 的候选是靠
     * {@code JOIN ad_target} 求交出来的，一条没有定向条件的广告永远 JOIN 不上，
     * 等于建了一条「死广告」——建完就查不到、投不出去。所以在入口就拦掉。
     *
     * <p>ad 与 ad_target 必须在**同一事务**里落库，否则中途失败会留下孤儿广告。
     */
    @Transactional
    public Long create(AdUpsertRequest req) {
        if (req.getAdvertiserId() == null) {
            throw BizException.badRequest("advertiserId 不能为空");
        }
        if (req.getTargets() == null || req.getTargets().isEmpty()) {
            throw BizException.badRequest("新建广告必须至少带 1 条定向条件，否则该广告永远不会被检索命中");
        }
        List<TargetItem> targets = normalizeTargets(req.getTargets());

        Ad ad = new Ad();
        ad.setAdvertiserId(req.getAdvertiserId());
        ad.setTitle(req.getTitle());
        ad.setCreativeUrl(req.getCreativeUrl());
        ad.setCategoryCode(req.getCategoryCode());
        ad.setBid(req.getBid());
        ad.setDailyBudget(req.getDailyBudget());
        ad.setStatus(req.getStatus() == null ? STATUS_ACTIVE : req.getStatus());

        adMapper.insertAd(ad);                                  // 自增主键回填到 ad.id
        adMapper.batchInsertTargets(ad.getId(), targets);

        log.info("新建广告 id={} advertiserId={} 定向 {} 条",
                ad.getId(), ad.getAdvertiserId(), targets.size());
        return ad.getId();
    }

    /** 详情：广告本体 + 它的全部定向条件 */
    public Map<String, Object> detail(Long id) {
        Ad ad = mustGet(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ad", ad);
        out.put("targets", adMapper.listTargetsByAdId(id));
        return out;
    }

    /** 列表：先查总数再查当页，保证 total 与 list 出自同一份筛选条件 */
    public PageResult<Ad> page(AdQuery q) {
        long total = adMapper.countBy(q);
        List<Ad> list = (total == 0) ? List.of() : adMapper.selectPage(q);
        return PageResult.of(total, q.safePageNum(), q.safePageSize(), list);
    }

    /**
     * 更新广告。
     *
     * <p><b>PUT 是全量替换</b>：{@code title / creativeUrl / categoryCode / bid / dailyBudget}
     * 由 DTO 校验强制必填，只有 {@code targets} 支持三态语义。
     *
     * <p><b>targets 的三态语义</b>（与请求体的 null 语义对齐）：
     * <ul>
     *   <li>{@code null} —— 不动定向，只改 ad 本体字段；</li>
     *   <li>空数组 —— 清空全部定向（会让广告检索不到，慎用）；</li>
     *   <li>非空 —— 全删重插，等价于「覆盖式替换」。</li>
     * </ul>
     * 全删重插而非增量 diff，是为了避免定向的增删改三种情况各写一套 SQL；
     * {@code DELETE FROM ad_target WHERE ad_id = ?} 走
     * {@code uk_ad_dim(ad_id, dim_type, dim_value)} 的最左前缀，不是全表扫。
     *
     * <p><b>日预算变更</b>：{@code dailyBudget} 与 DB 原值不同时，事务提交后会把新预算同步到当天
     * Redis（余额 = max(0, 新日预算 − 今日已消耗)）。否则 Redis 的预算 key 已存在，
     * 新预算要等次日新 key 播种才生效。同步失败只记日志：DB 已是新值、Redis 仍是旧值，
     * 再次提交或次日自然恢复，不应回滚已提交的 DB 修改。
     */
    @Transactional
    public void update(Long id, AdUpsertRequest req) {
        Ad existing = mustGet(id);

        Ad patch = new Ad();
        patch.setId(id);
        patch.setTitle(req.getTitle());
        patch.setCreativeUrl(req.getCreativeUrl());
        patch.setCategoryCode(req.getCategoryCode());
        patch.setBid(req.getBid());
        patch.setDailyBudget(req.getDailyBudget());
        patch.setStatus(req.getStatus());
        adMapper.updateAd(patch);

        if (req.getTargets() != null) {
            // 去重后再全删重插：唯一键会拒绝重复的 (ad_id, dim_type, dim_value)
            List<TargetItem> targets = normalizeTargets(req.getTargets());
            adMapper.deleteTargetsByAdId(id);
            if (!targets.isEmpty()) {
                adMapper.batchInsertTargets(id, targets);
            }
        }

        // 日预算变了才同步 Redis，避免每次 PUT 都多一次写
        if (req.getDailyBudget() != null
                && (existing.getDailyBudget() == null
                    || req.getDailyBudget().compareTo(existing.getDailyBudget()) != 0)) {
            syncBudgetAfterCommit(id, req.getDailyBudget());
        }

        log.info("更新广告 id={} 是否重写定向={}", id, req.getTargets() != null);
    }

    /**
     * 只更新日预算：单条 UPDATE，不读取/回写 title、bid 等字段，
     * 避免与编辑广告的并发请求互相覆盖。
     */
    @Transactional
    public void updateDailyBudget(Long id, BigDecimal dailyBudget) {
        if (dailyBudget == null || dailyBudget.compareTo(new BigDecimal("1.00")) < 0) {
            throw BizException.badRequest("dailyBudget 应不小于 1.00 元");
        }
        Ad existing = mustGet(id);
        if (existing.getDailyBudget() != null
                && dailyBudget.compareTo(existing.getDailyBudget()) == 0) {
            return;
        }
        int updated = adMapper.updateDailyBudget(id, dailyBudget);
        if (updated == 0) {
            throw BizException.notFound("广告不存在: id=" + id);
        }
        syncBudgetAfterCommit(id, dailyBudget);
        log.info("只更新广告 id={} 日预算为 {}，事务提交后同步当天 Redis", id, dailyBudget);
    }

    /**
     * 广告日预算变更后，把新额度同步到当天 Redis。
     *
     * <p>注册到事务 {@code afterCommit}：DB 回滚时绝不能留下「Redis 已改、DB 未改」的状态，
     * 否则会在 key 的整个 TTL（默认 2 天）内按错误预算计费。
     */
    private void syncBudgetAfterCommit(Long id, BigDecimal dailyBudget) {
        Runnable apply = () -> {
            try {
                BigDecimal remain = budgetService.applyDailyBudget(id, dailyBudget);
                log.info("广告 id={} 日预算变更为 {}，当日 Redis 余额重算为 {} 元", id, dailyBudget, remain);
            } catch (Exception e) {
                // best-effort：DB 已是新预算，Redis 仍是旧值；再次提交或次日新 key 自然恢复
                log.warn("广告 id={} 日预算同步 Redis 失败（DB 已生效，Redis 仍为旧值）: {}",
                        id, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    apply.run();
                }
            });
        } else {
            apply.run();
        }
    }

    /** 删除：先删从属的定向条件、再删本体；同一事务，避免留下孤儿定向行 */
    @Transactional
    public void delete(Long id) {
        mustGet(id);
        int targets = adMapper.deleteTargetsByAdId(id);
        adMapper.deleteAd(id);
        log.info("删除广告 id={}，连带清理定向 {} 条", id, targets);
    }

    /**
     * 上下架：在 1(在投) 与 2(暂停) 之间切换。
     *
     * <p><b>3(已耗尽) 不接受外部指定</b>——它是 BudgetService 在预算扣减失败时写入的系统状态。
     *
     * <p><b>一个必须知道的坑</b>：把 3 改回 1 <b>并不能真正恢复投放</b>。
     * 因为预算耗尽的根因在 Redis 里——{@code spent:{adId}:{yyyyMMdd}} 的计数仍是满的，
     * 下一次竞价扣减还是失败，广告会被立刻重新标记为 3。
     * 真正的恢复要先重置预算：等次日 00:05 的复位任务 + 新一天 key，
     * 或通过 {@code PUT /admin/ad/{id}} 修改 dailyBudget 触发当天余额重算（见 {@link #update}）。
     * 本接口不悄悄做这件事，避免调用方以为「改个状态就恢复了」。
     */
    @Transactional
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != STATUS_ACTIVE && status != STATUS_PAUSED)) {
            throw BizException.badRequest(
                    "status 只能为 1(在投) 或 2(暂停)；3(已耗尽) 是系统状态，不接受手工指定");
        }
        Ad ad = mustGet(id);
        adMapper.updateStatus(id, status);
        log.info("广告 id={} 状态 {} -> {}", id, ad.getStatus(), status);
    }


    /**
     * 定向条件去重：ad_target 上有 UNIQUE(ad_id, dim_type, dim_value)，
     * 同一组条件重复提交会让批量 INSERT 直接报错；这里按 (dimType, dimValue) 先收敛。
     *
     * <p>输入非空但全部非法时抛 400（而不是静默清空），避免调用方以为定向已生效。
     */
    private static List<TargetItem> normalizeTargets(List<TargetItem> targets) {
        Map<String, TargetItem> unique = new LinkedHashMap<>();
        for (TargetItem t : targets) {
            if (t == null || t.getDimType() == null || t.getDimValue() == null) {
                continue;
            }
            unique.putIfAbsent(t.getDimType() + "\u0000" + t.getDimValue(), t);
        }
        List<TargetItem> normalized = new ArrayList<>(unique.values());
        if (!targets.isEmpty() && normalized.isEmpty()) {
            throw BizException.badRequest("定向条件不合法：dimType 与 dimValue 不能为空");
        }
        return normalized;
    }

    /** 取广告，不存在直接抛 404，避免各处重复判空 */
    private Ad mustGet(Long id) {
        Ad ad = adMapper.selectById(id);
        if (ad == null) {
            throw BizException.notFound("广告不存在: id=" + id);
        }
        return ad;
    }
}
