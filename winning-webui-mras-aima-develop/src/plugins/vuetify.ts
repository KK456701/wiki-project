import 'vuetify/styles';
import '@mdi/font/css/materialdesignicons.css';
import { createVuetify } from 'vuetify';
import { zhHans } from 'vuetify/locale';
import { aliases, mdi } from 'vuetify/iconsets/mdi';

/**
 * WinDesign Next 色彩体系常量
 *
 * 参考文档：
 * - 主题：http://wued.winning-health.com.cn:8088/win-design-next/zh-CN/component/theming.html
 * - 色彩：http://wued.winning-health.com.cn:8088/win-design-next/zh-CN/component/color.html
 *
 * 默认采用"主题蓝"配色方案，也支持：
 *   - sauce-purple  (山梗紫): primary=#722ED1
 *   - maternity-pink (芙蓉粉): primary=#F24F86
 *   - innovation-green (松柏绿): primary=#1F8970
 *   - calendula (落栗棕): primary=#7B4A36
 */
const WD_COLORS = {
  // ---- 主色：主题蓝 (sauce-blue / default) ----
  primary: '#2D5AFA',
  primaryHover: '#5175F4',
  primaryPress: '#1D39C4',

  // ---- 语义色：辅助色（常量，不随主题切换）----
  success: '#00AB44',
  successHover: '#08C955',
  successPress: '#186C3A',

  warning: '#FF8C00',
  warningHover: '#FFAC48',
  warningPress: '#DB5B03',

  error: '#EC0000',
  errorHover: '#FF5555',
  errorPress: '#B61E1E',

  info: '#999999',
  infoHover: '#B1B1B1',
  infoPress: '#7D7D7D',

  // ---- 文字色 ----
  textPrimary: '#000000',
  textSecondary: '#666666',
  textDisabled: '#999999',
  textWhite: '#FFFFFF',

  // ---- 线条色 ----
  borderPrimary: '#BABABA',
  borderSecondary: '#C9C9C9',
  borderTertiary: '#E9E9E9',

  // ---- 背景色 ----
  bgPage: '#FAFAFA',
  bgWhite: '#FFFFFF',
} as const;

export default createVuetify({
  locale: {
    locale: 'zhHans',
    messages: { zhHans },
  },
  icons: {
    defaultSet: 'mdi',
    aliases,
    sets: {
      mdi,
    },
  },
  theme: {
    defaultTheme: 'light',
    themes: {
      light: {
        colors: {
          // 主色
          primary: WD_COLORS.primary,
          'on-primary': WD_COLORS.textWhite,

          // 辅助色 — WinDesign 无独立 secondary 概念，使用次要文字色作为中性灰
          secondary: WD_COLORS.textSecondary,
          'on-secondary': WD_COLORS.textWhite,

          // 强调色 — WinDesign 顶部导航激活色
          accent: '#41EDFF',
          'on-accent': WD_COLORS.textPrimary,

          // 语义色
          error: WD_COLORS.error,
          'on-error': WD_COLORS.textWhite,

          info: WD_COLORS.info,
          'on-info': WD_COLORS.textWhite,

          success: WD_COLORS.success,
          'on-success': WD_COLORS.textWhite,

          warning: WD_COLORS.warning,
          'on-warning': WD_COLORS.textWhite,

          // 表面 / 背景
          surface: WD_COLORS.bgWhite,
          'on-surface': WD_COLORS.textPrimary,

          background: WD_COLORS.bgPage,
          'on-background': WD_COLORS.textPrimary,

          'surface-variant': '#F5F5F5',
          'on-surface-variant': WD_COLORS.textSecondary,

          // 边框
          outline: WD_COLORS.borderSecondary,
          'outline-variant': WD_COLORS.borderTertiary,
        },
      },
      dark: {
        colors: {
          // 暗色模式下使用 hover 色提亮
          primary: WD_COLORS.primaryHover,
          'on-primary': WD_COLORS.textWhite,

          secondary: WD_COLORS.infoHover,
          'on-secondary': WD_COLORS.textPrimary,

          accent: '#41EDFF',
          'on-accent': WD_COLORS.textPrimary,

          error: WD_COLORS.errorHover,
          'on-error': WD_COLORS.textWhite,

          info: WD_COLORS.infoHover,
          'on-info': WD_COLORS.textPrimary,

          success: WD_COLORS.successHover,
          'on-success': WD_COLORS.textPrimary,

          warning: WD_COLORS.warningHover,
          'on-warning': WD_COLORS.textPrimary,

          surface: '#1E1E1E',
          'on-surface': '#E0E0E0',

          background: '#121212',
          'on-background': '#E0E0E0',

          'surface-variant': '#2C2C2C',
          'on-surface-variant': '#B1B1B1',

          outline: '#3D3D3D',
          'outline-variant': '#2C2C2C',
        },
      },
    },
  },
});
