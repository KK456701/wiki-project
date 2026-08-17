# 数值运算规范（按需：涉及浮点精度 / 大整数运算时）

> 浮点精度敏感场景优先使用 big.js，大整数使用 BigInt；big.js 未安装时须经用户同意再安装，不自动改动依赖。

## 核心规则

1. **浮点精度敏感运算**（金额、比率、累计求和等）必须使用 `big.js`，禁止用原生 `+ - * /` 直接运算。
   ```typescript
   // ✅ 正确
   import Big from 'big.js';
   const total = new Big(a).plus(b).minus(c).toNumber();
   // ❌ 错误：0.1 + 0.2 === 0.30000000000000004
   const wrong = 0.1 + 0.2;
   ```
2. **大整数（超过 Number.MAX_SAFE_INTEGER）** 使用 `BigInt`，不要用 `number` 存储。
3. **big.js 未安装时**：不要自动 `npm install big.js` 改动依赖，须先向用户说明并经同意。
4. 若项目已有数值工具（如 `src/utils/number.ts`），优先复用，不要重复造轮子（见 `B00-avoid-reinventing-wheel.md`）。

## 适用场景

- 金额加减、百分比计算、税率
- 大量小数累加（统计、报表）
- 时间戳/ID 超过 2^53 的整数

## 禁止的写法

```typescript
// ❌ 浮点直接比较
if (total === 0.3) { ... }   // 可能因精度问题失败
// ❌ 大整数用 number
const id = 9007199254740993; // 丢失精度
```

## 检查清单

- [ ] 浮点运算是否走了 big.js（而非原生运算符）？
- [ ] 大整数是否使用 BigInt？
- [ ] 若 big.js 未安装，是否已征得用户同意再安装？
- [ ] 是否优先复用了项目已有的数值工具？
