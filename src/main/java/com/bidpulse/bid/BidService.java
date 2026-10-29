package com.bidpulse.bid;

import com.bidpulse.entity.Ad;
import com.bidpulse.infra.persistence.AdMapper;
import com.bidpulse.infra.redis.BudgetService;
import com.bidpulse.event.BidEvent;
import com.bidpulse.infra.kafka.BidEventPublisher;
import com.bidpulse.infra.buffer.BidDetailBuffer;
import com.bidpulse.infra.buffer.ExhaustedBuffer;
import com.bidpulse.breaker.BreakerGate;
import com.bidpulse.breaker.BreakerOpenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import com.bidpulse.config.BidPulseProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.bidpulse.api.dto.BidRequest;
import com.bidpulse.entity.BidLog;

/**
 * 竞价主链路 · M1 朴素实现 + M3 预算 Lua + M4 二价 + M5 超时/熔断/兜底 + M6 事件旁路
 *
 * <p>链路：候选检索 → 定向过滤（已下推 SQL）→ 二价计费（fall-through）→ 返回。
 *
 * <p><b>本轮（M5）新增的韧性机制</b>：
 * <ul>
 *   <li><b>超时预算</b>：入链定 {@code deadline = now + total-ms}，计费循环内超期即放弃后续候选、
 *       直接兜底。<b>M9 起链路内不再有应用级线程池</b>：DB 超时由 MySQL 优化器 hint
 *       {@code MAX_EXECUTION_TIME(800)}（服务端毫秒级）+ MyBatis {@code timeout="1"} 兜住；
 *       Redis 超时由 Lettuce 的 {@code spring.data.redis.timeout(200ms)} 兜住。</li>
 *   <li><b>熔断</b>：DB 检索与 Redis 计费各持独立熔断器；
 *       连续失败超阈值 → OPEN（后续请求短路兜底），冷却后 HALF_OPEN 放一个探活，成功自愈。</li>
 *   <li><b>兜底广告</b>：任何失败（超时 / 熔断 OPEN / 候选为空 / 预算耗尽）一律返回 Redis 中的
 *       兜底广告（代码静态常量作最后保底），<b>绝不返回空广告、绝不请求失败</b>。</li>
 * </ul>
 *
 * <p><b>M6（事件旁路）</b>：每次 {@code /bid} 在 return 之前都会通过 {@link BidEventPublisher}
 * 异步投递一条 {@link BidEvent} 到 Kafka（分区键=adId），由三个独立消费组异步落库。
 * 发送是 fire-and-forget 且包在 try-catch 中，<b>绝不增加延迟、绝不因 Kafka 故障影响主链路</b>。
 *
 * <p>注意：主链路<b>不读</b>本地广告缓存、<b>不</b>同步写 MQ——竞价事件与竞价明细都在返回之后异步处理：
 * 事件走 Kafka 旁路（不可丢），明细走本地有界队列批量落库（可丢、可采样）。
 */
@Slf4j
@Service
public class BidService {

    private final AdMapper adMapper;
    private final BudgetService budgetService;
    private final FallbackAdProvider fallbackAdProvider;
    private final BidEventPublisher bidEventPublisher;
    /** M7：竞价明细异步落库缓冲（本地有界队列 + 后台批量刷盘） */
    private final BidDetailBuffer bidDetailBuffer;
    /** M9：「预算耗尽」状态回写缓冲（本地有界队列 + 后台批量 UPDATE） */
    private final ExhaustedBuffer exhaustedBuffer;

    /** 统一配置（超时 / 候选上限 / 兜底 / 最小加价），不再散落 @Value */
    private final BidPulseProperties props;


    /** M5/M8：DB 与 Redis 各自独立的熔断门（impl 可切 sentinel / local），互不影响 */
    private final BreakerGate dbBreaker;
    private final BreakerGate redisBreaker;

    /**
     * 二价定价时向后确认「竞争者是否付得起」的最大条数。
     *
     * <p>正常流量下胜出者的下一名就付得起；上限只用于防止「连续多条候选都已耗尽」
     * 的极端数据把热路径拖长。窗口内的余额用一次 {@code MGET} 批量取回（见
     * {@link BudgetService#peekBalances}），不是逐条 GET。
     */
    private static final int SECOND_PRICE_LOOKAHEAD = 5;

    /**
     * 二价定价结果。
     *
     * @param secondBid    用于计算成交价的「次高有效出价」；没有可支付竞争者时为胜出者自身出价
     * @param competitive  窗口内是否存在「余额 > 0」的可支付竞争者。
     *                     为 false 时成交价退化为胜出者自身出价，对外应答的 {@code priceMode} 应为 0（一价）
     */
    private record SecondPrice(BigDecimal secondBid, boolean competitive) {
    }


    public BidService(AdMapper adMapper,
                      BudgetService budgetService,
                      FallbackAdProvider fallbackAdProvider,
                      BidEventPublisher bidEventPublisher,
                      BidDetailBuffer bidDetailBuffer,
                      ExhaustedBuffer exhaustedBuffer,
                      BidPulseProperties props,
                      @Qualifier("dbBreakerGate") BreakerGate dbBreaker,
                      @Qualifier("redisBreakerGate") BreakerGate redisBreaker) {
        this.adMapper = adMapper;
        this.budgetService = budgetService;
        this.fallbackAdProvider = fallbackAdProvider;
        this.bidEventPublisher = bidEventPublisher;
        this.bidDetailBuffer = bidDetailBuffer;
        this.exhaustedBuffer = exhaustedBuffer;
        this.props = props;
        this.dbBreaker = dbBreaker;
        this.redisBreaker = redisBreaker;
    }

    /**
     * 执行一次竞价。M5 下任何「不该打 / 打失败 / 超时 / 预算耗尽」都统一收敛到兜底广告。
     * M6 下每个出口在 return 前都会异步发布一条竞价事件（旁路，不影响返回）。
     */
    public BidRequest.Result bid(BidRequest req) {
        // 本次竞价请求的全局唯一 ID，用于日志追踪、数据对账与 Kafka 事件关联
        String requestId = UUID.randomUUID().toString().replace("-", "");
        // 链路起点（纳秒），后续各阶段耗时都以它为基准计算
        long startNanos = System.nanoTime();
        // 总超时截止时刻（纳秒）= 起点 + 总超时预算；计费循环在 deadline 内推进，超期即兜底，防止越 SLA
        long deadline = startNanos + props.getTimeout().getTotalMs() * 1_000_000L;
        // 候选检索阶段耗时（ms），初始 0，检索完成后回填，用于埋点与对账
        int candidateCostMs = 0;
        // 计费（Redis 预算扣减）阶段耗时（ms），初始 0，扣减完成后回填，用于埋点与对账
        int billCostMs = 0;

        // 强制兜底（演示/复现开关）：直接返回兜底广告，模拟依赖全面故障
        if (props.getFallback().isForce()) {
            BidRequest.Result r = buildFallback(requestId);
            emit(requestId, req, r, ms(startNanos, System.nanoTime()), 0, 0);
            return r;
        }

        // ---------------------------------------------------------
        // ① 候选检索（套熔断，超时下沉到 SQL 层）。ORDER BY a.bid DESC 已把出价排序下推到 SQL。
        // ---------------------------------------------------------
        // 候选检索阶段起点（纳秒），用于计算该阶段耗时
        long cStart = System.nanoTime();
        // 调用候选检索：DB 熔断在内部判断；返回 null 表示熔断 OPEN / SQL 超时(800ms) / 异常
        List<Ad> candidates = retrieveCandidates(req);
        // 回填候选检索耗时（ms），供埋点与对账使用
        candidateCostMs = ms(cStart, System.nanoTime());
        if (candidates == null) {
            // 候选检索失败（DB 熔断 OPEN / 超时 / 异常）→ 兜底，绝不返回空结果
            BidRequest.Result r = buildFallback(requestId);
            // 发布一条兜底竞价事件（旁路），并带上已统计的候选检索耗时
            emit(requestId, req, r, ms(startNanos, System.nanoTime()), candidateCostMs, 0);
            // 直接返回兜底结果，结束本次竞价
            return r;
        }

        // ---------------------------------------------------------
        // ② 定向过滤已在 SQL 的 WHERE/JOIN 完成，这里仅作拷贝占位
        // ---------------------------------------------------------
        List<Ad> matched = new ArrayList<>(candidates);

        // 候选为空（检索无命中）→ 兜底，不返回空广告
        if (matched.isEmpty()) {
            BidRequest.Result r = buildFallback(requestId);
            emit(requestId, req, r, ms(startNanos, System.nanoTime()), candidateCostMs, 0);
            return r;
        }

        // 检索阶段预算预热：为本次候选惰性补建 Redis 预算 key（SET NX + TTL）。
        // 本地按「今日已预热广告」过滤，避免每个请求对同一批候选重复 SET NX；
        // best-effort：失败忽略，计费时 Lua 也会带 TTL 惰性播种。
        try {
            budgetService.warmBudgets(matched);
        } catch (Exception e) {
            log.warn("预算预热失败（忽略，计费时惰性兜底）requestId={}: {}", requestId, e.getMessage());
        }

        // ---------------------------------------------------------
        // ③ 二价计费（Vickrey）+ fall-through
        //    候选已按出价降序（SQL ORDER BY a.bid DESC）。
        //    胜出者 = 沿降序第一个预算充足的候选；
        //    成交价 = min(胜出者出价, 次高「有效」出价 + 最小加价)。
        //    套 Redis 熔断 + 整体 deadline 约束：Redis 挂 / 预算耗尽 / 阶段超期 → 兜底。
        // ---------------------------------------------------------
        Ad winner = null;
        BigDecimal payPrice = null;
        Long adId = null;
        Integer advertiserId = null;
        // 1 = 二价（有可支付竞争者参与定价）/ 0 = 一价（没有可支付竞争者，按自身出价成交）
        int priceMode = 1;

        long bStart = System.nanoTime();
        // M7 修正：余额不足需回写 MySQL 状态的广告先收集，计费相位结束后再统一落库，
        // 彻底脱离 Redis 超时包裹、Redis 熔断与 billCostMs（Redis 计费耗时）计时。
        List<Long> exhaustedToMark = new ArrayList<>();
        // 二价只读余额按请求缓存：扣减失败不会改 Redis 余额，扣减成功会立即 break，
        // 因此同一请求内同一竞争者被 fall-through 反复看到时无需重复查询。
        Map<Long, BigDecimal> balanceCache = new HashMap<>();
        for (int i = 0; i < matched.size(); i++) {
            // 整体超时预算耗尽 → 放弃后续计费，兜底收尾
            if (System.nanoTime() > deadline) {
                break;
            }
            Ad candidate = matched.get(i);
            // 二价成交价：次高「有效出价」+ 最小加价，且不超过自身出价（PricingPolicy 纯函数）。
            // 有效出价 = min(竞争者出价, 当日剩余预算)：只有出价更低、并不代表付得起，
            // 必须把预算上限算进去，否则会拿一个付不起的候选把成交价抬高。
            SecondPrice secondPrice;
            try {
                secondPrice = nextPayableBid(matched, i, candidate, deadline, balanceCache);
            } catch (BreakerOpenException e) {
                // 只读余额查询也受同一 Redis 熔断门保护：未放行视为 Redis 不可用，整段计费放弃
                log.warn("Redis 余额查询熔断短路（未放行），走兜底");
                break;
            }
            if (secondPrice == null) {
                // 只读查询已耗尽总超时预算：不再尝试计费，兜底收尾
                break;
            }
            // 计费最小货币单位是「分」：把理论成交价显式量化到 2 位小数（向下取整，
            // 恒不超过理论价、也不超过自身出价），保证「返回给调用方的 payPrice、
            // 写 Kafka/bid_log 的金额、Redis 实际扣减的分」三者口径完全一致，
            // 不再出现 4 位小数成交价被 Redis 截断造成的对账漂移。
            BigDecimal price = PricingPolicy.secondPrice(
                            candidate.getBid(), secondPrice.secondBid(), props.getMinIncrement())
                    .setScale(2, RoundingMode.DOWN);

            BudgetService.DeductResult result;
            try {
                // 熔断门包裹单次扣减：粒度是「一次 Redis 调用」，慢调用阈值才对得上
                // spring.data.redis.timeout(200ms)。
                result = redisBreaker.call(() -> budgetService.deductResult(
                        candidate.getId(), candidate.getDailyBudget(), price, req.getUserId()));
            } catch (BreakerOpenException e) {
                // 熔断未放行（短路，未触达 Redis）→ 本请求不再尝试任何候选，整段计费放弃 → 兜底
                log.warn("Redis 计费熔断短路（未放行），走兜底");
                break;
            } catch (Exception e) {
                // 仅 Lettuce 命令超时 / 连接异常才算 Redis 失败；
                // 余额不足（正常返回 INSUFFICIENT）不属于 Redis 故障，不在此分支。
                // 基础设施故障时不再尝试剩余候选：它们走的是同一个挂掉的 Redis，
                // 继续遍历只会把整个 SLA 预算（1000ms）耗在重复超时上，直接放弃计费走兜底。
                log.warn("Redis 计费失败（超时/异常），跳过剩余候选走兜底: {} - {}",
                        e.getClass().getSimpleName(), e.getMessage());
                break;
            }

            if (result == BudgetService.DeductResult.NEEDS_SEED) {
                // budget 与 spent 双 key 都丢失（Redis 重启 / FLUSH）：当天已消耗未知，
                // 必须先用 billing_event 当日汇总重建，再重试一次扣减。
                // DB 查询与播种刻意放在 redisBreaker 之外：一次 DB 抖动不能被记成 Redis
                // 慢调用/失败，否则会把好好的 Redis 熔断器打开。
                if (System.nanoTime() > deadline) {
                    // SLA 已耗尽，别再用一轮 DB 重建把请求拖得更久
                    break;
                }
                try {
                    budgetService.seedFromDb(candidate.getId(), candidate.getDailyBudget());
                } catch (Exception e) {
                    log.warn("预算冷启动重建失败（DB 查询/写入异常），跳过剩余候选走兜底 adId={}: {} - {}",
                            candidate.getId(), e.getClass().getSimpleName(), e.getMessage());
                    break;
                }
                try {
                    result = redisBreaker.call(() -> budgetService.deductResult(
                            candidate.getId(), candidate.getDailyBudget(), price, req.getUserId()));
                } catch (BreakerOpenException e) {
                    log.warn("Redis 计费熔断短路（未放行），走兜底");
                    break;
                } catch (Exception e) {
                    log.warn("Redis 计费失败（重建后重试，超时/异常），走兜底: {} - {}",
                            e.getClass().getSimpleName(), e.getMessage());
                    break;
                }
                if (result == BudgetService.DeductResult.NEEDS_SEED) {
                    // 播种后双 key 仍缺失（写入被拒 / 再次 FLUSH）：绝不能按「整笔日预算」放款
                    log.warn("预算重建后仍无法扣减，跳过剩余候选走兜底 adId={}", candidate.getId());
                    break;
                }
            }

            if (result == BudgetService.DeductResult.SUCCESS) {
                winner = candidate;
                payPrice = price;
                adId = candidate.getId();
                advertiserId = candidate.getAdvertiserId();
                priceMode = secondPrice.competitive() ? 1 : 0;
                break;
            }
            // M7 修正（职责解耦）：只收集「Redis 正常返回余额不足」的候选，
            // 不在计费相位内写 MySQL（避免污染 billCostMs、受 Redis 200ms 子超时约束、
            // 误开 Redis 熔断器）。Redis 故障已在上面 break，不会误标。
            // 频次超限（FREQ_CAPPED）是正常业务结果：只跳过该候选，绝不能标成已耗尽——
            // 否则一个用户刷满频次就会让广告对所有人下线。
            if (result == BudgetService.DeductResult.INSUFFICIENT) {
                exhaustedToMark.add(candidate.getId());
            }
        }

        billCostMs = ms(bStart, System.nanoTime());   // ← Redis 扣减 + 二价只读余额查询耗时，不含后续 MySQL 回写

        // M9 修正：计费相位之外、把「余额不足」的广告 id 投递到后台缓冲，由常驻线程
        // 攒批回写 MySQL 状态（ad.status=3，使检索 SQL 的 WHERE a.status=1 后续直接排除）。
        // 这里只做一次 offer（纳秒级），请求线程不再等待 DB —— 即便本条请求已走兜底返回，
        // 回写仍会在后台完成。已完全脱离 Redis 超时包裹、Redis 熔断器与 billCostMs 计时；
        // 数据可丢（丢了下一次请求再判一次余额不足即自愈），队列满时直接丢弃。
        for (Long id : exhaustedToMark) {
            exhaustedBuffer.enqueue(id);
        }

        // Redis 熔断 OPEN / 预算全部不足 / 整体超时 → 兜底广告保底
        if (winner == null) {
            BidRequest.Result r = buildFallback(requestId);
            emit(requestId, req, r, ms(startNanos, System.nanoTime()), candidateCostMs, billCostMs);
            return r;
        }

        // [已注释] 分段计时埋点 + bid_log 落库（M1 baseline 压测期间不采集统计，纯服务化）
        // persistLog(requestId, req, candidates.size(), matched.size(),
        //         adId, advertiserId, winner, payPrice,
        //         costMs, candidateCost, filterCost, sortCost, billCost);

        BidRequest.Result r = buildResult(requestId, candidates, matched, winner, payPrice, priceMode);
        emit(requestId, req, r, ms(startNanos, System.nanoTime()), candidateCostMs, billCostMs);
        return r;
    }

    /**
     * 为胜出候选确定二价的「次高有效出价」。
     *
     * <p><b>为什么不直接取下一名</b>：排在胜出者后面的候选只是出价更低，并不代表它付得起。
     * 一个当天已耗尽的高出价候选会把成交价抬到高于真实机会成本。
     *
     * <p><b>有效出价</b>：{@code min(候选出价, 当日剩余预算)}。预算不足的候选按它真正付得起的
     * 额度参与定价，价格随预算连续下降，而不是「付不起就整条跳过」造成的断崖。
     * 窗口内余额用一次只读 {@code MGET} 取回（{@link BudgetService#peekBalances}，
     * 不播种、不写 Redis），并沿请求级 {@code balanceCache} 复用；
     * 最多看 {@value #SECOND_PRICE_LOOKAHEAD} 条。
     *
     * <p><b>故障与超时</b>：
     * <ul>
     *   <li>只读查询走 {@code redisBreaker}，熔断未放行时抛 {@link BreakerOpenException}，
     *       由调用方放弃整段计费走兜底（这些慢调用也因此会被熔断器统计到）；</li>
     *   <li>查询前检查总 deadline，已超期返回 {@code null}，避免在 SLA 将尽时再起一轮 Redis 往返；</li>
     *   <li>其它读取异常退回旧语义（直接采用第一个竞争者的出价），定价故障不放大成整条竞价失败。</li>
     * </ul>
     *
     * <p>若胜出者之后没有任何有效竞争者（含只有一名候选、或竞争者余额全为 0），
     * 次高价取自身出价，成交价 = 自身出价，退化为「一价」。
     *
     * @param matched      按出价降序的候选（与计费循环同一份列表）
     * @param winnerIdx    胜出候选下标
     * @param winner       胜出候选
     * @param deadline     本次竞价的总超时截止时刻（{@link System#nanoTime()}）
     * @param balanceCache 本次请求内的 adId → 剩余预算缓存
     * @return 二价结果；只读查询已超期时返回 {@code null}
     */
    private SecondPrice nextPayableBid(List<Ad> matched, int winnerIdx, Ad winner,
                                       long deadline, Map<Long, BigDecimal> balanceCache) {
        int last = Math.min(matched.size() - 1, winnerIdx + SECOND_PRICE_LOOKAHEAD);

        // 窗口内尚未查询过的竞争者，一次 MGET 批量取回
        List<Ad> toFetch = new ArrayList<>();
        for (int k = winnerIdx + 1; k <= last; k++) {
            Ad competitor = matched.get(k);
            if (competitor.getBid() != null && !balanceCache.containsKey(competitor.getId())) {
                toFetch.add(competitor);
            }
        }
        if (!toFetch.isEmpty()) {
            // 只读查询也受总 deadline 约束：不能在 SLA 将尽时再起一轮 Redis 往返
            if (System.nanoTime() > deadline) {
                return null;
            }
            try {
                Map<Long, BigDecimal> fetched = redisBreaker.call(
                        () -> budgetService.peekBalances(toFetch));
                balanceCache.putAll(fetched);
            } catch (BreakerOpenException e) {
                // 短路信号原样上抛：不能当成「读到了 0」，也不能再喂回熔断器
                throw e;
            } catch (Exception e) {
                // 只读查询故障：退回「直接采用第一个竞争者出价」的旧语义。
                // 用 debug：真正的 Redis 故障会在随后的 deduct 分支以 warn 打出，避免刷屏。
                log.debug("二价定价：读取竞争者余额失败，按第一个竞争者出价定价: {} - {}",
                        e.getClass().getSimpleName(), e.getMessage());
                for (int k = winnerIdx + 1; k <= last; k++) {
                    BigDecimal bid = matched.get(k).getBid();
                    if (bid != null) {
                        return new SecondPrice(bid, true);
                    }
                }
                return new SecondPrice(winner.getBid(), false);
            }
        }

        // 有效出价 = min(出价, 剩余预算)；余额为 0 的候选无法承接本次竞价，不参与定价
        BigDecimal best = null;
        for (int k = winnerIdx + 1; k <= last; k++) {
            Ad competitor = matched.get(k);
            BigDecimal bid = competitor.getBid();
            if (bid == null) {
                continue;
            }
            BigDecimal remain = balanceCache.get(competitor.getId());
            if (remain == null) {
                continue;
            }
            BigDecimal effective = PricingPolicy.effectiveBid(bid, remain);
            if (effective.signum() > 0 && (best == null || effective.compareTo(best) > 0)) {
                best = effective;
            }
        }
        // 没有有效竞争者：次高 = 自身，成交价 = 自身出价（一价）
        return best == null
                ? new SecondPrice(winner.getBid(), false)
                : new SecondPrice(best, true);
    }

    /**
     * M6：构造并发布竞价事件（旁路）。无论胜出还是兜底，都发送一条事件供下游三消费组使用。
     * 内部已 try-catch，任何异常都不影响主链路返回。
     */
    private void publishBidEvent(String requestId, String region, String userId, BidRequest.Result r) {
        try {
            BidEvent e = BidEvent.builder()
                    .eventId(UUID.randomUUID().toString().replace("-", ""))
                    .requestId(requestId)
                    .ts(System.currentTimeMillis())
                    .region(region)
                    .userId(userId)
                    .adId(r.getWinner() != null ? r.getWinner().getAdId() : null)
                    .advertiserId(r.getWinner() != null ? r.getWinner().getAdvertiserId() : null)
                    .winBid(r.getWinner() != null ? r.getWinner().getBid() : null)
                    .payPrice(r.getPayPrice())
                    .fallback(Boolean.TRUE.equals(r.getFallback()))
                    .hasAd(Boolean.TRUE.equals(r.getHasAd()))
                    .priceMode(r.getPriceMode() == null ? 0 : r.getPriceMode())
                    .build();
            bidEventPublisher.publish(e);
        } catch (Exception ex) {
            log.warn("发布竞价事件失败（忽略，不影响竞价返回）requestId={}: {}", requestId, ex.getMessage());
        }
    }

    /**
     * M7：把一次竞价结果「事件旁路(Kafka) + 明细落库(本地队列)」合并发出。
     * 主链路每个 return 前调用一次，保证计费事件与竞价明细一一对应、且都不阻塞返回。
     *
     * @param costMs          整条链路总耗时（ms），入链到 return 的墙钟时间
     * @param candidateCostMs 候选检索耗时（ms），DB 调用，含 SQL 内定向过滤/排序
     * @param billCostMs     计费耗时（ms），Redis 预算扣减循环
     */
    private void emit(String requestId, BidRequest req, BidRequest.Result r,
                      int costMs, int candidateCostMs, int billCostMs) {
        // 1) 计费事件旁路（Kafka，fire-and-forget，不阻塞）
        publishBidEvent(requestId, req.getRegion(), req.getUserId(), r);
        // 2) 竞价明细入本地有界队列，后台线程批量落库（可丢、可采样）
        try {
            BidLog log = new BidLog();
            log.setRequestId(requestId);
            log.setAdId(r.getWinner() != null ? r.getWinner().getAdId() : null);
            log.setAdvertiserId(r.getWinner() != null ? r.getWinner().getAdvertiserId() : null);
            log.setSlotCode(req.getSlotCode());
            log.setRegion(req.getRegion());
            log.setAge(req.getAge());
            // bid_log.gender 是 NOT NULL，0 表示「未知」（与 DTO 注释口径一致）；
            // age 列已放宽为可空：只传 ageBucket 的请求不应被伪造成 age=0（0 是合法年龄）。
            log.setGender(req.getGender() != null ? req.getGender() : 0);
            log.setInterests(req.getInterests() != null ? req.getInterests() : "");
            log.setCandidateCnt(r.getCandidateCnt());
            log.setMatchCnt(r.getMatchCnt());
            log.setHasAd(Boolean.TRUE.equals(r.getHasAd()) ? 1 : 0);
            log.setWinBid(r.getWinner() != null ? r.getWinner().getBid() : null);
            log.setPayPrice(r.getPayPrice());
            log.setCostMs(costMs);
            log.setCandidateCost(candidateCostMs);
            // 定向过滤与出价排序在 M1 已下推进 SQL（WHERE/JOIN/ORDER BY a.bid DESC），
            // 不单独计时，瓶颈完全落在 candidate_cost（DB 检索）。
            log.setFilterCost(0);
            log.setSortCost(0);
            log.setBillCost(billCostMs);
            log.setBidAt(LocalDateTime.now());
            bidDetailBuffer.enqueue(log);
        } catch (Exception ex) {
            log.warn("构造/入队竞价明细失败（忽略，不影响竞价返回）requestId={}: {}", requestId, ex.getMessage());
        }
    }

    private static int ms(long fromNanos, long toNanos) {
        return (int) ((toNanos - fromNanos) / 1_000_000);
    }

    /**
     * ① 候选检索：熔断 OPEN 直接短路；否则同步调用 DB。
     *
     * <p><b>M9（去线程池）</b>：不再用「线程池 + {@code get(timeout)}」包裹，改同步调用，
     * 超时下沉到 SQL 层，由两道闸门负责：
     * <ul>
     *   <li>MySQL 优化器 hint {@code MAX_EXECUTION_TIME(800)}：<b>服务端</b>毫秒级中断，只作用于本 SELECT；</li>
     *   <li>MyBatis {@code timeout="1"}：<b>客户端</b>秒级保险丝（驱动强制取消）。</li>
     * </ul>
     * 代价：请求线程会被阻塞至多约 800ms~1s；换来的是竞价链路里没有任何应用级线程池。
     *
     * @return 候选列表；任一失败（熔断/超时/异常）返回 null，由调用方走兜底
     */
    private List<Ad> retrieveCandidates(BidRequest req) {
        try {
            // 熔断门在外、SQL 在内：门测到的是这次 DB 调用的真实 RT，
            // 慢调用比例可直接捕捉「DB 变慢」，无需手工计时上报。
            // 超时由 SQL hint(800ms) + MyBatis timeout(1s) 兜住。
            return dbBreaker.call(() -> adMapper.findCandidates(
                    req.getRegion(),
                    TargetingResolver.resolveAgeBucket(req.getAge(), req.getAgeBucket()),
                    TargetingResolver.toGender(req.getGender()),
                    req.getCategoryCode(),
                    props.getBid().getCandidateLimit()));
        } catch (BreakerOpenException e) {
            // 熔断未放行：短路，本次未触达 DB
            log.warn("候选检索熔断短路（DB 未放行），走兜底");
            return null;
        } catch (Exception e) {
            // SQLTimeoutException（MyBatis timeout）/ 3024 Query execution was interrupted
            // （MAX_EXECUTION_TIME 服务端中断）/ 其它 DB 异常，统一计一次失败
            log.warn("候选检索失败（超时/异常），走兜底: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** 兜底广告出口：Redis 优先，失败回退静态常量。hasAd=1、fallback=true、不计二价 */
    private BidRequest.Result buildFallback(String requestId) {
        // A/B 对照组：兜底关闭时返回真正的空广告，用于测量改造前的空广告率
        if (!fallbackAdProvider.enabled()) {
            BidRequest.Result empty = new BidRequest.Result();
            empty.setRequestId(requestId);
            empty.setHasAd(false);
            empty.setFallback(false);
            empty.setPriceMode(2);
            empty.setCandidateCnt(0);
            empty.setMatchCnt(0);
            empty.setTopCandidates(List.of());
            return empty;
        }
        Ad ad = fallbackAdProvider.current();
        BidRequest.Result r = new BidRequest.Result();
        r.setRequestId(requestId);
        r.setHasAd(true);
        r.setFallback(true);
        r.setPriceMode(2);          // 2 = 兜底，区别于 0 一价 / 1 二价
        r.setPayPrice(null);        // 兜底不计费
        r.setCandidateCnt(0);
        r.setMatchCnt(0);
        r.setTopCandidates(List.of());

        BidRequest.AdInfo info = new BidRequest.AdInfo();
        info.setAdId(ad.getId());
        info.setAdvertiserId(ad.getAdvertiserId());
        info.setTitle(ad.getTitle());
        info.setCategoryCode(ad.getCategoryCode());
        info.setBid(ad.getBid());
        r.setWinner(info);
        return r;
    }


    private BidRequest.Result buildResult(String requestId, List<Ad> candidates,
                                          List<Ad> matched, Ad winner, BigDecimal payPrice,
                                          int priceMode) {
        BidRequest.Result r = new BidRequest.Result();
        r.setRequestId(requestId);
        r.setHasAd(winner != null);
        r.setFallback(false);
        r.setCandidateCnt(candidates.size());
        r.setMatchCnt(matched.size());
        // 1 = 二价（有可支付竞争者参与定价）；0 = 一价（无竞争者，成交价 = 自身出价）
        r.setPriceMode(priceMode);
        r.setPayPrice(payPrice);

        if (winner != null) {
            BidRequest.AdInfo info = new BidRequest.AdInfo();
            info.setAdId(winner.getId());
            info.setAdvertiserId(winner.getAdvertiserId());
            info.setTitle(winner.getTitle());
            info.setCategoryCode(winner.getCategoryCode());
            info.setBid(winner.getBid());
            r.setWinner(info);
        }

        // 出价前 N 条，演示时展示「为什么是它赢」
        int topN = Math.min(10, matched.size());
        List<BidRequest.AdInfo> tops = new ArrayList<>(topN);
        for (int i = 0; i < topN; i++) {
            Ad a = matched.get(i);
            BidRequest.AdInfo info = new BidRequest.AdInfo();
            info.setAdId(a.getId());
            info.setAdvertiserId(a.getAdvertiserId());
            info.setTitle(a.getTitle());
            info.setCategoryCode(a.getCategoryCode());
            info.setBid(a.getBid());
            tops.add(info);
        }
        r.setTopCandidates(tops);

        return r;
    }

}
