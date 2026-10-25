// Tipos de los contratos del backend. Fuentes de verdad:
//   REST:      backend/src/main/java/com/example/iotlab/api/*View.java, MetricsOverview, MonitorSnapshot
//   WebSocket: docs/websocket-contract.md
// Si cambia el contrato, cambia primero el documento, después el backend y después esto.

export type Source = 'PHYSICAL' | 'SIMULATED';
export type DeviceState = 'UNKNOWN' | 'ONLINE' | 'OFFLINE';

export interface DeviceView {
  deviceId: string;
  source: Source | null;
  status: DeviceState;
  statusChangedAt: string | null;
  lastSeen: string | null;
  createdAt: string;
  lastTemperature: number | null;
  lastHumidity: number | null;
  lastMeasuredAt: string | null;
  messagesLastMinute: number | null;
}

export interface TelemetryView {
  deviceId: string;
  temperature: number;
  humidity: number | null;
  measuredAt: string;
  receivedAt: string;
  sequence: number;
}

export interface HistoryPage {
  deviceId: string;
  from: string;
  to: string;
  page: number;
  size: number;
  hasMore: boolean;
  items: TelemetryView[];
}

export interface MetricsOverview {
  mqttConnected: boolean;
  received: number;
  valid: number;
  invalid: number;
  persisted: number;
  duplicates: number;
  failed: number;
  storeRetries: number;
  sequenceResets: number;
  acksSent: number;
  acksPending: number;
  storeInFlight: number;
  latencyP95Millis: number;
  latencyP99Millis: number;
  devicesTotal: number;
  devicesOnline: number;
  devicesOffline: number;
  telemetryRows: number;
  averageTemperature: number | null;
  activeAlerts: number;
}

export interface Alert {
  deviceId: string;
  kind: 'HIGH_TEMPERATURE';
  state: 'RAISED' | 'CLEARED';
  temperature: number;
  threshold: number;
  at: string;
}

/** Un valor ausente significa "métrica no disponible", nunca cero. */
export interface MonitorSnapshot {
  at: string;
  counters: Partial<Record<string, number>>;
  gauges: Partial<Record<string, number>>;
}

// ---------------------------------------------------------------- WebSocket

export interface WsItem extends TelemetryView {
  source: Source;
}

export type ServerMessage =
  | { type: 'subscribed'; devices: '*' | string[]; intervalMs: number }
  | { type: 'snapshot'; items: WsItem[] }
  | { type: 'telemetry'; items: WsItem[] }
  | { type: 'status'; deviceId: string; status: 'ONLINE' | 'OFFLINE'; at: string }
  | ({ type: 'alert' } & Alert)
  | { type: 'pong' }
  | { type: 'error'; message: string };

export type Subscription = { devices: '*' | string[]; intervalMs: number };
