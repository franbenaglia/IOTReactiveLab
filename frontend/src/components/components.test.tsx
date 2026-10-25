import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { DeviceView } from '../api/types';
import { DevicesTable } from '../pages/DevicesPage';
import { renderWithProviders } from '../test/render';
import { buildStages, PipelineDiagram } from './PipelineDiagram';

const device = (over: Partial<DeviceView> = {}): DeviceView => ({
  deviceId: 'esp32-sim-00001',
  source: 'SIMULATED',
  status: 'ONLINE',
  statusChangedAt: null,
  lastSeen: null,
  createdAt: '2026-10-25T12:00:00Z',
  lastTemperature: 24.4,
  lastHumidity: null,
  lastMeasuredAt: '2026-10-25T12:00:00Z',
  messagesLastMinute: 60,
  ...over,
});

describe('DevicesTable', () => {
  it('renders the required columns and live temperature overrides REST', () => {
    renderWithProviders(
      <DevicesTable
        devices={[device(), device({ deviceId: 'esp32-real-001', source: 'PHYSICAL', status: 'OFFLINE', lastTemperature: null, messagesLastMinute: 0 })]}
        live={{ 'esp32-sim-00001': { temperature: 30.15, measuredAt: new Date().toISOString() } }}
        onSelect={vi.fn()}
      />,
    );
    for (const h of ['Identificador', 'Tipo', 'Última temperatura', 'Última comunicación', 'Estado', 'Frecuencia estimada']) {
      expect(screen.getByText(h)).toBeInTheDocument();
    }
    expect(screen.getByText('30.1 °C')).toBeInTheDocument(); // del WebSocket, no 24.4 de REST
    expect(screen.getByText('físico')).toBeInTheDocument();
    expect(screen.getByText('desconectado')).toBeInTheDocument();
    expect(screen.getByText('60/min')).toBeInTheDocument();
  });
});

describe('PipelineDiagram', () => {
  it('shows "no disponible" for metrics the backend does not expose', () => {
    renderWithProviders(<PipelineDiagram stages={buildStages({ storeInFlight: 3 }, { valid: 12.5 })} />);
    expect(screen.getByTestId('stage-PERSISTENCE')).toHaveTextContent('en curso3');
    expect(screen.getByTestId('stage-VALIDATION')).toHaveTextContent('válidos12.5/s');
    expect(screen.getByTestId('stage-POSTGRESQL')).toHaveTextContent('conexiones en usono disponible');
  });
});
