import type { MonitorSnapshot } from '../api/types';

/**
 * Tasas por segundo a partir de dos lecturas de contadores acumulados. El backend expone
 * totales (lo que mide realmente); la tasa se deriva acá, sin inventar valores:
 * - Si el contador falta en alguna de las dos lecturas → ausente ("no disponible").
 * - Si bajó (el backend se reinició y los contadores volvieron a cero) → ausente en esa lectura.
 */
export function rates(prev: MonitorSnapshot | undefined, curr: MonitorSnapshot): Partial<Record<string, number>> {
  if (!prev) return {};
  const dt = (Date.parse(curr.at) - Date.parse(prev.at)) / 1000;
  if (!(dt > 0)) return {};
  const out: Partial<Record<string, number>> = {};
  for (const [k, v] of Object.entries(curr.counters)) {
    const before = prev.counters[k];
    if (v === undefined || before === undefined || v < before) continue;
    out[k] = (v - before) / dt;
  }
  return out;
}

export function formatRate(v: number | undefined): string {
  if (v === undefined) return 'no disponible';
  return v >= 100 ? `${Math.round(v)}/s` : `${v.toFixed(1)}/s`;
}

export function formatBytes(v: number | undefined): string {
  if (v === undefined) return 'no disponible';
  const mb = v / (1024 * 1024);
  return mb >= 1024 ? `${(mb / 1024).toFixed(2)} GB` : `${mb.toFixed(0)} MB`;
}

export function formatNumber(v: number | undefined, digits = 0): string {
  return v === undefined ? 'no disponible' : v.toLocaleString('es-AR', { maximumFractionDigits: digits });
}

/** Agrega un punto y conserva solo los últimos `max`: los gráficos en vivo no crecen sin límite. */
export function appendBounded<T>(list: T[], item: T, max: number): T[] {
  const next = list.length >= max ? list.slice(list.length - max + 1) : list.slice();
  next.push(item);
  return next;
}

export function relativeTime(iso: string | null, now = Date.now()): string {
  if (!iso) return '—';
  const s = Math.round((now - Date.parse(iso)) / 1000);
  if (s < 5) return 'ahora';
  if (s < 60) return `hace ${s} s`;
  if (s < 3600) return `hace ${Math.round(s / 60)} min`;
  if (s < 86400) return `hace ${Math.round(s / 3600)} h`;
  return `hace ${Math.round(s / 86400)} d`;
}
