<script setup lang="ts">
import { FALLBACK_AD_ID } from '@/constants'

const visible = defineModel<boolean>({ required: true })
</script>

<template>
    <el-drawer v-model="visible" title="BidPulse Console 使用说明" size="560px">
      <div class="help-body">
        <p class="help-lead">
          这是一个 RTB 投放引擎的演示控制台。建议先看“首页”确认系统正常，再去“模拟竞价”发一次请求，
          最后用“预算与频次”和“运行监控”解释背后的机制。
        </p>

        <section class="help-section">
          <h3>1. 首页：先看系统是否正常</h3>
          <p>今日请求、兜底率、P95、消耗、广告状态和 Kafka 积压。健康区里的“DB/Redis 熔断”表示依赖是否被短路；
            “最近结果分布”可以快速看出胜出、兜底、空广告的占比。</p>
        </section>

        <section class="help-section">
          <h3>“兜底 {{ FALLBACK_AD_ID }}”是什么意思？</h3>
          <p>当没有可投放广告（无候选、预算耗尽、依赖故障或超时）时，系统不会返回空广告，而是返回一条固定的
            保底广告的广告 ID 是 {{ FALLBACK_AD_ID }}。它的出价只是配置值，成交价显示“—”表示不计费；请求结果标记为“兜底”，
            priceMode=2。</p>
        </section>

        <section class="help-section">
          <h3>2. 模拟竞价：亲手发一次请求</h3>
          <p>填地域、年龄、性别和 userId，点“发起竞价”。结果卡会显示谁胜出、胜出价、实际成交价和计费模式；
            “连打 4 次”可以观察同一用户达到频次上限后，候选被跳过而不是被标成耗尽。</p>
        </section>

        <section class="help-section">
          <h3>3. 广告管理：管理可投放广告</h3>
          <p>查看广告列表、详情和定向条件，支持新建、编辑、暂停/恢复、调日预算。保存后下一次竞价立即生效；
            没有定向条件的广告永远不会被检索命中。</p>
        </section>

        <section class="help-section">
          <h3>4. 预算与频次：查广告和用户状态</h3>
          <p>输入广告 ID 查当天剩余预算、已消耗与有效期；输入用户 ID 查当前小时已经成交几次。页面只读，不会改预算。
            演示数据可用广告 ID <strong>110</strong>；兜底广告 <strong>{{ FALLBACK_AD_ID }}</strong> 不在 ad 表里，所以查不到是正常的。</p>
        </section>

        <section class="help-section">
          <h3>5. 运行监控：看异步链路有没有卡住</h3>
          <p>三个页签分别是消费积压、失败消息重放、预算对账差异。暂停消费后继续竞价，可以看到积压上升但竞价不受影响；失败消息支持单条或批量重放；对账页会列出 Redis 与账单的差异。</p>
        </section>

        <section class="help-section">
          <h3>6. 数据查询：排查底层数据</h3>
          <p>按主题查看竞价明细、计费流水、小时聚合和消费去重，支持广告 ID/请求 ID 过滤和 CSV 导出。</p>
        </section>

        <section class="help-section">
          <h3>7. 演示开关：演示故障与策略变化</h3>
          <p>可以临时关闭兜底、强制兜底、关闭频控或调整每小时上限。这些开关会影响下一次请求的行为，
            演示结束后请恢复到默认值。</p>
        </section>

        <section class="help-section">
          <h3>名词速查</h3>
          <dl class="help-glossary">
            <dt>竞价</dt><dd>一次广告请求：检索候选、扣预算、算成交价、返回胜出广告。</dd>
            <dt>二价</dt><dd>成交价 = min(胜出价, 次高有效出价 + 0.01 元)。</dd>
            <dt>预算</dt><dd>按「广告 × 天」存在 Redis 的剩余额度，扣减前校验，成功后累计消耗。</dd>
            <dt>频次</dt><dd>按「用户 × 广告 × 自然小时」计数，超限只跳过候选，不扣费、不标耗尽。</dd>
            <dt>兜底 / {{ FALLBACK_AD_ID }}</dt><dd>没有可投放真实广告时的保底广告；固定广告 ID 为 {{ FALLBACK_AD_ID }}，成交价为空，不计费。</dd>
            <dt>计费方式</dt><dd>0 一价、1 二价、2 兜底。</dd>
            <dt>竞价明细</dt><dd>每次竞价请求的结果与耗时；异步落库，可能有约 200ms 延迟。</dd>
            <dt>计费流水</dt><dd>每次成交产生的扣费事件；Kafka 异步写入，是预算重建与对账的依据。</dd>
            <dt>小时聚合</dt><dd>按自然小时汇总的曝光与消耗。</dd>
            <dt>消费去重</dt><dd>Kafka 消费组避免重复处理的记录。</dd>
            <dt>失败消息（DLT）</dt><dd>消费失败最终进入的失败队列，可在这里查看并单条/批量重放。</dd>
            <dt>对账</dt><dd>对比 Redis 累计消耗与当日计费流水：Redis 少记自动修复，多记只告警。</dd>
          </dl>
        </section>

        <section class="help-section">
          <h3>常见问题</h3>
          <dl class="help-glossary">
            <dt>为什么提示“广告不存在”？</dt>
            <dd>广告 ID 必须存在于广告库；兜底广告 {{ FALLBACK_AD_ID }} 不在表中，已删除广告也查不到。可以从“广告管理”复制有效广告 ID。</dd>
            <dt>为什么编辑广告提示 403？</dt>
            <dd>写操作被关闭，或前端来源不在 allowed-origins。检查 bidpulse.console.allow-write 与 allowed-origins。</dd>
            <dt>为什么返回兜底？</dt>
            <dd>没有候选、预算耗尽、依赖故障或超时；这是保底，不是错误。</dd>
            <dt>为什么频次超限后广告还在？</dt>
            <dd>频次超限只跳过当前候选，不会把广告标成已耗尽，其他用户仍可看到。</dd>
          </dl>
        </section>

        <section class="help-section">
          <h3>推荐演示顺序</h3>
          <ol class="help-steps">
            <li>首页 → 确认健康状态和今日请求</li>
            <li>模拟竞价 → 发起一次竞价，讲二价成交</li>
            <li>连打 4 次 → 预算与频次看频次上限</li>
            <li>运行监控 → 暂停消费、看积压、失败消息、对账</li>
            <li>数据查询 → 看底层明细</li>
          </ol>
        </section>
      </div>

      <template #footer>
        <el-button type="primary" @click="visible = false">知道了</el-button>
      </template>
    </el-drawer>
</template>
