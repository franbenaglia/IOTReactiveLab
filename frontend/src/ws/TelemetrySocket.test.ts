import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ServerMessage } from '../api/types';
import { TelemetrySocket } from './TelemetrySocket';

/** WebSocket simulado: registra lo enviado y permite disparar eventos. */
class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  readyState = 0;
  sent: unknown[] = [];
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  constructor(public url: string) {
    FakeWebSocket.instances.push(this);
  }
  send(data: string) {
    this.sent.push(JSON.parse(data));
  }
  close() {
    this.readyState = 3;
    this.onclose?.();
  }
  // helpers de la prueba
  open() {
    this.readyState = 1;
    this.onopen?.();
  }
  receive(msg: ServerMessage) {
    this.onmessage?.({ data: JSON.stringify(msg) });
  }
  drop() {
    this.readyState = 3;
    this.onclose?.();
  }
}

const last = () => FakeWebSocket.instances[FakeWebSocket.instances.length - 1]!;

describe('TelemetrySocket', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeWebSocket.instances = [];
  });
  afterEach(() => vi.useRealTimers());

  const make = () =>
    new TelemetrySocket('ws://x/ws/telemetry', { WebSocketImpl: FakeWebSocket as unknown as typeof WebSocket, pingMs: 1000 });

  it('sends the subscription on open and delivers server messages', () => {
    const s = make();
    const got: ServerMessage[] = [];
    s.onMessage((m) => got.push(m));
    s.subscribe({ devices: ['dev-001'], intervalMs: 1000 });
    s.connect();
    last().open();
    expect(last().sent).toEqual([{ type: 'subscribe', devices: ['dev-001'], intervalMs: 1000 }]);
    last().receive({ type: 'pong' });
    expect(got).toEqual([{ type: 'pong' }]);
  });

  it('reconnects with exponential backoff and re-sends the subscription', () => {
    const s = make();
    const states: string[] = [];
    s.onState((st) => states.push(st));
    s.subscribe({ devices: '*', intervalMs: 2000 });
    s.connect();
    last().open();
    last().drop();
    expect(FakeWebSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(999);
    expect(FakeWebSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(1); // 1 s
    expect(FakeWebSocket.instances).toHaveLength(2);
    last().drop();
    vi.advanceTimersByTime(1999);
    expect(FakeWebSocket.instances).toHaveLength(2);
    vi.advanceTimersByTime(1); // 2 s
    expect(FakeWebSocket.instances).toHaveLength(3);
    last().open();
    expect(last().sent).toEqual([{ type: 'subscribe', devices: '*', intervalMs: 2000 }]);
    expect(states).toEqual(['connecting', 'open', 'closed', 'connecting', 'closed', 'connecting', 'open']);
  });

  it('backoff is capped', () => {
    expect(TelemetrySocket.delay(0, 1000, 30000)).toBe(1000);
    expect(TelemetrySocket.delay(3, 1000, 30000)).toBe(8000);
    expect(TelemetrySocket.delay(10, 1000, 30000)).toBe(30000);
  });

  it('heartbeat closes a silent connection so it can reconnect', () => {
    const s = make();
    s.connect();
    last().open();
    vi.advanceTimersByTime(1000);
    expect(last().sent).toContainEqual({ type: 'ping' });
    vi.advanceTimersByTime(2000); // sin respuesta en 2 × pingMs
    expect(s.state).toBe('closed');
    vi.advanceTimersByTime(1000);
    expect(FakeWebSocket.instances).toHaveLength(2);
  });

  it('close() stops reconnecting', () => {
    const s = make();
    s.connect();
    last().open();
    s.close();
    vi.advanceTimersByTime(60000);
    expect(FakeWebSocket.instances).toHaveLength(1);
  });
});
