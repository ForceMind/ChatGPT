const headers = {
  'Cache-Control': 'no-store, no-transform',
  'X-Content-Type-Options': 'nosniff',
  'X-Robots-Tag': 'noindex, noarchive'
};

function unavailable(status = 404) {
  return new Response('Not found', { status, headers });
}

async function authorized(request, expected) {
  const key = request.headers.get('X-Studio-Acceptance') || '';
  if (!/^[a-f0-9]{64}$/.test(key) || !/^[a-f0-9]{64}$/.test(expected || '')) return false;
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(key));
  const actual = [...new Uint8Array(bytes)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
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
  try {
    for (const part of parts) {
      if (!/^\/private\/part-[0-9]{3}\.bin$/.test(part.path) || !Number.isSafeInteger(part.size) || part.size <= 0)
        throw Error('Acceptance part metadata invalid');
      const response = await asset(env, request, part.path);
      if (Number(response.headers.get('Content-Length')) !== part.size) throw Error('Acceptance part size differs');
      await response.body.pipeTo(writable, { preventClose: true });
    }
    await writable.getWriter().close();
  } catch (error) {
    await writable.abort(error);
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
          || !Array.isArray(metadata.parts) || metadata.parts.length === 0 || metadata.parts.length > 32
          || metadata.parts.reduce((total, part) => total + part.size, 0) !== metadata.feed.size)
        throw Error('Acceptance source metadata invalid');
      if (url.pathname === '/android.json')
        return new Response(JSON.stringify(metadata.feed), { headers: { ...headers, 'Content-Type': 'application/json' } });
      if (request.headers.has('Range')) return unavailable(416);
      const stream = new FixedLengthStream(metadata.feed.size);
      context.waitUntil(writePackage(env, request, metadata.parts, stream.writable));
      return new Response(stream.readable, {
        headers: {
          ...headers,
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Disposition': 'attachment; filename="ChatGPT-Studio-' + metadata.feed.versionName + '-android-arm64.apk"'
        }
      });
    } catch {
      return unavailable(503);
    }
  }
};
