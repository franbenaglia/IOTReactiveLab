import type { Alert, DeviceView, HistoryPage, MetricsOverview, MonitorSnapshot, TelemetryView } from './types';

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(path, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    // El backend responde ProblemDetail (RFC 9457) con `detail`.
    let detail = res.statusText;
    try {
      const body = (await res.json()) as { detail?: string };
      detail = body.detail ?? detail;
    } catch {
      /* cuerpo vacío o no JSON */
    }
    throw new ApiError(res.status, detail);
  }
  return (await res.json()) as T;
}

const enc = encodeURIComponent;

export const api = {
  devices: () => get<DeviceView[]>('/api/devices'),
  device: (id: string) => get<DeviceView>(`/api/devices/${enc(id)}`),
  latest: (id: string) => get<TelemetryView>(`/api/devices/${enc(id)}/latest`),
  history: (id: string, opts: { from?: string; to?: string; page?: number; size?: number } = {}) => {
    const q = new URLSearchParams();
    Object.entries(opts).forEach(([k, v]) => v !== undefined && q.set(k, String(v)));
    const qs = q.toString();
    return get<HistoryPage>(`/api/devices/${enc(id)}/history${qs ? `?${qs}` : ''}`);
  },
  overview: () => get<MetricsOverview>('/api/metrics/overview'),
  monitor: () => get<MonitorSnapshot>('/api/metrics/monitor'),
  alerts: () => get<Alert[]>('/api/alerts'),
};
