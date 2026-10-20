import { Resvg } from '@resvg/resvg-js';
import { readFileSync, writeFileSync } from 'node:fs';
const svg = readFileSync('assets/icon.svg');
const blocks = [['icp4',16],['icp5',32],['icp6',64],['ic07',128],['ic08',256],['ic09',512],['ic10',1024]].map(([kind,size])=>{
  const png=new Resvg(svg,{fitTo:{mode:'width',value:size}}).render().asPng();
  const header=Buffer.alloc(8);header.write(kind,0,'ascii');header.writeUInt32BE(png.length+8,4);return Buffer.concat([header,png]);
});
const header=Buffer.alloc(8);header.write('icns',0,'ascii');header.writeUInt32BE(8+blocks.reduce((n,b)=>n+b.length,0),4);
writeFileSync('assets/icon.icns',Buffer.concat([header,...blocks]));
writeFileSync('assets/icon.png', new Resvg(svg, { fitTo: { mode: 'width', value: 512 } }).render().asPng());
