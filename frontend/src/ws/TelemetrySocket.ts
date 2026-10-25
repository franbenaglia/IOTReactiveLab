import type { ServerMessage, Subscription } from '../api/types';

export type SocketState = 'connecting' | 'open' | 'closed';

export interface SocketOptions {
  /** Inyectable para pruebas. */
  WebSocketImpl?: typeof WebSocket;
  minDelayMs?: number;
  maxDelayMs?: number;
  /** Cada cuánto se envía ping. Sin ningún mensaje en 2 períodos, la conexión se da por muerta. */
  pingMs?: number;
}

/**
 * Cliente de /ws/telemetry (docs/websocket-contract.md).
 *
 * - Reconecta solo, con espera exponencial (1 s, 2 s, 4 s… hasta 30 s).
 * - Recuerda la suscripción y la reenvía al reconectar: el servidor no guarda estado entre
 *   conexiones.
 * - Heartbeat: un TCP muerto puede tardar minutos en notarse. Si no llega nada (ni datos ni
 *   pong) en 2 × pingMs, cierra y reconecta.
 */
export class TelemetrySocket {
  private ws: WebSocket | null = null;
  private subscription: Subscription | null = null;
  private listeners = new Set<(m: ServerMessage) => void>();
  private stateListeners = new Set<(s: SocketState) => void>();
  private attempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private lastMessageAt = 0;
  private stopped = false;
  private _state: SocketState = 'closed';
  private readonly opts: Required<SocketOptions>;

  constructor(private readonly url: string, opts: SocketOptions = {}) {
    this.opts = {
      WebSocketImpl: opts.WebSocketImpl ?? WebSocket,
      minDelayMs: opts.minDelayMs ?? 1000,
      maxDelayMs: opts.maxDelayMs ?? 30000,
      pingMs: opts.pingMs ?? 15000,
    };
  }

  get state(): SocketState {
    return this._state;
  }

  connect(): void {
    this.stopped = false;
    this.open();
  }

  close(): void {
    this.stopped = true;
    this.clearTimers();
    this.ws?.close();
    this.ws = null;
    this.setState('closed');
  }

  subscribe(sub: Subscription): void {
    this.subscription = sub;
    this.sendSubscription();
  }

  onMessage(l: (m: ServerMessage) => void): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  onState(l: (s: SocketState) => void): () => void {
    this.stateListeners.add(l);
    return () => this.stateListeners.delete(l);
  }

  /** Espera antes del intento n (0, 1, 2…): exponencial y acotada. */
  static delay(attempt: number, min: number, max: number): number {
    return Math.min(max, min * 2 ** attempt);
  }

  private open(): void {
    this.setState('connecting');
    const ws = new this.opts.WebSocketImpl(this.url);
    this.ws = ws;
    ws.onopen = () => {
      this.attempt = 0;
      this.lastMessageAt = Date.now();
      this.setState('open');
      this.sendSubscription();
      this.startHeartbeat();
    };
    ws.onmessage = (ev: MessageEvent) => {
      this.lastMessageAt = Date.now();
      let msg: ServerMessage;
      try {
        msg = JSON.parse(String(ev.data)) as ServerMessage;
      } catch {
        return;
      }
      this.listeners.forEach((l) => l(msg));
    };
    ws.onclose = () => {
      if (this.ws !== ws) return; // ya reemplazado
      this.ws = null;
      this.clearTimers();
      this.setState('closed');
      if (!this.stopped) this.scheduleReconnect();
    };
    ws.onerror = () => ws.close();
  }

  private scheduleReconnect(): void {
    const wait = TelemetrySocket.delay(this.attempt++, this.opts.minDelayMs, this.opts.maxDelayMs);
    this.reconnectTimer = setTimeout(() => this.open(), wait);
  }

  private startHeartbeat(): void {
    this.pingTimer = setInterval(() => {
      if (Date.now() - this.lastMessageAt > 2 * this.opts.pingMs) {
        this.ws?.close(); // onclose reconecta
        return;
      }
      this.send({ type: 'ping' });
    }, this.opts.pingMs);
  }

  private sendSubscription(): void {
    if (this.subscription) this.send({ type: 'subscribe', ...this.subscription });
  }

  private send(payload: object): void {
    if (this.ws && this.ws.readyState === 1 /* OPEN */) this.ws.send(JSON.stringify(payload));
  }

  private clearTimers(): void {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.reconnectTimer = null;
    this.pingTimer = null;
  }

  private setState(s: SocketState): void {
    this._state = s;
    this.stateListeners.forEach((l) => l(s));
  }
}

export function defaultSocketUrl(): string {
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${proto}://${window.location.host}/ws/telemetry`;
}
