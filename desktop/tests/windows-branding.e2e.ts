import { test, expect } from '@playwright/test';
import { runWindowsBrandingMatrix } from '../scripts/windows-branding-fixture.mjs';
test.use({ screenshot: 'off', trace: 'off', video: 'off' });
test('packaged Windows branding migrates only program paths and rolls back safely', async () => {
  test.skip(process.platform !== 'win32', 'Packaged installer migration requires Windows');
  test.setTimeout(1_200_000);
  const proof = await runWindowsBrandingMatrix();
  expect(proof.complete).toBe(true);
});
