import Papa from 'papaparse';
import { marked } from 'marked';

export function csvRows(input:string):string[][] {
  const result=Papa.parse<string[]>(input,{skipEmptyLines:'greedy',dynamicTyping:false});
  const errors=result.errors.filter(error=>error.code!=='UndetectableDelimiter');
  if(errors.length)throw new Error(`CSV 结构无效：${errors[0].message}`);
  if(!result.data.length)throw new Error('CSV 没有可转换的数据');
  const columns=result.data[0].length;
  if(!columns||result.data.some(row=>row.length!==columns))throw new Error('CSV 各行列数不一致，请先检查原文件');
  return result.data;
}
export function csvToJSON(input:string):string {
  const [fields,...rows]=csvRows(input);
  if(fields.some(f=>!f.trim())||new Set(fields).size!==fields.length)throw new Error('CSV 表头不能为空或重复');
  return JSON.stringify(rows.map(row=>Object.fromEntries(fields.map((field,i)=>[field,row[i]]))),null,2)+'\n';
}
export function jsonToCSV(input:string):string {
  let data:unknown;try{data=JSON.parse(input);}catch{throw new Error('JSON 语法无效，请先检查原文件');}
  if(!Array.isArray(data)||!data.length||data.some(row=>!row||typeof row!=='object'||Array.isArray(row)))throw new Error('JSON 转 CSV 需要非空的对象数组');
  const records=data as Record<string,unknown>[],fields=Object.keys(records[0]);
  if(!fields.length||records.some(row=>Object.keys(row).length!==fields.length||Object.keys(row).some(k=>!fields.includes(k))))throw new Error('JSON 对象字段不一致，请先整理成相同结构');
  if(records.some(row=>Object.values(row).some(v=>v!==null&&(!['string','number','boolean'].includes(typeof v)||typeof v==='number'&&!Number.isFinite(v)))))throw new Error('嵌套对象、数组或非有限数值需要先清洗，不能直接转换为 CSV');
  return '\uFEFF'+Papa.unparse(records,{columns:fields,newline:'\r\n',escapeFormulae:true})+'\r\n';
}
export function markdownHTML(input:string):string {
  const body=marked.parse(input,{async:false,gfm:true});
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data: asset:; font-src data:; script-src 'none'; base-uri 'none'; form-action 'none'"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:-apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;max-width:800px;margin:40px auto;padding:0 24px;color:#222;line-height:1.8}pre{overflow-wrap:anywhere;white-space:pre-wrap;background:#f6f6f6;padding:16px;border-radius:6px}code{font-family:monospace;font-size:.9em}table{width:100%;border-collapse:collapse}td,th{border:1px solid #ddd;padding:8px;text-align:left}img{max-width:100%;height:auto}blockquote{border-left:3px solid #ddd;margin-left:0;padding-left:16px;color:#777}@media print{body{margin:0;max-width:none}pre,blockquote{break-inside:avoid}}@page{size:A4;margin:14mm}</style></head><body>${body}</body></html>`;
}
