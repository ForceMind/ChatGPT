import { build as bundle } from 'esbuild';
import { build as viteBuild } from 'vite';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = fileURLToPath(new URL('..', import.meta.url));
await bundle({ entryPoints: [path.join(root, 'src/main/main.ts'), path.join(root, 'src/main/preload.ts')], outdir: path.join(root, 'dist/main'), outExtension: { '.js': '.cjs' }, bundle: true, platform: 'node', format: 'cjs', target: 'node22', external: ['electron'], sourcemap: true });
await viteBuild({ root: path.join(root, 'src/renderer'), base: './', build: { outDir: path.join(root, 'dist/renderer'), emptyOutDir: true } });
