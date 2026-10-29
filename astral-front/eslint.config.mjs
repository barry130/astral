import eslint from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  // 全局忽略
  {
    ignores: [
      '.next/**',
      'node_modules/**',
      'out/**',
      'build/**',
      'next-env.d.ts',
      'postcss.config.mjs',
      'next.config.js',
    ],
  },
  // 基础 JS/TS 规则
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  {
    rules: {
      // ── TypeScript 严格度 ──
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      'prefer-const': 'error',
      'no-var': 'error',
      // ── 常见 bug 防线 ──
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      'no-debugger': 'error',
      'no-alert': 'error',
      eqeqeq: ['error', 'smart'],
      'no-duplicate-imports': 'error',
      'object-shorthand': 'warn',
    },
  },
  // React 规则（.tsx 文件）
  {
    files: ['**/*.tsx', '**/*.jsx'],
    plugins: {
      'react-hooks': reactHooks,
    },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
    },
  },
  // 兼容层允许 any（antd-compat 是历史 API 映射层，不改签名）
  {
    files: ['src/components/antd-compat/**/*.tsx'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unsafe-function-type': 'off',
    },
  },
  // UI 原子组件允许 any（Radix 包装，泛型约束繁琐且不承担业务）
  {
    files: ['src/components/ui/**/*.tsx'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
  // 测试/脚本文件允许 console
  {
    files: ['**/*.test.*', '**/*.spec.*', 'scripts/**/*'],
    rules: {
      'no-console': 'off',
    },
  },
);
