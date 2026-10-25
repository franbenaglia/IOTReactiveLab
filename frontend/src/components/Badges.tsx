import { Badge } from '@mantine/core';
import type { DeviceState, Source } from '../api/types';
import type { SocketState } from '../ws/TelemetrySocket';

export function StatusBadge({ status }: { status: DeviceState }) {
  const color = status === 'ONLINE' ? 'green' : status === 'OFFLINE' ? 'red' : 'gray';
  const label = status === 'ONLINE' ? 'en línea' : status === 'OFFLINE' ? 'desconectado' : 'desconocido';
  return (
    <Badge color={color} variant="light">
      {label}
    </Badge>
  );
}

export function SourceBadge({ source }: { source: Source | null }) {
  if (!source) return <Badge color="gray" variant="outline">sin datos</Badge>;
  return (
    <Badge color={source === 'PHYSICAL' ? 'orange' : 'blue'} variant="outline">
      {source === 'PHYSICAL' ? 'físico' : 'simulado'}
    </Badge>
  );
}

export function SocketBadge({ state }: { state: SocketState }) {
  const map = { open: ['green', 'tiempo real'], connecting: ['yellow', 'conectando…'], closed: ['red', 'sin conexión'] } as const;
  const [color, label] = map[state];
  return (
    <Badge color={color} variant="dot" data-testid="socket-state">
      {label}
    </Badge>
  );
}
