import { screen, waitFor, act } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ServerMessage } from '../api/types';
import { renderWithProviders } from '../test/render';
import { DevicePage } from './DevicePage';

// El hook del WebSocket se reemplaza: la prueba captura el handler y le "envía" mensajes.
let handler: ((m: ServerMessage) => void) | null = null;
let lastSubscription: unknown = null;
vi.mock('../ws/useTelemetrySocket', () => ({
  useTelemetrySocket: (sub: unknown, h: (m: ServerMessage) => void) => {
    handler = h;
    lastSubscription = sub;
    return 'open';
  },
}));

vi.mock('../api/client', () => ({
  api: {
    device: vi.fn().mockResolvedValue({
      deviceId: 'esp32-sim-00007', source: 'SIMULATED', status: 'ONLINE', statusChangedAt: null, lastSeen: null,
      createdAt: '2026-10-25T12:00:00Z', lastTemperature: 21, lastHumidity: null, lastMeasuredAt: null, messagesLastMinute: 60,
    }),
    history: vi.fn().mockResolvedValue({
      deviceId: 'esp32-sim-00007', from: '', to: '', page: 0, size: 300, hasMore: false,
      items: [{ deviceId: 'esp32-sim-00007', temperature: 21, humidity: null, measuredAt: '2026-10-25T12:00:00Z', receivedAt: '2026-10-25T12:00:00Z', sequence: 1 }],
    }),
    alerts: vi.fn().mockResolvedValue([]),
  },
}));

describe('DevicePage', () => {
  beforeEach(() => {
    handler = null;
  });

  it('subscribes to its device and updates live from WebSocket telemetry, status and alerts', async () => {
    renderWithProviders(<DevicePage />, { path: '/devices/:deviceId', route: '/devices/esp32-sim-00007' });
    await waitFor(() => expect(screen.getByTestId('stat-Temperatura actual')).toHaveTextContent('21.00 °C'));
    expect(lastSubscription).toEqual({ devices: ['esp32-sim-00007'], intervalMs: 1000 });

    act(() =>
      handler!({
        type: 'telemetry',
        items: [{ deviceId: 'esp32-sim-00007', temperature: 36.5, humidity: null, measuredAt: '2026-10-25T12:00:05Z', receivedAt: '2026-10-25T12:00:05Z', sequence: 2, source: 'SIMULATED' }],
      }),
    );
    expect(screen.getByTestId('stat-Temperatura actual')).toHaveTextContent('36.50 °C');

    // Una medición vieja (reentrega) no hace retroceder la vista.
    act(() =>
      handler!({
        type: 'telemetry',
        items: [{ deviceId: 'esp32-sim-00007', temperature: 10, humidity: null, measuredAt: '2026-10-25T12:00:01Z', receivedAt: '2026-10-25T12:00:06Z', sequence: 1, source: 'SIMULATED' }],
      }),
    );
    expect(screen.getByTestId('stat-Temperatura actual')).toHaveTextContent('36.50 °C');

    act(() => handler!({ type: 'alert', deviceId: 'esp32-sim-00007', kind: 'HIGH_TEMPERATURE', state: 'RAISED', temperature: 36.5, threshold: 35, at: new Date().toISOString() }));
    expect(screen.getByText(/disparada a 36.5 °C/)).toBeInTheDocument();

    act(() => handler!({ type: 'status', deviceId: 'esp32-sim-00007', status: 'OFFLINE', at: new Date().toISOString() }));
    expect(screen.getByText('desconectado')).toBeInTheDocument();
  });
});
