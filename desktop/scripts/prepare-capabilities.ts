import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CapabilityCatalog } from '../src/main/capabilities';

const root = fileURLToPath(new URL('..', import.meta.url));
const source = path.join(root, 'resources/capabilities');
const target = path.join(root, 'dist/capabilities');
const catalog = new CapabilityCatalog(source);
catalog.verifyAll();
for (const file of catalog.packagingFiles()) {
  const destination = path.join(target, ...file.split('/'));
  mkdirSync(path.dirname(destination), { recursive: true });
  writeFileSync(destination, catalog.packagingBytes(file));
}
const snapshot = catalog.snapshot();
console.log(`Built-in capabilities: ${snapshot.skills.length} skills, ${snapshot.agents.length} agents (${snapshot.agents.filter(a => a.internetPriority).length} internet-priority).`);
