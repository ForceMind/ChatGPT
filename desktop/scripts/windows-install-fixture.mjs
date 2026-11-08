// Owned TLS page for native installer checks; never connects to a real website.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, createPrivateKey, X509Certificate } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:https';
import path from 'node:path';

assert.equal(process.env.GITHUB_ACTIONS, 'true');
assert.equal(process.platform, 'win32');
const root = path.resolve(process.argv[2]);
assert(root.toLowerCase().startsWith(path.resolve(process.env.RUNNER_TEMP).toLowerCase() + path.sep));
const read = (file) => JSON.parse(readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const config = { key: path.join(root, 'key.json'), cer: path.join(root, 'cert.cer') };
writeFileSync(path.join(root, 'tls.json'), JSON.stringify(config));
execFileSync('powershell.exe', [
  '-NoProfile',
  '-NonInteractive',
  '-ExecutionPolicy',
  'Bypass',
  '-File',
  path.resolve('scripts/windows-branding-control.ps1'),
  '-Operation',
  'Tls',
  '-Config',
  path.join(root, 'tls.json'),
  '-Output',
  path.join(root, 'thumbprint')
]);
const cert = new X509Certificate(readFileSync(config.cer));
const spki = createHash('sha256')
  .update(cert.publicKey.export({ type: 'spki', format: 'der' }))
  .digest('base64');
const requests = [];
const server = createServer(
  {
    key: createPrivateKey({ key: read(config.key), format: 'jwk' }).export({ type: 'pkcs8', format: 'pem' }),
    cert: cert.toString()
  },
  (request, response) => {
    requests.push({ host: request.headers.host, url: request.url });
    writeFileSync(path.join(root, 'requests.json'), JSON.stringify({ synthetic: true, requests }));
    response.setHeader('Cache-Control', 'no-store');
    if (request.url?.startsWith('/updates/windows/')) {
      response.setHeader('Content-Type', 'application/json');
      response.end(readFileSync('../site/updates/windows/alpha.yml'));
    } else {
      response.setHeader('Content-Type', 'text/html; charset=utf-8');
      response.end(
        '<!doctype html><title>Owned installation fixture</title><textarea id="prompt-textarea"></textarea>'
      );
    }
  }
);
server.listen(443, '127.0.0.1', () => {
  writeFileSync(
    path.join(root, 'ready.json'),
    JSON.stringify({
      arguments: [
        '"--host-resolver-rules=MAP * 127.0.0.1"',
        '--no-proxy-server',
        '--ignore-certificate-errors-spki-list=' + spki
      ]
    })
  );
});
