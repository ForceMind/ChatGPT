import {readFileSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const root=path.resolve(process.argv[2]||'test-results/resources');
const currentVersion=JSON.parse(readFileSync(new URL('../package.json',import.meta.url))).version;
const runs={};
for(const label of ['baseline-light','current-light','baseline-conversation','current-conversation','current-retention']){
 const run=JSON.parse(readFileSync(path.join(root,label+'.json'),'utf8'));
 assert.equal(run.label,label);assert.equal(run.site,'local');assert.equal(run.nativeLaunch,true);assert.equal(run.isolatedTestData,true);
 assert.equal(run.applicationVersion,label.startsWith('baseline-')?'3.0.0-alpha.13':currentVersion);
 assert.deepEqual(run.results.map(r=>r.tabs),label.endsWith('retention')?[8]:[1,4,8]);
 for(const phase of run.results){
  assert.equal(phase.views.length,phase.tabs);assert.equal(phase.samples.length,12);
  assert(phase.views.every(v=>!v.throttled&&!v.state.discarded&&v.state.sent===0));
  for(const sample of phase.samples)assert(Number.isFinite(sample.cpu)&&sample.cpu>=0&&Number.isFinite(sample.workingSetKB)&&sample.workingSetKB>0);
 }
 // Retain actual observations without CI filesystem paths or process arguments.
 const {argv,...evidence}=run;runs[label]=evidence;
}
const retention=runs['current-retention'].retention;
assert(retention?.passed&&retention.stable&&retention.active&&retention.durationMs>=330000);
assert.equal(retention.profiles,3);assert.equal(retention.before.length,8);assert.equal(retention.after.length,8);
const comparison=[];
for(const workload of ['light','conversation'])for(const current of runs['current-'+workload].results){
 const baseline=runs['baseline-'+workload].results.find(r=>r.tabs===current.tabs);
 comparison.push({workload,tabs:current.tabs,baselineWorkingSetMiB:baseline.workingSetMiB,currentWorkingSetMiB:current.workingSetMiB,workingSetDeltaPercent:100*(current.workingSetMiB/baseline.workingSetMiB-1),baselineCpuPercent:baseline.cpuPercent,currentCpuPercent:current.cpuPercent,cpuDeltaPercentagePoints:current.cpuPercent-baseline.cpuPercent});
}
const report={schema:1,measuredAt:new Date().toISOString(),currentVersion,currentCommit:process.env.GITHUB_SHA,baselineVersion:'3.0.0-alpha.13',baselineCommit:process.env.BASELINE_COMMIT,ciRun:process.env.GITHUB_REPOSITORY&&process.env.GITHUB_RUN_ID?'https://github.com/'+process.env.GITHUB_REPOSITORY+'/actions/runs/'+process.env.GITHUB_RUN_ID:undefined,host:runs['current-light'].host,comparison,retentionVerified:true,limitations:['Local synthetic pages, no authenticated ChatGPT or model generation','Working-set sum includes shared pages; not unique physical RAM','CPU is the Electron process API sum during idle sampling, not generation peak','Sequential runs on one hosted CI machine; point comparisons are not statistical performance guarantees','No freezing or discarding opened tabs for memory savings'],runs};
writeFileSync(path.join(root,'summary.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({comparison,retentionVerified:true},null,2));
