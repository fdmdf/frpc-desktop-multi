<script setup lang="ts">
import { computed } from "vue";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();

const history = computed(() => store.metricsHistory.slice(-60));

function cpuPolyline(): string {
  const h = history.value;
  if (h.length < 2) return "";
  const w = 300;
  const ht = 80;
  return h
    .map((p, i) => {
      const x = (i / (h.length - 1)) * w;
      const y = ht - ((p.cpu ?? 0) / 100) * ht;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
}

function rssPolyline(): string {
  const h = history.value;
  if (h.length < 2) return "";
  const max = Math.max(...h.map((p) => p.rssKb ?? 0), 1);
  const w = 300;
  const ht = 80;
  return h
    .map((p, i) => {
      const x = (i / (h.length - 1)) * w;
      const y = ht - ((p.rssKb ?? 0) / max) * ht;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
}

const statusRows = computed<{ name: string; type: string; status: string; err: string; remoteAddr?: string }[]>(() => {
  const snap = store.status;
  if (!snap) return [];
  return Object.entries(snap.byType).flatMap(([type, items]) =>
    items.map((i) => ({ ...i, typeKey: type })),
  );
});

function fmtKb(kb?: number | null): string {
  if (!kb) return "—";
  return kb >= 1024 ? `${(kb / 1024).toFixed(1)} MB` : `${kb} KB`;
}
</script>

<template>
  <section class="view">
    <h2>{{ $t("nav.monitor") }}</h2>

    <div class="grid-2">
      <div class="card">
        <h3>{{ $t("monitor.cpu") }}</h3>
        <p class="metric-big">{{ store.metrics?.cpuPercent != null ? store.metrics.cpuPercent.toFixed(1) + "%" : "—" }}</p>
        <svg v-if="history.length > 1" viewBox="0 0 300 80" class="spark">
          <polyline :points="cpuPolyline()" fill="none" stroke="#0f6e56" stroke-width="1.5" />
        </svg>
      </div>
      <div class="card">
        <h3>{{ $t("monitor.memory") }}</h3>
        <p class="metric-big">{{ fmtKb(store.metrics?.rssKb) }}</p>
        <svg v-if="history.length > 1" viewBox="0 0 300 80" class="spark">
          <polyline :points="rssPolyline()" fill="none" stroke="#185fa5" stroke-width="1.5" />
        </svg>
      </div>
    </div>

    <div class="card">
      <p class="muted small">
        PID {{ store.metrics?.pid ?? "—" }} · {{ $t("monitor.threads") }} {{ store.metrics?.threads ?? "—" }}
        <span v-if="!store.metrics"> · {{ $t("monitor.noData") }}</span>
      </p>
    </div>

    <div class="card">
      <h3>{{ $t("monitor.statusTable") }}</h3>
      <p v-if="!statusRows.length" class="muted">{{ $t("monitor.statusTableNone") }}</p>
      <table v-else class="table">
        <thead>
          <tr>
            <th>{{ $t("monitor.column.name") }}</th>
            <th>{{ $t("monitor.column.type") }}</th>
            <th>{{ $t("monitor.column.status") }}</th>
            <th>{{ $t("monitor.column.remote") }}</th>
            <th>{{ $t("monitor.column.err") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in statusRows" :key="row.type + row.name">
            <td><strong>{{ row.name }}</strong></td>
            <td>{{ row.type }}</td>
            <td>
              <span class="pill" :class="row.status === 'running' ? 'ok' : 'bad'">{{ row.status }}</span>
            </td>
            <td class="muted small">{{ row.remoteAddr || "—" }}</td>
            <td class="error-text small">{{ row.err || "" }}</td>
          </tr>
        </tbody>
      </table>
      <p class="muted small" v-if="store.status">
        {{ new Date(store.status.fetchedAtEpochMs).toLocaleTimeString() }}
      </p>
    </div>
  </section>
</template>
