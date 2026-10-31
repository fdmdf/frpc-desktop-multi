import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import { router } from "./router";
import { applyLocale, i18n, localeSetting } from "./i18n";
import "./styles.css";

// 先落一次语言（同时把 <html lang> 与持久化的值对齐），再挂载应用
applyLocale(localeSetting.value);

createApp(App).use(createPinia()).use(router).use(i18n).mount("#app");
