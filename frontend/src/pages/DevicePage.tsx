import { Alert as MAlert, Anchor, Card, Group, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { api } from '../api/client';
import type { Alert, DeviceState, ServerMessage, TelemetryView } from '../api/types';
import { SocketBadge, SourceBadge, StatusBadge } from '../components/Badges';
import { StatCard } from '../components/StatCard';
import { TemperatureChart, type Point } from '../components/TemperatureChart';
import { appendBounded, relativeTime } from '../lib/rates';
import { useTelemetrySocket } from '../ws/useTelemetrySocket';

const MAX_POINTS = 600;

/** Vista individual: historial por REST + continuación en vivo por WebSocket. */
export function DevicePage() {
  const { deviceId = '' } = useParams();
  const device = useQuery({ queryKey: ['device', deviceId], queryFn: () => api.device(deviceId), refetchInterval: 10000 });
  const history = useQuery({ queryKey: ['history', deviceId], queryFn: () => api.history(deviceId, { size: 300 }) });
  const alertsQ = useQuery({ queryKey: ['alerts'], queryFn: api.alerts, refetchInterval: 5000 });

  const [rows, setRows] = useState<TelemetryView[]>([]);
  const [status, setStatus] = useState<DeviceState | null>(null);
  const [events, setEvents] = useState<Alert[]>([]);

  // El historial llega descendente; el gráfico va en orden de tiempo.
  useEffect(() => {
    if (history.data) setRows([...history.data.items].reverse());
  }, [history.data]);

  const socket = useTelemetrySocket({ devices: [deviceId], intervalMs: 1000 }, (m: ServerMessage) => {
    if (m.type === 'telemetry') {
      for (const i of m.items) {
        setRows((prev) => {
          const last = prev[prev.length - 1];
          if (last && Date.parse(i.measuredAt) <= Date.parse(last.measuredAt)) return prev; // ya visto
          return appendBounded(prev, i, MAX_POINTS);
        });
      }
    } else if (m.type === 'status') {
      setStatus(m.status);
    } else if (m.type === 'alert') {
      setEvents((prev) => appendBounded(prev, m, 20));
    }
  });

  if (device.error) {
    return (
      <MAlert color="red" title="Dispositivo no encontrado">
        {deviceId} · <Anchor component={Link} to="/devices">volver</Anchor>
      </MAlert>
    );
  }
  const d = device.data;
  const current = rows[rows.length - 1];
  const points: Point[] = rows.map((r) => ({ t: Date.parse(r.measuredAt), temperature: r.temperature }));
  const activeAlert = alertsQ.data?.find((a) => a.deviceId === deviceId);

  return (
    <Stack>
      <Group justify="space-between">
        <Group>
          <Title order={2}>{deviceId}</Title>
          {d && <SourceBadge source={d.source} />}
          {d && <StatusBadge status={status ?? d.status} />}
        </Group>
        <SocketBadge state={socket} />
      </Group>
      <SimpleGrid cols={{ base: 2, md: 4 }}>
        <StatCard label="Temperatura actual" value={current ? `${current.temperature.toFixed(2)} °C` : '—'} color={activeAlert ? 'red' : undefined} />
        <StatCard label="Humedad" value={current?.humidity != null ? `${current.humidity.toFixed(0)} %` : 'no medida'} />
        <StatCard label="Última medición" value={relativeTime(current?.measuredAt ?? null)} hint={current ? `secuencia ${current.sequence}` : undefined} />
        <StatCard label="Alerta" value={activeAlert ? 'ACTIVA' : 'no'} color={activeAlert ? 'red' : 'green'} hint={activeAlert ? `desde ${relativeTime(activeAlert.at)}` : undefined} />
      </SimpleGrid>
      <Card withBorder radius="md">
        <Title order={4} mb="xs">
          Temperatura
        </Title>
        {points.length === 0 ? <Text c="dimmed">Sin mediciones en las últimas 24 h.</Text> : <TemperatureChart points={points} threshold={activeAlert?.threshold ?? 35} />}
      </Card>
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Card withBorder radius="md">
          <Title order={4} mb="xs">
            Últimas mediciones
          </Title>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Secuencia</Table.Th>
                <Table.Th>Temperatura</Table.Th>
                <Table.Th>Medida</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.slice(-10).reverse().map((r) => (
                <Table.Tr key={`${r.measuredAt}-${r.sequence}`}>
                  <Table.Td>{r.sequence}</Table.Td>
                  <Table.Td>{r.temperature.toFixed(2)} °C</Table.Td>
                  <Table.Td>{new Date(r.measuredAt).toLocaleTimeString('es-AR', { hour12: false })}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
        <Card withBorder radius="md">
          <Title order={4} mb="xs">
            Alertas (esta sesión)
          </Title>
          {events.length === 0 && <Text c="dimmed">Sin eventos desde que se abrió la vista.</Text>}
          {[...events].reverse().map((a, i) => (
            <Text key={i} size="sm" c={a.state === 'RAISED' ? 'red' : 'green'}>
              {a.state === 'RAISED' ? '▲ disparada' : '▼ resuelta'} a {a.temperature.toFixed(1)} °C · {relativeTime(a.at)}
            </Text>
          ))}
        </Card>
      </SimpleGrid>
    </Stack>
  );
}
