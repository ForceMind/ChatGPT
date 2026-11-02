import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const folder = path.resolve(process.argv[2] || 'test-results/cpu');
const load = (label) => JSON.parse(readFileSync(path.join(folder, label, 'report.json'), 'utf8'));
const baseline = load('baseline');
const candidate = load('candidate');
if (!baseline.passed || !candidate.passed || !candidate.retention?.passed)
  throw Error('Both complete measurements and the full candidate retention check must pass');
if (
  baseline.sourceSha !== '7ad0219f169be84293abbc9ba669b51743ba574c' ||
  baseline.scriptSha256 !== candidate.scriptSha256 ||
  JSON.stringify(baseline.host) !== JSON.stringify(candidate.host) ||
  baseline.versions.electron !== candidate.versions.electron ||
  baseline.fixture.connectionProtocol !== 'websocket' ||
  candidate.fixture.connectionProtocol !== 'websocket' ||
  baseline.fixture.connectionPeriodMs !== 500 ||
  candidate.fixture.connectionPeriodMs !== 500
)
  throw Error(
    'Baseline SHA, observer script, runner, Electron or fixed WebSocket workload differ; old SSE evidence is not comparable'
  );
if (
  candidate.retention.uniqueConnections !== 8 ||
  candidate.retention.connectionCloses !== 0 ||
  candidate.retention.connectionErrors !== 0
)
  throw Error('Candidate retention requires eight unique WebSocket connections and zero closes/errors');
const comparisons = [];
for (const name of ['idle-1', 'idle-4', 'idle-8', 'streaming-8']) {
  const before = baseline.phases.find((phase) => phase.name === name);
  const after = candidate.phases.find((phase) => phase.name === name);
  if (!before || !after || before.elapsedMs < 120000 || after.elapsedMs < 120000)
    throw Error('Missing a complete 120-second comparison window: ' + name);
  for (const operation of ['pageState', 'readConversation', 'capturePage', 'recoveryWrite', 'recoveryFsync']) {
    if (before.operations[operation] <= 0)
      throw Error('Baseline counter failed its positive control: ' + name + '/' + operation);
  }
  if (after.operations.readRecoveryChanges <= 0) throw Error('Candidate change probe counter is missing: ' + name);
  comparisons.push({
    phase: name,
    baselineCPU: before.cpuPercent,
    candidateCPU: after.cpuPercent,
    cpuMeanDeltaPercentagePoints: after.cpuPercent.mean - before.cpuPercent.mean,
    cpuMeanReductionPercent: before.cpuPercent.mean ? (1 - after.cpuPercent.mean / before.cpuPercent.mean) * 100 : null,
    baselineOperations: before.operations,
    candidateOperations: after.operations,
    baselineMainDelayMs: before.mainProcessDelayMs,
    candidateMainDelayMs: after.mainProcessDelayMs,
    baselineObserver: before.observer,
    candidateObserver: after.observer
  });
}
const instrumented = candidate.phases.find((phase) => phase.name === 'idle-8');
const disabled = candidate.phases.find((phase) => phase.name === 'idle-8-counters-disabled');
if (!disabled || disabled.elapsedMs < 120000 || disabled.countsEnabled)
  throw Error('Candidate counter-disabled control is missing');
const quietValidation = ['idle-1', 'idle-4', 'idle-8'].map((name) => {
  const phase = candidate.phases.find((item) => item.name === name);
  const warmupName = name.replace('idle-', 'warmup-');
  const warmup = candidate.phases.find((item) => item.name === warmupName);
  if (!warmup || warmup.elapsedMs < 5000) throw Error('Separately recorded fixed warmup is missing: ' + warmupName);
  const operations = Object.fromEntries(
    ['pageState', 'readConversation', 'capturePage', 'recoveryWrite', 'recoveryFsync'].map((key) => [
      key,
      phase.operations[key]
    ])
  );
  return {
    phase: name,
    operations,
    expectedEach: 0,
    passed: Object.values(operations).every((count) => count === 0),
    warmup: { name: warmup.name, elapsedMs: warmup.elapsedMs, operations: warmup.operations, rawFile: warmup.rawFile }
  };
});
const quietPassed = quietValidation.every((phase) => phase.passed);
const summary = {
  baselineSHA: baseline.sourceSha,
  candidateSHA: candidate.sourceSha,
  host: candidate.host,
  observerSHA256: candidate.scriptSha256,
  connectionWorkload: {
    protocol: 'websocket',
    periodMs: 500,
    connectionsPerTab: 1,
    previousProtocol: 'HTTP/1.1 SSE; incomplete earlier reports are excluded from this comparison.'
  },
  comparisons,
  quietValidation: { passed: quietPassed, phases: quietValidation },
  observationOverhead: {
    candidateCounterEnabledCPU: instrumented.cpuPercent,
    candidateCounterDisabledCPU: disabled.cpuPercent,
    enabledMinusDisabledMeanCPUPercentagePoints: instrumented.cpuPercent.mean - disabled.cpuPercent.mean,
    directObserverSelfTime: instrumented.observer,
    note: 'Sequential control on one host, not a causal estimate: workload aging and host drift can change the difference. Disabled control retains identical one-second CPU and 100ms delay sampling. Self-time excludes indirect renderer overhead.'
  },
  retention: candidate.retention,
  validationPassed: quietPassed,
  benefitProven: false,
  interpretation:
    'Functional validation is separate from CPU benefit. No benefit threshold was approved; inspect all deltas and raw samples, including regressions, without treating completion as proof of improvement.'
};
writeFileSync(path.join(folder, 'comparison.json'), JSON.stringify(summary, null, 2));
const rows = comparisons.map(
  (item) =>
    `| ${item.phase} | ${item.baselineCPU.mean.toFixed(3)} | ${item.candidateCPU.mean.toFixed(3)} | ${item.cpuMeanDeltaPercentagePoints.toFixed(3)} | ${item.baselineOperations.pageState} / ${item.candidateOperations.pageState} | ${item.baselineOperations.readConversation} / ${item.candidateOperations.readConversation} | ${item.baselineOperations.capturePage} / ${item.candidateOperations.capturePage} | ${item.baselineOperations.recoveryWrite} / ${item.candidateOperations.recoveryWrite} |`
);
const markdown = [
  `CPU comparison: baseline ${baseline.sourceSha}, candidate ${candidate.sourceSha}`,
  '',
  '| Phase | Baseline CPU mean | Candidate CPU mean | Delta pp | pageState B/C | readConversation B/C | capturePage B/C | recoveryWrite B/C |',
  '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |',
  ...rows,
  '',
  `Candidate hidden retention: ${candidate.retention.hiddenMs} ms; identity/draft/connection/counters/zero-send passed.`,
  'Both versions use one independent WebSocket per tab with fixed 500ms server pushes; candidate retention has eight unique connections and zero closes/errors. Earlier incomplete SSE evidence is excluded.',
  `Counter observer enabled minus disabled CPU mean: ${summary.observationOverhead.enabledMinusDisabledMeanCPUPercentagePoints.toFixed(3)} pp (sequential control; not a causal estimate).`,
  `Direct idle-8 observer self-time: ${instrumented.observer.selfTimePercentOfWall.toFixed(5)}% of wall time.`,
  `Candidate idle-1/4/8 zero-repeat gate: ${quietPassed ? 'passed' : 'FAILED'}; pageState/readConversation/capturePage/recoveryWrite/recoveryFsync must each be zero after the separately recorded fixed 5-second warmup.`,
  'Streaming follows the visible conversation tail on every token; this does not measure preserving a manual reading position.',
  '',
  summary.interpretation,
  'Only synthetic localhost pages and fresh test data were used. CPU percentages are Electron process values; no real ChatGPT or personal data was tested.',
  ''
].join('\n');
writeFileSync(path.join(folder, 'comparison.md'), markdown);
console.log(markdown);
if (!quietPassed)
  throw Error(
    'Candidate idle windows repeated full collection, capture or recovery I/O; inspect comparison.json and raw phase evidence'
  );
