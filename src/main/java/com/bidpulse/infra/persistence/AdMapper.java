package com.bidpulse.infra.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;
import com.bidpulse.entity.Ad;
import com.bidpulse.api.dto.AdQuery;
import com.bidpulse.api.dto.TargetItem;
/**
 * 广告 Mapper。
 *
 * <p><b>M1 的检索方式</b>：ad 表全表扫描 + ad_target 多维 JOIN 求交。
 *
 * <p><b>为什么是「一半优化」的</b>：M1 有两个互相冲突的要求——
 * 候选集要落在 100~200 条（否则造数与演示都不成立），
 * 而检索要足够慢（否则 M2 没有对照价值）。
 * 解法是分工：<b>ad 表不建索引承担「慢」</b>，<b>ad_target 建索引承担「筛得准」</b>。
 * 详见 {@code sql/all-schema.sql}。
 */
@Mapper
public interface AdMapper {

    /**
     * 按定向条件做多维交集检索候选广告。
     *
     * <p>三个定向维度（地域 / 年龄段 / 性别）取交集，再叠加类目与在投状态。
     * 任一维度传 null 表示该维度不参与过滤，对应的 JOIN 会被拼掉。
     *
     * <p><b>为什么要 LIMIT</b>：候选集只服务于一次二价计算与 fall-through，
     * 返回全部几百行没有意义，只会放大排序、网络与对象映射开销。
     * 上限由 {@code bidpulse.bid.candidate-limit} 控制（默认 50），
     * 与 {@code BudgetService#warmBudgets} 的预热规模保持一致。
     */
    List<Ad> findCandidates(@Param("region") String region,
                            @Param("ageBucket") String ageBucket,
                            @Param("gender") String gender,
                            @Param("categoryCode") String categoryCode,
                            @Param("limit") int limit);

    /**
     * 批量标记广告当日预算已耗尽（status=1 -> 3）。
     *
     * <p>批量语义：仅当广告当前在投（status=1）时才更新，已是 3 的不动，重复调用幂等。
     * 一条 {@code UPDATE ... WHERE status = 1 AND id IN (...)} 同时处理多个 id，
     * 只产生一次 redo fsync、只借一次 Hikari 连接。
     *
     * <p>返回值为<b>实际被更新的行数</b>：已是 3（或已删除、已下架）的 id 不计入，
     * 因此调用方可据此观测「真正由 1 翻 3」的广告数。
     *
     * @param ids 待标记的广告 id；调用方保证非空（空集合会拼出非法 SQL）
     */
    @Update("<script>UPDATE ad SET status = 3 WHERE status = 1 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int markExhaustedBatch(@Param("ids") List<Long> ids);

    /**
     * 日预算复位：把「昨天及更早」被标记为已耗尽的广告（status=3）恢复为在投（status=1）。
     *
     * <p>预算是按天分桶的（{@code budget:{adId}:{yyyyMMdd}}），跨天后 Redis 里额度自动恢复；
     * 若 DB 状态不复位，广告会永久退出投放。用 {@code updated_at < CURDATE()} 卡住自然日边界，
     * 不会放开当天刚耗尽的数据。
     *
     * @return 实际复位的行数
     */
    @Update("UPDATE ad SET status = 1 WHERE status = 3 AND updated_at < CURDATE()")
    int resetExhaustedBeforeToday();

    // =====================================================================
    // 管理端 CRUD（服务 /admin/ad 这批接口）
    //
    // 与上面的检索路径完全隔离：这些 SQL 不参与竞价，因此不会改变
    // findCandidates 的执行计划，也不影响 M1 baseline 的结论。
    //
    // 【已知代价】ad 表除主键外无索引（M1 刻意设计）、ad_target 只有
    // (dim_type, dim_value, ad_id) 联合索引（最左前缀用不上 ad_id），因此：
    //   · countBy / selectPage  按 advertiser_id 查 → 全表扫 + filesort
    //   · listTargetsByAdId / deleteTargetsByAdId  按 ad_id 查 → 全表扫 644 万行
    // 索引留待后续单独补（建议 KEY idx_adv(advertiser_id) 与
    // KEY idx_ad_dir(ad_id, dim_type)），补完需重新 EXPLAIN 复核检索计划未变。
    // =====================================================================

    /** 按主键查广告，不存在返回 null */
    Ad selectById(@Param("id") Long id);

    /** 列表总数，筛选条件与 selectPage 完全一致，保证 total 与当页数据同源 */
    long countBy(@Param("q") AdQuery q);

    /** 列表分页查询（ORDER BY id DESC，最新的在前） */
    List<Ad> selectPage(@Param("q") AdQuery q);

    /** 新建广告，回填自增主键到 {@code ad.id} */
    int insertAd(Ad ad);

    /** 按主键更新可变字段（内部语义：null 字段不 SET；对外 PUT 仍是全量替换） */
    int updateAd(Ad ad);

    /** 按主键删除广告本体 */
    int deleteAd(@Param("id") Long id);

    /**
     * 变更投放状态。目标状态只允许 1(在投)/2(暂停)，由 service 层校验。
     * 也允许把系统标记的 3(已耗尽) 改回 1，但**仅改状态不足以恢复投放**——
     * Redis 的 spent 计数仍为满，下一轮扣减会立刻把它重新标记为 3，需配合预算重置。
     */
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /** 查某广告的全部定向条件，供详情页展示 */
    List<Map<String, Object>> listTargetsByAdId(@Param("adId") Long adId);

    /** 批量写入定向条件（单条 INSERT 拼多组 VALUES） */
    int batchInsertTargets(@Param("adId") Long adId, @Param("targets") List<TargetItem> targets);

    /** 清空某广告的定向条件（更新时「全删重插」的第一步） */
    int deleteTargetsByAdId(@Param("adId") Long adId);
}
