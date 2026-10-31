import { createI18n } from "vue-i18n";
import zhCN from "./locales/zh-CN.json";
import en from "./locales/en.json";

// 语言持久化与跟随系统在 M2 接入设置存储（对齐 SettingsScreen.kt 的语言选项）
export const i18n = createI18n({
  legacy: false,
  locale: "zh-CN",
  fallbackLocale: "en",
  messages: { "zh-CN": zhCN, en },
});
