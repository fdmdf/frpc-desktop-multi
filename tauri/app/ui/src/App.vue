<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { onBackButtonPress } from "@tauri-apps/api/app";
import { api } from "./api";
import { runBackHandlers } from "./composables/backNav";
import { useEngineStore } from "./stores/engine";
import logo from "./assets/logo.svg";

const route = useRoute();
const router = useRouter();
const store = useEngineStore();
const { t } = useI18n();

// 前台保活只在 Android 有意义：桌面端没有「进程被系统回收」这回事
const isAndroid = /Android/i.test(navigator.userAgent || "");

onMounted(async () => {
  // 先挂返回键，再拉数据：init 慢的时候用户按返回不该直接把应用退掉
  if (isAndroid) void installBackHandler();
  await store.init();
});

/// Android 返回键，逐层收：弹窗 → 页面 → 退后台。
///
/// 1. 有弹窗（编辑代理、删除确认…）先关掉它，否则从编辑页按返回会直接把整个
///    页面掀掉，用户刚填的东西无声丢失；
/// 2. 不在主页就回主页（五个 tab 是平级，没有更细的层级可退）；
/// 3. 已经在主页，要**在 2 秒内连按两次**才退到后台：第一下只提示「再按一次」。
///
/// 为什么主页要两下：这是全应用唯一的「离开」入口，单次误触就退出去太轻率；
/// 而且底部导航紧挨着手势条，从下往上滑、按返回的误操作并不少见。
///
/// 退后台刻意不是 exit / finish：frpc 是应用进程的子进程，进程没了隧道就断。
/// 「退出软件但不是关闭软件」= Activity 和进程都留着，前台服务通知还在，
/// 从最近任务点回来还是原界面。真正的关闭仍然是主页上的「停止」。
async function installBackHandler() {
  try {
    // 注册之后默认行为（WebView 历史回退 / 退出应用）就被接管了，全由 handleBack 决定。
    await onBackButtonPress(() => {
      void handleBack();
    });
  } catch (e) {
    // 拿不到插件时保持系统默认行为（返回即退出），不影响其它功能
    console.warn("onBackButtonPress 不可用，返回键交回系统:", e);
  }
}

// 主页「连按两次」的窗口：与系统常见实现一致（约 2s），再长就容易变成
// 「前后两次点击各算一半」，再短则手感跟不上手速。
const BACK_EXIT_WINDOW_MS = 2000;

/// 主页返回已经按过一下、正等着第二下。
const backArmed = ref(false);
let backTimer: number | undefined;

/// 撤掉「已上膛」状态：超时、切页面、真的退出去了都要清，避免下一次返回
/// 拿着上一次的计数直接退出去。
function disarmBack() {
  backArmed.value = false;
  if (backTimer !== undefined) {
    window.clearTimeout(backTimer);
    backTimer = undefined;
  }
}

async function handleBack() {
  // 弹窗层：关掉最上面那个，不参与退出计数
  if (runBackHandlers()) {
    disarmBack();
    return;
  }
  if (route.path !== "/") {
    disarmBack();
    await router.replace("/");
    return;
  }
  // 主页第一次：只上膛 + 提示，不动界面
  if (!backArmed.value) {
    backArmed.value = true;
    backTimer = window.setTimeout(disarmBack, BACK_EXIT_WINDOW_MS);
    return;
  }
  // 窗口内第二次：退到后台
  disarmBack();
  try {
    await api.moveToBack();
  } catch (e) {
    // 退后台失败不要静默：JNI 的错误串会被原样带上来，devtools 里能看到真因
    console.warn("moveToBack failed:", e);
  }
}

// 切页面就把计数清掉：从别的 tab 回到主页时，不该继承上一次的「第一下」
watch(
  () => route.path,
  () => disarmBack(),
);

/// Android 后台保活：隧道在跑就挂一条常驻通知。
///
/// Tauri 版的 frpc 是应用进程的子进程，父进程被系统回收时隧道直接断掉。
/// 挂上前台服务后，系统会把它当成用户可见的工作，不再按「后台空闲」清理；
/// 停止 / 启动失败时把通知收掉，避免留下「假运行」的常驻通知。
///
/// 通知内容（折叠一行 + 展开多行）也在这里拼：文案要跟随界面语言，放 Kotlin 里就没法
/// 跟着切换了。计时器不在这里算 —— 传启动时刻给系统，由通知栏自己走秒。
watch(
  () => [
    store.runState.phase,
    store.runState.profileName,
    store.runState.startedAtMs,
    store.runState.adminPort,
    store.activeCounts.proxies,
  ] as const,
  ([phase, name]) => {
    if (!isAndroid) return;
    if (phase === "RUNNING") {
      void api
        .setKeepalive(
          true,
          t("keepalive.title"),
          t("keepalive.body", { name: name || "—" }),
          keepaliveLines(),
          store.runState.startedAtMs ?? 0,
        )
        .catch(() => {
          /* 保活失败不影响隧道本身，忽略 */
        });
    } else if (phase === "IDLE" || phase === "ERROR") {
      void api.setKeepalive(false, "", "").catch(() => {});
    }
  },
  { immediate: true },
);

/// 通知展开后的明细：状态 / 配置 / 在线代理数 / 管理端口 / 启动时刻。
/// 只在有意义的项才出，避免出现「管理端口：0」这种噪音。
function keepaliveLines(): string[] {
  const s = store.runState;
  const lines = [
    t("keepalive.lineState", {
      v: s.phase === "STARTING" ? t("keepalive.statusStarting") : t("keepalive.statusRunning"),
    }),
    t("keepalive.lineProfile", { v: s.profileName || "—" }),
  ];
  if (store.activeCounts.proxies > 0) {
    lines.push(t("keepalive.lineProxies", { n: store.activeCounts.proxies }));
  }
  if (s.adminEnabled && s.adminPort) {
    lines.push(t("keepalive.lineAdmin", { port: s.adminPort }));
  }
  if (s.startedAtMs) {
    const at = new Date(s.startedAtMs);
    lines.push(
      t("keepalive.lineStarted", {
        time: `${String(at.getHours()).padStart(2, "0")}:${String(at.getMinutes()).padStart(2, "0")}`,
      }),
    );
  }
  lines.push(t("keepalive.lineHint"));
  return lines;
}

watch(
  () => store.settings?.theme,
  (t) => {
    const dark =
      t === "dark" || (t !== "light" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.dataset.theme = dark ? "dark" : "light";
  },
  { immediate: true },
);

const tabs = computed(
  () =>
    [
      { to: "/", label: t("nav.home"), icon: "home" },
      { to: "/profiles", label: t("nav.profiles"), icon: "dashboard" },
      { to: "/logs", label: t("nav.logs"), icon: "article" },
      { to: "/monitor", label: t("nav.monitor"), icon: "monitor" },
      { to: "/settings", label: t("nav.settings"), icon: "settings" },
    ] as const,
);

const icons: Record<string, string> = {
  home: "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
  dashboard: "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z",
  article:
    "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z",
  monitor:
    "M22 12h-4l-3 9L9 3l-3 9H2v2h5.63l2.37-7.1L13.63 19l3-9H22v2zM11 13h2v6h-2z",
  settings:
    "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6A3.61 3.61 0 0 1 8.4 12c0-1.98 1.62-3.6 3.6-3.6s3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
};
</script>

<template>
  <div class="shell">
    <!-- 移动端顶栏（窄屏才显示）：桌面端用侧栏标题 -->
    <header class="topbar">
      <img class="brand-logo" :src="logo" alt="" />
      <span class="topbar-title">frpc-desktop-multi</span>
      <span v-if="store.appVersion" class="topbar-ver">v{{ store.appVersion }}</span>
    </header>
    <aside class="sidebar">
      <div class="brand">
        <img class="brand-logo" :src="logo" alt="" />
        <span>frpc-desktop-multi</span>
      </div>
      <RouterLink
        v-for="t in tabs"
        :key="t.to"
        :to="t.to"
        class="nav-item"
        :class="{ active: route.path === t.to }"
      >
        <svg viewBox="0 0 24 24" fill="currentColor"><path :d="icons[t.icon]" /></svg>
        <span>{{ t.label }}</span>
      </RouterLink>
      <div class="sidebar-foot">
        {{ $t("app.engineFooter", { version: store.engineVersion }) }}
        <span v-if="store.appVersion"> · v{{ store.appVersion }}</span>
      </div>
    </aside>
    <main class="content">
      <RouterView />
    </main>
    <!-- 主页按了第一下返回：提示再按一次。固定定位、不吃点击，不参与布局 -->
    <div v-if="backArmed" class="toast back-hint">{{ t("app.backAgain") }}</div>
  </div>
</template>
