import type { KnipConfig } from 'knip';

const config: KnipConfig = {
  entry: ['src/main.ts', 'src/router/index.ts', 'vite.config.ts'],
  project: ['src/**/*.{ts,vue,js}'],
  ignore: [
    'src/**/*.test.ts',
    'src/**/*.spec.ts',
    'src/types/**/*.d.ts',
    'src/mocks/**',
    'prototype/**', // 原型目录，不参与死代码检测
  ],
  ignoreDependencies: ['@types/*'],
};

export default config;
