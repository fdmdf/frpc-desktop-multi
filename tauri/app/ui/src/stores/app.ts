import { defineStore } from "pinia";

/** 全局应用状态占位：M2 接入 Tauri invoke（get_engine_state / get_settings）。 */
export const useAppStore = defineStore("app", {
  state: () => ({
    engineRunning: false,
    locale: "zh-CN" as "zh-CN" | "en",
  }),
});
