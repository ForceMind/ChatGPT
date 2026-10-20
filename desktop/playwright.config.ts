import { defineConfig } from '@playwright/test';
export default defineConfig({testDir:'./tests',testMatch:'**/*.e2e.ts',testIgnore:['**/._*'],workers:1,fullyParallel:false,timeout:90_000,expect:{timeout:15_000},reporter:[['list'],['html',{open:'never'}]],outputDir:'test-results'});
