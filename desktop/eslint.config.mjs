import js from '@eslint/js';
import tseslint from 'typescript-eslint';

const globals = (names) => Object.fromEntries(names.map((name) => [name, 'readonly']));
const nodeGlobals = globals([
  'process',
  'console',
  'Buffer',
  'URL',
  'URLSearchParams',
  'setTimeout',
  'clearTimeout',
  'setInterval',
  'clearInterval',
  'setImmediate',
  'performance',
  'structuredClone',
  'fetch',
  'AbortController',
  'TextDecoder',
  'TextEncoder',
  'queueMicrotask',
  '__dirname',
  '__filename',
  'require',
  'module'
]);
// Scripts pass page functions to Playwright/Electron evaluate calls, which run in the browser.
const pageGlobals = globals(['window', 'document', 'getComputedStyle', 'Image']);

export default tseslint.config(
  {
    ignores: [
      'dist/**',
      'release/**',
      'test-results/**',
      'playwright-report/**',
      'resources/**',
      'ui-preview/**',
      '**/._*'
    ]
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    rules: {
      'no-empty': ['error', { allowEmptyCatch: true }],
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', ignoreRestSiblings: true }
      ]
    }
  },
  {
    files: ['scripts/**/*.{mjs,ts}'],
    languageOptions: { globals: { ...nodeGlobals, ...pageGlobals } },
    // hookMain() functions are serialized and run inside the Electron main process.
    rules: { '@typescript-eslint/no-require-imports': 'off' }
  },
  {
    files: ['scripts/**/*.cjs'],
    languageOptions: { sourceType: 'commonjs', globals: nodeGlobals },
    rules: { '@typescript-eslint/no-require-imports': 'off' }
  },
  {
    // Page scripts and Playwright fixtures pass `any`, `{}` and `this` through evaluate/use callbacks by design.
    files: ['tests/**/*.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      'no-empty-pattern': 'off',
      '@typescript-eslint/no-this-alias': 'off'
    }
  },
  {
    // Control characters are filtered on purpose in label and filename validation.
    files: ['src/**/*.{ts,tsx}'],
    rules: { 'no-control-regex': 'off' }
  }
);
