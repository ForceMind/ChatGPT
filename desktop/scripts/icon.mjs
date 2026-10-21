import { Resvg } from '@resvg/resvg-js';
import { readFileSync, writeFileSync, mkdtempSync, mkdirSync, rmSync, copyFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
const svg = readFileSync('assets/icon.svg');
// Let macOS encode both legacy small icons and Retina representations. Hand-built
// PNG chunks can decode correctly at 512px but render corrupt system thumbnails.
// Stage on APFS so AppleDouble sidecars on external drives cannot enter iconutil.
if (process.platform === 'darwin') {
  const staging = mkdtempSync(path.join(tmpdir(), 'chatgpt-profiles-icons-'));
  try {
    const iconset = path.join(staging, 'icon.iconset');
    mkdirSync(iconset);
    for (const points of [16, 32, 128, 256, 512]) {
      for (const scale of [1, 2]) {
        const png = new Resvg(svg, { fitTo: { mode: 'width', value: points * scale } }).render().asPng();
        writeFileSync(path.join(iconset, `icon_${points}x${points}${scale === 2 ? '@2x' : ''}.png`), png);
      }
    }
    const icns = path.join(staging, 'icon.icns');
    execFileSync('/usr/bin/iconutil', ['-c', 'icns', iconset, '-o', icns]);
    execFileSync('/usr/bin/iconutil', ['-c', 'iconset', icns, '-o', path.join(staging, 'verified.iconset')]);
    copyFileSync(icns, 'assets/icon.icns');
  } finally {
    rmSync(staging, { recursive: true, force: true });
  }
}
writeFileSync('assets/icon.png', new Resvg(svg, { fitTo: { mode: 'width', value: 512 } }).render().asPng());
const frames=[16,24,32,48,64,128,256].map(size=>({size,png:new Resvg(svg,{fitTo:{mode:'width',value:size}}).render().asPng()}));
const icoHeader=Buffer.alloc(6);icoHeader.writeUInt16LE(1,2);icoHeader.writeUInt16LE(frames.length,4);
let offset=6+16*frames.length;
const entries=frames.map(({size,png})=>{const entry=Buffer.alloc(16);entry[0]=entry[1]=size===256?0:size;entry.writeUInt16LE(1,4);entry.writeUInt16LE(32,6);entry.writeUInt32LE(png.length,8);entry.writeUInt32LE(offset,12);offset+=png.length;return entry;});
writeFileSync('assets/icon.ico',Buffer.concat([icoHeader,...entries,...frames.map(f=>f.png)]));
