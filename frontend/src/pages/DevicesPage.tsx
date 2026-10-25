import { Group, Pagination, Stack, Table, Text, TextInput, Title, UnstyledButton } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../api/client';
import type { DeviceView, ServerMessage } from '../api/types';
import { SocketBadge, SourceBadge, StatusBadge } from '../components/Badges';
import { relativeTime } from '../lib/rates';
import { useTelemetrySocket } from '../ws/useTelemetrySocket';

const PAGE_SIZE = 50;

type Live = Record<string, { temperature: number; measuredAt: string }>;

/**
 * Tabla de dispositivos. Los metadatos vienen de REST cada 10 s; la temperatura de los 50
 * dispositivos de la página visible llega por WebSocket con una suscripción filtrada: el
 * servidor solo envía esos 50 (docs/websocket-contract.md).
 */
export function DevicesPage() {
  const navigate = useNavigate();
  const devices = useQuery({ queryKey: ['devices'], queryFn: api.devices, refetchInterval: 10000 });
  const [filter, setFilter] = useState('');
  const [page, setPage] = useState(1);
  const [live, setLive] = useState<Live>({});

  const filtered = useMemo(
    () => (devices.data ?? []).filter((d) => d.deviceId.includes(filter.trim().toLowerCase())),
    [devices.data, filter],
  );
  const pages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const visible = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  const ids = visible.map((d) => d.deviceId);

  const socket = useTelemetrySocket(ids.length ? { devices: ids, intervalMs: 1000 } : null, (m: ServerMessage) => {
    if (m.type === 'telemetry' || m.type === 'snapshot') {
      setLive((prev) => {
        const next = { ...prev };
        for (const i of m.items) next[i.deviceId] = { temperature: i.temperature, measuredAt: i.measuredAt };
        return next;
      });
    }
  });

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Dispositivos</Title>
        <SocketBadge state={socket} />
      </Group>
      <Group>
        <TextInput
          placeholder="Filtrar por deviceId"
          value={filter}
          onChange={(e) => {
            setFilter(e.currentTarget.value);
            setPage(1);
          }}
          w={280}
        />
        <Text size="sm" c="dimmed">
          {filtered.length} de {devices.data?.length ?? 0}
        </Text>
      </Group>
      <DevicesTable devices={visible} live={live} onSelect={(id) => navigate(`/devices/${id}`)} />
      {pages > 1 && <Pagination total={pages} value={page} onChange={setPage} />}
    </Stack>
  );
}

export function DevicesTable({ devices, live, onSelect }: { devices: DeviceView[]; live: Live; onSelect: (id: string) => void }) {
  return (
    <Table striped highlightOnHover>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Identificador</Table.Th>
          <Table.Th>Tipo</Table.Th>
          <Table.Th>Última temperatura</Table.Th>
          <Table.Th>Última comunicación</Table.Th>
          <Table.Th>Estado</Table.Th>
          <Table.Th>Frecuencia estimada</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {devices.map((d) => {
          const l = live[d.deviceId];
          const temp = l?.temperature ?? d.lastTemperature;
          const at = l?.measuredAt ?? d.lastMeasuredAt;
          return (
            <Table.Tr key={d.deviceId}>
              <Table.Td>
                <UnstyledButton onClick={() => onSelect(d.deviceId)} fw={600} c="blue">
                  {d.deviceId}
                </UnstyledButton>
              </Table.Td>
              <Table.Td>
                <SourceBadge source={d.source} />
              </Table.Td>
              <Table.Td>{temp === null || temp === undefined ? '—' : `${temp.toFixed(1)} °C`}</Table.Td>
              <Table.Td>{relativeTime(at)}</Table.Td>
              <Table.Td>
                <StatusBadge status={d.status} />
              </Table.Td>
              <Table.Td>{d.messagesLastMinute ? `${d.messagesLastMinute}/min` : '—'}</Table.Td>
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
  );
}
