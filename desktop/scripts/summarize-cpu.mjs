import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const folder = path.resolve(process.argv[2] || 'test-results/cpu');
const baselineSHA = '462d4f3c9fea960fcdba0cd96cdf039d5cf8a24c';
const names = ['forward-baseline', 'forward-candidate', 'reverse-candidate', 'reverse-baseline'];
const reports = names.map((name) => ({
  name,
  report: JSON.parse(readFileSync(path.join(folder, name, 'report.json'), 'utf8'))
}));
const [forwardBaseline, forwardCandidate, reverseCandidate, reverseBaseline] = reports.map((item) => item.report);
const candidateSHA = forwardCandidate.sourceSha;
const requireCondition = (condition, message) => {
  if (!condition) throw Error(message);
};
const phase = (report, name) => {
  const found = report.phases.find((item) => item.name === name);
  requireCondition(found, 'Missing phase: ' + report.label + '/' + name);
  return found;
};
const verifyPages = (pages, expected) => {
  requireCondition(pages?.length === expected, 'Missing native pages');
  requireCondition(
    new Set(pages.map((page) => page.state.connection)).size === expected,
    'Connections are not independent'
  );
  for (const page of pages) {
    const state = page.state;
    requireCondition(
      state.ready &&
        !page.throttled &&
        !state.discarded &&
        state.sent === 0 &&
        state.connectionOpen &&
        state.connections === 1 &&
        state.connectionCloses === 0 &&
        state.connectionErrors === 0 &&
        state.connectionIdentityChanges === 0,
      'Page/connection invariant failed'
    );
  }
};
const verifyTokens = (pages) => {
  verifyPages(pages, 8);
  for (const { state } of pages)
    requireCondition(
      state.tokens === 1040 &&
        state.tailTokens.length === 1040 &&
        state.tailTokens.every((token, index) => token === index) &&
        Math.abs(state.tailGap) <= 2 &&
        Number.isFinite(state.streamDurationMs) &&
        state.streamDurationMs >= 130000 &&
        state.streamDurationMs <= 135000,
      'Incomplete fixed 1040-token/135-second streaming workload'
    );
};
for (let index = 0; index < reports.length; index++) {
  const { name, report } = reports[index];
  const full = name.startsWith('forward-');
  const baseline = name.endsWith('baseline');
  requireCondition(
    report.schema === 2 && report.passed && report.settlement?.passed && report.isolatedTestData && report.nativeLaunch,
    'Incomplete or failed run: ' + name
  );
  requireCondition(
    report.label === (baseline ? 'baseline' : 'candidate') &&
      report.sourceSha === (baseline ? baselineSHA : candidateSHA) &&
      /^[a-f0-9]{40}$/.test(report.sourceSha),
    'Wrong source identity: ' + name
  );
  if (process.env.GITHUB_SHA)
    requireCondition(candidateSHA === process.env.GITHUB_SHA, 'Candidate is not this workflow SHA');
  requireCondition(
    report.profile === (full ? 'full' : 'stream-only') &&
      report.instrumentation === (full ? 'on' : 'off') &&
      report.fullRegression === full,
    'Control cannot replace full regression: ' + name
  );
  requireCondition(
    report.scriptSha256 === forwardBaseline.scriptSha256 &&
      report.dependenciesSHA256 === forwardBaseline.dependenciesSHA256 &&
      JSON.stringify(report.versions) === JSON.stringify(forwardBaseline.versions) &&
      JSON.stringify(report.host) === JSON.stringify(forwardBaseline.host),
    'Source-independent observer/runtime/dependencies/host differ: ' + name
  );
  requireCondition(
    report.fixture.connectionProtocol === 'websocket' &&
      report.fixture.connectionPeriodMs === 500 &&
      report.fixture.tokensPerTab === 1040 &&
      report.fixture.tokenPeriodMs === 125,
    'Old or different fixture workload: ' + name
  );
  requireCondition(
    Number.isFinite(report.launchStartedAt) &&
      Number.isFinite(report.completedAt) &&
      report.completedAt > report.launchStartedAt,
    'Missing run timestamps: ' + name
  );
  if (index)
    requireCondition(
      reports[index - 1].report.completedAt <= report.launchStartedAt,
      'Forward/reverse execution order differs'
    );
  const preparation = phase(report, 'prepare-stream');
  requireCondition(
    report.preparation?.capture?.success &&
      report.preparation.capture.startedAtMs >= report.preparation.markerCompletedAtMs &&
      report.preparation.replacement.atMs >= report.preparation.capture.completedAtMs &&
      preparation.endedAtMs >= report.preparation.alignedAtMs + 1000,
    'Preparation did not align fresh snapshot success and recovery replacement: ' + name
  );
  if (full) {
    requireCondition(existsSync(path.join(folder, name, 'timeline.json')), 'Forward timeline missing');
    for (const operation of [
      'pageState',
      'readConversation',
      'readRecoveryChanges',
      'capturePage',
      'captureCompleted',
      'recoveryWrite',
      'recoveryWriteCompleted',
      'recoveryFsync',
      'recoveryRename',
      'recoveryBytes'
    ])
      requireCondition(
        preparation.operations?.[operation] > 0,
        'Preparation positive control failed: ' + name + '/' + operation
      );
    for (const count of [1, 4, 8]) {
      requireCondition(phase(report, 'warmup-' + count).elapsedMs >= 5000, 'Fixed warmup missing');
      requireCondition(phase(report, 'idle-' + count).elapsedMs >= 120000, '120-second idle window missing');
      requireCondition(
        phase(report, 'idle-' + count).operations?.readRecoveryChanges > 0,
        'Idle probe observation missing'
      );
    }
  } else {
    requireCondition(
      report.totals === null &&
        !report.retention &&
        !existsSync(path.join(folder, name, 'timeline.json')) &&
        report.phases.every((item) => item.operations === null),
      'Control operations must be null/unobserved'
    );
    requireCondition(
      !report.phases.some((item) => item.name.startsWith('idle-') || item.name === 'hidden-8'),
      'Stream-only control repeated full regression phases'
    );
  }
  for (const item of report.phases) {
    verifyPages(item.before, item.tabs);
    verifyPages(item.after, item.tabs);
    requireCondition(
      item.before.every((before) =>
        item.after.some(
          (after) =>
            before.id === after.id &&
            before.pid === after.pid &&
            before.state.instance === after.state.instance &&
            before.state.connection === after.state.connection &&
            before.state.draft === after.state.draft
        )
      ),
      'Measured stage reloaded/swapped a page or lost its draft'
    );
  }
  const lifecycle = phase(report, 'streaming-lifecycle-8');
  const [stream, settle] = lifecycle.segments || [];
  requireCondition(
    stream?.name === 'streaming-8' &&
      stream.elapsedMs >= 135000 &&
      settle?.name === 'settle-8' &&
      settle.elapsedMs >= 45000 &&
      stream.endedAtMs === settle.startedAtMs &&
      lifecycle.elapsedMs >= 180000,
    'Continuous 135+45-second lifecycle is missing'
  );
  verifyTokens(stream.after);
  verifyTokens(settle.after);
  requireCondition(
    Number.isFinite(lifecycle.cpuPercentageSeconds) &&
      Math.abs(lifecycle.cpuPercentageSeconds - stream.cpuPercentageSeconds - settle.cpuPercentageSeconds) < 0.000001,
    'Lifecycle CPU integral does not include both segments'
  );
  if (full) {
    for (const operation of [
      'readConversation',
      'capturePage',
      'captureCompleted',
      'recoveryWrite',
      'recoveryWriteCompleted',
      'recoveryFsync',
      'recoveryRename',
      'recoveryBytes'
    ])
      requireCondition(
        lifecycle.operations?.[operation] > 0,
        'Changed lifecycle positive control failed: ' + name + '/' + operation
      );
    for (const key of Object.keys(lifecycle.operations))
      requireCondition(
        lifecycle.operations[key] === stream.operations[key] + settle.operations[key],
        'Lifecycle operation was omitted across the split: ' + key
      );
  } else
    requireCondition(
      stream.operations === null && settle.operations === null,
      'Off control fabricated zero operations'
    );
}
requireCondition(candidateSHA !== baselineSHA, 'Candidate equals the baseline');
requireCondition(
  forwardCandidate.retention?.passed &&
    forwardCandidate.retention.hiddenMs >= 1800000 &&
    forwardCandidate.retention.uniqueConnections === 8 &&
    forwardCandidate.retention.connectionCloses === 0 &&
    forwardCandidate.retention.connectionErrors === 0,
  'Full candidate 30-minute restoration failed'
);

const quietValidation = [forwardBaseline, forwardCandidate].flatMap((report) =>
  [1, 4, 8].map((count) => {
    const item = phase(report, 'idle-' + count);
    const operations = Object.fromEntries(
      [
        'pageState',
        'readConversation',
        'capturePage',
        'recoveryWrite',
        'recoveryFsync',
        'recoveryRename',
        'recoveryBytes'
      ].map((key) => [key, item.operations[key]])
    );
    return {
      phase: report.label + '/' + item.name,
      operations,
      expectedEach: 0,
      passed: Object.values(operations).every((value) => value === 0),
      warmup: phase(report, 'warmup-' + count)
    };
  })
);
const quietPassed = quietValidation.every((item) => item.passed);
const compare = (baseline, candidate, title) => ({
  phase: title,
  baseline: {
    elapsedMs: baseline.elapsedMs,
    cpuPercent: baseline.cpuPercent,
    cpuTimeWeightedMeanPercent: baseline.cpuTimeWeightedMeanPercent,
    cpuPercentageSeconds: baseline.cpuPercentageSeconds,
    operations: baseline.operations,
    observer: baseline.observer,
    mainProcessDelayMs: baseline.mainProcessDelayMs
  },
  candidate: {
    elapsedMs: candidate.elapsedMs,
    cpuPercent: candidate.cpuPercent,
    cpuTimeWeightedMeanPercent: candidate.cpuTimeWeightedMeanPercent,
    cpuPercentageSeconds: candidate.cpuPercentageSeconds,
    operations: candidate.operations,
    observer: candidate.observer,
    mainProcessDelayMs: candidate.mainProcessDelayMs
  },
  cpuMeanDeltaPercentagePoints: candidate.cpuTimeWeightedMeanPercent - baseline.cpuTimeWeightedMeanPercent,
  cpuIntegralDeltaPercentageSeconds: candidate.cpuPercentageSeconds - baseline.cpuPercentageSeconds
});
const lifecycleComparisons = (baseline, candidate) => {
  const before = phase(baseline, 'streaming-lifecycle-8'),
    after = phase(candidate, 'streaming-lifecycle-8');
  return [
    compare(before.segments[0], after.segments[0], 'streaming-8'),
    compare(before.segments[1], after.segments[1], 'settle-8'),
    compare(before, after, 'streaming-lifecycle-8')
  ];
};
const forward = [
  ...[1, 4, 8].map((count) =>
    compare(phase(forwardBaseline, 'idle-' + count), phase(forwardCandidate, 'idle-' + count), 'idle-' + count)
  ),
  compare(phase(forwardBaseline, 'prepare-stream'), phase(forwardCandidate, 'prepare-stream'), 'prepare-stream'),
  ...lifecycleComparisons(forwardBaseline, forwardCandidate)
];
const reverse = [
  compare(phase(reverseBaseline, 'prepare-stream'), phase(reverseCandidate, 'prepare-stream'), 'prepare-stream'),
  ...lifecycleComparisons(reverseBaseline, reverseCandidate)
];
const idleControl = phase(forwardCandidate, 'idle-8-counters-disabled');
requireCondition(
  idleControl.elapsedMs >= 120000 && idleControl.operations === null && !idleControl.countsEnabled,
  'Candidate idle counter-disabled observation control is missing'
);
const summary = {
  schema: 2,
  baselineSHA,
  candidateSHA,
  observerSHA256: forwardBaseline.scriptSha256,
  dependenciesSHA256: forwardBaseline.dependenciesSHA256,
  host: forwardBaseline.host,
  executionOrder: names,
  forward,
  reverse,
  preparation: Object.fromEntries(
    reports.map(({ name, report }) => [
      name,
      {
        alignment: report.preparation,
        measured: phase(report, 'prepare-stream')
      }
    ])
  ),
  settlement: Object.fromEntries(reports.map(({ name, report }) => [name, report.settlement])),
  quietValidation: { passed: quietPassed, phases: quietValidation },
  observationControl: {
    note: 'Reverse candidate then baseline disables operation counters/timeline, not App observers, background policy, WebSocket or CPU/delay sampling. Null means unobserved, never zero. Thin completion/probe/egress wrappers remain. Order and instrumentation both differ, so their individual causal effects cannot be separated.',
    candidateIdleControl: { enabled: phase(forwardCandidate, 'idle-8'), disabled: idleControl },
    deltas: reverse.map((item) => ({
      phase: item.phase,
      forwardCandidateMean: forward.find((entry) => entry.phase === item.phase).candidate.cpuTimeWeightedMeanPercent,
      reverseCandidateMean: item.candidate.cpuTimeWeightedMeanPercent,
      forwardBaselineMean: forward.find((entry) => entry.phase === item.phase).baseline.cpuTimeWeightedMeanPercent,
      reverseBaselineMean: item.baseline.cpuTimeWeightedMeanPercent
    }))
  },
  retention: forwardCandidate.retention,
  validationPassed: quietPassed,
  benefitProven: false,
  units:
    'CPU integral: Electron percentage-seconds, not core-seconds. Mean: sum of Electron process percentCPUUsage, time weighted.',
  interpretation:
    'Full regression and stream-only controls are distinct. Completion is not proof of CPU benefit. Preserve adverse forward/reverse/lifecycle/preparation results; no benefit threshold was approved.'
};
writeFileSync(path.join(folder, 'comparison.json'), JSON.stringify(summary, null, 2));
const row = (order, item) =>
  '| ' +
  order +
  ' | ' +
  item.phase +
  ' | ' +
  item.baseline.cpuTimeWeightedMeanPercent.toFixed(3) +
  ' | ' +
  item.candidate.cpuTimeWeightedMeanPercent.toFixed(3) +
  ' | ' +
  item.cpuMeanDeltaPercentagePoints.toFixed(3) +
  ' | ' +
  item.baseline.cpuPercentageSeconds.toFixed(3) +
  ' | ' +
  item.candidate.cpuPercentageSeconds.toFixed(3) +
  ' | ' +
  (item.baseline.operations === null ? 'unobserved (null)' : JSON.stringify(item.baseline.operations)) +
  ' | ' +
  (item.candidate.operations === null ? 'unobserved (null)' : JSON.stringify(item.candidate.operations)) +
  ' |';
const markdown = [
  'CPU comparison: baseline ' + baselineSHA + ', candidate ' + candidateSHA,
  '',
  '| Order | Phase | Baseline mean CPU | Candidate mean CPU | Delta pp | Baseline %-s | Candidate %-s | Baseline operations | Candidate operations |',
  '| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |',
  ...forward.map((item) => row('forward / counters on', item)),
  ...reverse.map((item) => row('reverse / counters off', item)),
  '',
  summary.units,
  'Snapshot preparation is separately measured and retained; it waits for a fresh active-page capture and matching encrypted .bin replacement, then the same 1-second wait. No reload aligns phases.',
  'The identical 240-message rebuild and visible preparation marker are charged to prepare-stream above, with CPU and operations shown for both versions and both execution orders.',
  'Lifecycle starts before the first page starts generating; the same sampler covers 135 seconds plus at least 45 seconds of settlement without a reset or intervening report write. Final active-page capture/replacement/probe versions must settle before the deadline.',
  'Streaming follows the visible tail. Every page independently retains the complete 0..1039 DOM-token sequence, unchanged identity/draft and one 500ms WebSocket with zero close/error/reconnect/send.',
  'Forward baseline/candidate idle zero-repeat gate: ' +
    (quietPassed ? 'passed' : 'FAILED') +
    '. Full candidate background retention: ' +
    forwardCandidate.retention.hiddenMs +
    ' ms.',
  summary.observationControl.note,
  summary.interpretation,
  'Synthetic localhost pages only. No real ChatGPT or personal data was tested.',
  ''
].join('\n');
writeFileSync(path.join(folder, 'comparison.md'), markdown);
console.log(markdown);
if (!quietPassed)
  throw Error('Forward idle windows repeated collection/capture/recovery I/O; inspect complete evidence');
