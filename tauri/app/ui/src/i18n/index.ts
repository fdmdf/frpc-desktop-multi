import { ref } from "vue";
import { createI18n } from "vue-i18n";
import zhCN from "./locales/zh-CN.json";
import en from "./locales/en.json";

export type LocaleSetting = "system" | "zh-CN" | "en";
export type Locale = "zh-CN" | "en";

const STORAGE_KEY = "frpc.locale";

/// 跟随系统：WebView / 浏览器语言以 zh 开头用中文，否则英文
export function systemLocale(): Locale {
  const lang = (navigator.language || "zh-CN").toLowerCase();
  return lang.startsWith("zh") ? "zh-CN" : "en";
}

function readSetting(): LocaleSetting {
  const v = localStorage.getItem(STORAGE_KEY);
  return v === "en" || v === "zh-CN" ? v : "system";
}

function resolve(setting: LocaleSetting): Locale {
  return setting === "system" ? systemLocale() : setting;
}

/// 用户在设置页选的值（"system" = 跟随系统）。语言是纯界面偏好，存 localStorage 即可，
/// 不进 settings.json —— 否则改语言要写盘，还要给后端 SettingsData 加字段。
export const localeSetting = ref<LocaleSetting>(readSetting());

export const i18n = createI18n({
  legacy: false,
  locale: resolve(localeSetting.value),
  fallbackLocale: "zh-CN",
  messages: { "zh-CN": zhCN, en },
});

export function applyLocale(setting: LocaleSetting) {
  localeSetting.value = setting;
  if (setting === "system") localStorage.removeItem(STORAGE_KEY);
  else localStorage.setItem(STORAGE_KEY, setting);
  const next = resolve(setting);
  i18n.global.locale.value = next;
  document.documentElement.lang = next;
}

// 选「跟随系统」时，系统语言在运行期变了要跟着切
if (typeof window !== "undefined") {
  window.addEventListener("languagechange", () => {
    if (localeSetting.value === "system") applyLocale("system");
  });
}
