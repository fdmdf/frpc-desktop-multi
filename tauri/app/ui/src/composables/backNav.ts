import { onUnmounted } from "vue";

/// Android 返回键的分层处理。
///
/// 系统返回键只有一个「按了什么」的信号，但界面上同时存在三层东西：最上面的弹窗、
/// 当前所在的页面、以及「再退就没有了」。由 App.vue 统一监听，页面按需注册自己
/// 那一层 —— 谁都不必知道别的页面在干什么。
///
/// 处理函数返回 true 表示这次返回已经消费掉，不再往下传。
export type BackHandler = () => boolean;

// 后注册的先执行（弹窗级 > 页面级）。目前各页面的弹窗都收在自己组件里靠状态判断，
// 顺序其实不影响结果，但按「后进先出」组织才和 DOM 的层级直觉一致。
const handlers: BackHandler[] = [];

/** 在页面组件里注册一个返回处理函数，组件卸载时自动摘除。 */
export function useBackHandler(handler: BackHandler): void {
  handlers.push(handler);
  onUnmounted(() => {
    const i = handlers.indexOf(handler);
    if (i >= 0) handlers.splice(i, 1);
  });
}

/** 依次尝试已注册的处理器；返回 true 表示这次返回键不需要再管了。 */
export function runBackHandlers(): boolean {
  for (let i = handlers.length - 1; i >= 0; i--) {
    if (handlers[i]()) return true;
  }
  return false;
}
