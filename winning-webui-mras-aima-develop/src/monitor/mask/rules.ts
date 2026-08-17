/**
 * 内置脱敏规则库
 *
 * @description 提供 7 条开箱即用的脱敏规则，覆盖中国大陆常见敏感数据类型。
 * 每条规则通过 name 唯一标识，供 MaskConfig 中按名称引用。
 */
import type { MaskRule } from './types';
import { MASK_STRATEGY } from './types';

export const BUILTIN_MASK_RULES: Record<string, MaskRule> = {
  /** 手机号码（中国大陆）：保留前3后4，如 138****1234 */
  phone: {
    name: 'phone',
    pattern: /1[3-9]\d{9}/g,
    strategy: MASK_STRATEGY.KEEP_ENDS,
    replaceValue: { head: 3, tail: 4 },
  },

  /** 身份证号（18位）：保留前4后4，如 1101****1234 */
  idCard: {
    name: 'idCard',
    pattern: /\b\d{6}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]\b/g,
    strategy: MASK_STRATEGY.KEEP_ENDS,
    replaceValue: { head: 4, tail: 4 },
  },

  /** 电子邮箱：保留首字符和域名，如 z***@example.com */
  email: {
    name: 'email',
    pattern: /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g,
    strategy: MASK_STRATEGY.CUSTOM,
    replaceValue: (match: string) => {
      const atIndex = match.indexOf('@');
      if (atIndex <= 1) return match;
      const local = match.substring(0, atIndex);
      const domain = match.substring(atIndex);
      return local[0] + '***' + domain;
    },
  },

  /** JWT Token：完整替换为 [TOKEN] */
  token: {
    name: 'token',
    pattern: /\beyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\b/g,
    strategy: MASK_STRATEGY.REPLACE,
    replaceValue: '[TOKEN]',
  },

  /**
   * URL 查询参数中的敏感凭据
   *
   * 匹配参数名：token | authorization | secret | password |
   * apiKey | api_key | apikey | accessToken | access_token |
   * refreshToken | refresh_token | sessionId | session_id | credential
   */
  urlSecretParam: {
    name: 'urlSecretParam',
    pattern:
      /([?&](?:token|authorization|secret|password|apiKey|api_key|apikey|accessToken|access_token|refreshToken|refresh_token|sessionId|session_id|credential))=([^&\s#]+)/gi,
    strategy: MASK_STRATEGY.CUSTOM,
    replaceValue: (_match: string, prefix: string) => {
      return `${prefix}=***`;
    },
  },

  /** IPv4 地址：保留前两段，如 192.168.*.* */
  ipv4: {
    name: 'ipv4',
    pattern: /\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b/g,
    strategy: MASK_STRATEGY.CUSTOM,
    replaceValue: (_match: string, a: string, b: string) => {
      return `${a}.${b}.*.*`;
    },
  },

  /** 银行卡号（16-19位）：保留后4位，如 ****1234 */
  bankCard: {
    name: 'bankCard',
    pattern: /\b\d{16,19}\b/g,
    strategy: MASK_STRATEGY.KEEP_ENDS,
    replaceValue: { head: 0, tail: 4 },
  },
};
