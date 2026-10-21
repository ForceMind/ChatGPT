import assert from 'node:assert/strict';
import { test } from 'node:test';
import { csvRows,csvToJSON,jsonToCSV,markdownHTML } from '../src/shared/formats';

test('CSV keeps quoted separators, line breaks, Unicode and textual values',()=>{
  const input='名称,备注,编号\r\n"例子,一","第一行\n第二行",00123\r\n测试,"包含""引号""",1e2\r\n';
  const result=JSON.parse(csvToJSON(input));
  assert.deepEqual(result,[{'名称':'例子,一','备注':'第一行\n第二行','编号':'00123'},{'名称':'测试','备注':'包含"引号"','编号':'1e2'}]);
  assert.deepEqual(JSON.parse(csvToJSON('名称\n张三\n李四')),[{'名称':'张三'},{'名称':'李四'}]);
  assert.throws(()=>csvToJSON('a,a\n1,2'),/重复/);assert.throws(()=>csvToJSON('a,b\n1'),/列数/);
});
test('JSON to CSV preserves flat field mapping and neutralizes spreadsheet formulas',()=>{
  const csv=jsonToCSV(JSON.stringify([{name:'=IMPORTXML("secret")',number:12,enabled:true},{enabled:false,number:0,name:'普通文本'}]));
  assert.ok(csv.startsWith('\uFEFF'));const rows=csvRows(csv);assert.deepEqual(rows[0],['name','number','enabled']);
  assert.equal(rows[1][0],'\'=IMPORTXML("secret")');assert.deepEqual(rows[2],['普通文本','0','false']);
  assert.throws(()=>jsonToCSV('[{"a":{"nested":1}}]'),/嵌套/);assert.throws(()=>jsonToCSV('[{"a":1},{"b":2}]'),/字段不一致/);assert.throws(()=>jsonToCSV('{"a":1}'),/对象数组/);
});
test('Markdown output is a complete printable document while original content is retained',()=>{
  const md='# 标题\n\n**重点**\n\n|a|b|\n|-|-|\n|1|2|';const result=markdownHTML(md);
  assert.ok(result.startsWith('<!doctype html>'));assert.ok(result.includes('<h1>标题</h1>'));assert.ok(result.includes('<strong>重点</strong>'));assert.ok(result.includes('<table>'));assert.ok(result.includes('@page'));
  assert.ok(md.includes('**重点**'));
});
