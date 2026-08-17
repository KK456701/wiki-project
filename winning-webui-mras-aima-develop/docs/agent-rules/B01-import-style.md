# 导入风格规范（按需：编辑 src/**/*.ts / src/**/*.vue 时）

> 编写 `import` 语句时，按以下优先级选择导入方式。

| 优先级 | 导入方式 | 适用场景 |
|--------|----------|----------|
| 1（最优） | 子路径导入 `import xxx from 'pkg/subpath'` | npm 包支持子路径导出时 |
| 2（推荐） | 具名导入 `import { xxx } from 'yyy'` | 项目内部模块、不支持子路径的 npm 包 |
| 3（禁止） | 命名空间导入 `import * as xxx from 'yyy'` | 除非符合豁免条件，否则禁止 |

## 1. 子路径导入（最优）

对于 **支持子路径导出的 npm 包**，**必须使用子路径导入**。

```typescript
// ✅ 子路径导入（最优）
import cloneDeep from 'lodash-es/cloneDeep';
import debounce from 'lodash-es/debounce';
import { format } from 'date-fns/format';              // date-fns v3+ 支持子路径
import { parseISO } from 'date-fns/parseISO';
```

```typescript
// ❌ 禁止：使用 lodash（CommonJS 包，无法 tree-shaking）
import { cloneDeep } from 'lodash';
// ❌ 不推荐：从 lodash-es 主入口具名导入（tree-shaking 不完全安全）
import { cloneDeep, debounce } from 'lodash-es';
// ❌ 禁止：命名空间导入
import * as _ from 'lodash-es';
```

**常见支持子路径导入的包**：`lodash-es`（`import cloneDeep from 'lodash-es/cloneDeep'`，且**禁止使用 `lodash'`**）、`date-fns` (v3+)、`ramda`（`import curry from 'ramda/es/curry'`）。

**如何判断包是否支持子路径导入**：查看包的 `package.json` 中 `exports` 字段是否定义了子路径映射；或查阅官方文档；或在 `node_modules` 下检查对应子目录/文件。

## 2. 具名导入（推荐）

对于**项目内部模块**（如 `./apis`、`@/utils`）和**不支持子路径导出的 npm 包**，**必须使用** `import { xxx } from 'yyy'` 具名导入。

```typescript
// ✅ 项目内部模块：具名导入
import { queryList, queryStats } from './apis';
// ✅ 不支持子路径的 npm 包：具名导入
import { ref, computed } from 'vue';
import { useRouter } from 'vue-router';
```

```typescript
// ❌ 命名空间导入（禁止）
import * as api from './apis';
const res = await api.queryList(params);
```

## 3. 允许使用 `import * as` 的场景

以下情况**可以**使用命名空间导入，但必须在代码注释中说明原因：

1. 模块导出大量成员且需要全部使用（如大型常量对象、枚举集合）
2. 动态访问导出成员：需要通过字符串键动态访问模块导出时
3. 第三方库要求：某些第三方库的文档明确要求使用命名空间导入

```typescript
// 允许：需要动态访问导出成员
import * as validators from './validators';
const rule = validators[`${field}Validator`];
```

## 检查清单

- [ ] 是否有 `import * as` 的导入语句？如有，是否有充分的理由和注释说明？
- [ ] npm 包是否优先使用了子路径导入（如 `lodash-es/cloneDeep`）？
- [ ] 是否误用了 `lodash`（应使用 `lodash-es`）？
- [ ] 项目内部模块是否使用了具名导入？
- [ ] 具名导入是否列出了所有实际使用的成员？
