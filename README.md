# Pair-wise GSB

本地、离线的扫频片段拼接工具。它只读取无线测试人员导出的压缩采集片段，拼接可能由时钟漂移、本振漂移和扫频边界切碎的干扰事件；不控制任何发射设备，也不联网识别信号。

## 构建与演示

```bash
mvn -q -DskipTests package
mvn -q test
mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5357
```

页面地址：

```text
http://127.0.0.1:5357
```

打开后先点“载入演示 fixture”，再点“生成自动链接版本”和“发布当前候选”。数据库默认在 `./data/spectrum.db`，可用 `SPECTRUM_DB=/path/to/file.db` 覆盖。

## 数据输入

`POST /api/imports` 接收 JSON，也可以用 `Content-Type: application/gzip` 提交 gzip 压缩 JSON：

```json
{
  "batchId": "batch-001",
  "segments": [
    {
      "deviceId": "scanner-a",
      "startNanos": 1000000000,
      "endNanos": 1090000000,
      "centerFrequencyHz": 100000000,
      "resolutionBandwidthHz": 10000,
      "powerBuckets": [-100, -72, -65],
      "deviceTemperatureC": 31.5,
      "calibrationVersion": "cal-2026-01"
    }
  ]
}
```

导入后，原始功率桶只以 gzip 字节保存，没有更新接口。片段稳定 ID 来自设备、本地时间、中心频率、RBW、温度、标定版本和桶内容的 SHA-256；重复提交同一批次返回同一批次记录，重复片段只计为 `duplicateSegments`，不会重复入库或重复计算。

## 区间口径

所有频率区间和时间区间都采用半开语义：

```text
[low, high)
```

- 桶边界：桶 i 覆盖 `[start + i*rbw, start + (i+1)*rbw)`。
- 片段边界：`[startNanos, endNanos)`，`startNanos < endNanos`。
- 两个恰好相接的区间重叠为 0、缺口为 0，不会把公共边界重复计入能量。
- 候选边必须存在严格大于 0 的校正后真实频率重叠；中心频率相近但区间不相交时不建边。
- 跨扫频边界比较校正后的真实频段与时间范围，不用中心距代理。

事件能量按所有观测边界形成的二维网格积分。若同一时间/频率网格有多个设备或重复片段覆盖，只取最大 dBm 对应的线性功率一次，然后按 `mW × Hz × 秒` 累加。这样跨边界片段可以拼接，但重叠覆盖不会翻倍。

## 校正函数

校正模型是独立版本，不修改原始片段。当前支持的条目字段：

- `deviceId`、`calibrationVersion`
- `frequencyOffsetHz`
- `frequencySlope`：相对校正域中心的比例修正
- `clockOffsetNanos`、`clockRatePpb`
- `temperatureOffsetC`、`temperatureCoefficientHzPerC`、`referenceTemperatureC`
- `domainStartHz`、`domainEndHz`
- `validFromNanos`、`validToNanos`
- `clockUnidentifiable`

频率校正为：

```text
f' = f + frequencyOffsetHz
     + frequencySlope × (f - domainCenterHz)
     + temperatureCoefficientHzPerC
       × (temperature + temperatureOffsetC - referenceTemperatureC)
```

时钟校正为：

```text
t' = t + clockOffsetNanos + t × clockRatePpb / 1_000_000_000
```

若桶或片段频率、时间位于声明域之外，仍允许计算，但观测和边会标记 `extrapolated`，分数中单列校正外推贡献。跨设备链接的任一侧 `clockUnidentifiable=true` 时，边的时钟不可辨识贡献为 1。

页面中的“分段频偏”调用 `POST /api/corrections/device-offset`，它追加一条新的校正模型版本。更换完整模型调用 `POST /api/corrections`。`GET /api/calibrations/{version}/affected` 只列出当前候选中引用该标定版本的事件。

## 自动链接与人工决议

自动链接、校正模型和人工决议分别版本化：

- `POST /api/auto-links`：由当前校正模型重新确定强边，保存新的自动链接版本。
- `GET /api/assembly`：返回校正后观测、候选边、竞争方案和完整证据。
- `POST /api/decisions`：按 `expectedVersion` 追加人工决议。

决议动作：

- `REJECT_EDGE`：拒绝一条边。
- `FORCE_EDGE`：强制保留一条边；对同一 key 的后续强制会覆盖早先拒绝。
- `SPLIT_AT_EDGE`：删除该边以拆分同频同时的两个事件。
- `SELECT_PLAN`：保留一个竞争拼接方案为当前人工选择。

人工决议请求必须带当前版本号。版本过旧时返回 HTTP 409，并在 `decision_conflict` 中独立事务保存双方证据：尝试提交的 JSON 和当前版本 JSON。发布事务中的观测和边证据包含在每个事件里。

## 分数与不确定性

候选边只在真实频率重叠严格大于 0 且时间缺口不超过 `200_000_000 ns` 时生成。

边分数：

```text
edgeScore = 0.40 × frequencyOverlap
          + 0.35 × timeContinuity
          + 0.25 × profileAgreement
```

- `frequencyOverlap`：真实频段重叠除以较窄观测带宽。
- `timeContinuity`：时间重叠时取重叠占较短观测时长比例；有缺口时随缺口线性下降。
- `profileAgreement`：只对真实重叠桶比较功率剖面，权重为重叠 Hz。

四类不确定性分别保存，不合并成一个黑盒分：

- `gapNanos`：时间缺口。
- `saturatedBucketFraction`：功率不低于 `-20 dBm` 的桶比例。
- `extrapolatedBucketFraction`：使用校正域外推的观测比例。
- `clockUnidentifiableFraction`：跨设备且至少一方时钟不可辨识时标记。

事件显示边一致性以及上述四个贡献和总 `uncertaintyPenalty`。阈值为：

- `STRONG`：边分数不低于 `0.72`。
- `ALTERNATIVE`：边分数不低于 `0.45`。
- 低于备选阈值的边保留在 `edges` 输出中，状态为 `BELOW_THRESHOLD`，但不进入方案枚举。

弱备选边按稳定 key 排序，最多取前四条并枚举其组合；空组合是基础方案，所有竞争方案随发布一起保存。

## 指纹与确定拼接

- 原始片段 ID：原始内容 SHA-256。
- 观测 ID：片段 ID、活动桶范围和校正版本的 SHA-256。
- 边 key：两个观测 ID 先按字典序排序后哈希，因此输入顺序不影响 key。
- 方案 ID：被接受的备选边 key 排序列表哈希。
- 事件指纹：方案 ID、观测 ID 列表和边 key 列表哈希。

拼接器先按观测 ID 排序，再生成候选；连通分量和事件也按稳定时间、频率、ID 排序。并行分块后只要合并相同的 ID 集合，就得到相同指纹。事件只比较校正后的真实重叠区间。

## 发布幂等

`POST /api/publications` 在一个数据库事务中写入发布批次、每个竞争方案及方案中的事件。事务失败时不会留下“半拼接”事件。

未指定发布 ID 时，ID 由校正版本、自动链接版本、决议版本、观测几何、候选边和方案集合的规范内容哈希确定。重复发布返回 `idempotentReplay=true` 且事件数一致。也可以在请求体传 `{"id":"..."}` 作为外部幂等键。

## SQLite 表

- `import_batch`：导入批次和源哈希。
- `raw_segment`：元数据与 gzip 原始功率桶。
- `correction_model`：追加式校正模型，仅一个 active。
- `auto_link_version`：自动链接版本及使用的校正版本。
- `manual_decision`：单调递增人工决议版本。
- `decision_conflict`：409 时保留的双方证据。
- `publish_batch`、`published_event`：事务化发布快照及竞争方案。

## 测试 Fixture

`src/main/resources/fixtures/demo-import.json` 覆盖：

- 半开相接频率桶和片段边界。
- A1→A2 的时间缺口。
- scanner-b 的本振频偏与时钟漂移。
- scanner-b 校正域外推和时钟不可辨识。
- A3 的 `-18 dBm` 饱和桶。
- 同一原始片段重复导入。

测试位于：

- `GeometryTest`：半开区间和真实重叠。
- `StitchingEngineTest`：中心相近不可替代区间相交、不确定性分项。
- `SpectrumWorkflowTest`：重复导入、漂移校正、自动链接、409、人工覆盖、饱和、发布幂等。
- `SpectrumApiTest`：浏览器页面和真实 HTTP 工作流。

## 安全边界

该工具是本地只读分析和人工拼接工作台：

- 不发射、不控制发射设备。
- 不做联网信号识别或外部查询。
- 不覆盖原始功率桶。
- 所有模型、自动链接、人工决议都可按版本审计。
