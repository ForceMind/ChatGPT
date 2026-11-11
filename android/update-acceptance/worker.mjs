const headers = {
  'Cache-Control': 'no-store, no-transform',
  'X-Content-Type-Options': 'nosniff',
  'X-Robots-Tag': 'noindex, noarchive'
};

function unavailable(status = 404) {
  return new Response('Not found', { status, headers });
}

function hex(bytes) {
  return [...new Uint8Array(bytes)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

async function authorized(request, expected) {
  const key = request.headers.get('X-Studio-Acceptance') || '';
  if (!/^[a-f0-9]{64}$/.test(key) || !/^[a-f0-9]{64}$/.test(expected || '')) return false;
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(key));
  const actual = hex(bytes);
  let difference = 0;
  for (let index = 0; index < actual.length; index++) difference |= actual.charCodeAt(index) ^ expected.charCodeAt(index);
  return difference === 0;
}

async function asset(env, request, path) {
  const url = new URL(path, request.url);
  const response = await env.ASSETS.fetch(new Request(url, { headers: { 'Accept-Encoding': 'identity' } }));
  if (response.status !== 200) throw Error('Acceptance asset unavailable');
  return response;
}

async function writePackage(env, request, parts, writable) {
  const writer = writable.getWriter();
  try {
    for (const part of parts) {
      if (!/^\/private\/part-[0-9]{3}\.bin$/.test(part.path) || !Number.isSafeInteger(part.size) || part.size <= 0)
        throw Error('Acceptance part metadata invalid');
      const response = await asset(env, request, part.path);
      const reader = response.body.getReader();
      let received = 0;
      try {
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          received += value.byteLength;
          if (received > part.size) throw Error('Acceptance part has excess bytes');
          await writer.write(value);
        }
        if (received !== part.size) throw Error('Acceptance part is truncated');
      } catch (error) {
        await reader.cancel(error);
        throw error;
      } finally {
        reader.releaseLock();
      }
    }
    await writer.close();
  } catch (error) {
    console.error('Acceptance stream failed', error.message);
    try { await writer.abort(error); }
    catch { /* A disconnected client can have already aborted this writer. */ }
  } finally {
    writer.releaseLock();
  }
}

export default {
  async fetch(request, env, context) {
    const url = new URL(request.url);
    if (request.method !== 'GET' || url.search || !['/android.json', '/update.apk'].includes(url.pathname))
      return unavailable();
    if (!(await authorized(request, env.ACCEPTANCE_CODE_SHA256))) return unavailable();
    try {
      const source = await asset(env, request, '/private/source.json');
      const metadata = await source.json();
      if (!metadata.feed || !Number.isSafeInteger(metadata.feed.size) || metadata.feed.size <= 0
          || !Array.isArray(metadata.parts) || metadata.parts.length === 0 || metadata.parts.length > 48
          || metadata.parts.reduce((total, part) => total + part.size, 0) !== metadata.feed.size)
        throw Error('Acceptance source metadata invalid');
      if (url.pathname === '/android.json')
        return new Response(JSON.stringify(metadata.feed), { headers: { ...headers, 'Content-Type': 'application/json' } });
      const responseHeaders = {
        ...headers,
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="ChatGPT-Studio-' + metadata.feed.versionName + '-android-arm64.apk"'
      };
      let parts = metadata.parts;
      let size = metadata.feed.size;
      let status = 200;
      const requested = request.headers.get('Range');
      if (requested !== null) {
        // Readback can verify the exact same protected bytes in independent part-sized requests.
        let offset = 0;
        const part = parts.find((entry) => {
          const matches = requested === 'bytes=' + offset + '-' + (offset + entry.size - 1);
          if (!matches) offset += entry.size;
          return matches;
        });
        if (!part) return unavailable(416);
        parts = [part];
        size = part.size;
        status = 206;
        responseHeaders['Content-Range'] = 'bytes ' + offset + '-' + (offset + size - 1) + '/' + metadata.feed.size;
      }
      const digestOnly = request.headers.get('X-Studio-Acceptance-Digest') === 'sha256';
      if (digestOnly && status !== 206) return unavailable(416);
      const stream = new FixedLengthStream(size);
      context.waitUntil(writePackage(env, request, parts, stream.writable));
      if (digestOnly) {
        const hasher = new crypto.DigestStream('SHA-256');
        const [, digest] = await Promise.all([stream.readable.pipeTo(hasher), hasher.digest]);
        return new Response(JSON.stringify({ range: responseHeaders['Content-Range'], size, sha256: hex(digest) }),
          { headers: { ...headers, 'Content-Type': 'application/json' } });
      }
      return new Response(stream.readable, { status, headers: responseHeaders });
    } catch {
      return unavailable(503);
    }
  }
};
