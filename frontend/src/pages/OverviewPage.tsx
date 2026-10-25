import { Alert as MAlert, Card, Group, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api } from '../api/client';
import { useMonitor } from '../api/useMonitor';
import { StatCard } from '../components/StatCard';
import { formatNumber, formatRate, relativeTime } from '../lib/rates';

export function OverviewPage() {
  const overview = useQuery({ queryKey: ['overview'], queryFn: api.overview, refetchInterval: 5000 });
  const alerts = useQuery({ queryKey: ['alerts'], queryFn: api.alerts, refetchInterval: 3000 });
  const monitor = useMonitor(2000);
  const o = overview.data;

  return (
    <Stack>
      <Title order={2}>Vista general</Title>
      {overview.error && <MAlert color="red">No se pudo leer el backend: {String(overview.error.message)}</MAlert>}
      <SimpleGrid cols={{ base: 2, md: 3, lg: 6 }}>
        <StatCard label="Registrados" value={o ? formatNumber(o.devicesTotal) : '…'} />
        <StatCard label="Activos" value={o ? formatNumber(o.devicesOnline) : '…'} color="green" />
        <StatCard label="Desconectados" value={o ? formatNumber(o.devicesOffline) : '…'} color="red" />
        <StatCard label="Mediciones/s" value={monitor.rates ? formatRate(monitor.rates.valid) : '…'} hint="válidas, últimos 2 s" />
        <StatCard
          label="Temperatura promedio"
          value={o ? (o.averageTemperature === null ? 'sin datos' : `${o.averageTemperature.toFixed(1)} °C`) : '…'}
          hint="última lectura de cada dispositivo"
        />
        <StatCard label="Alertas activas" value={o ? formatNumber(o.activeAlerts) : '…'} color={o && o.activeAlerts > 0 ? 'red' : undefined} />
      </SimpleGrid>

      <Card withBorder radius="md">
        <Group justify="space-between" mb="xs">
          <Title order={4}>Alertas activas</Title>
          <Text size="xs" c="dimmed">
            temperatura sobre el umbral, con antirrebote e histéresis
          </Text>
        </Group>
        {alerts.data && alerts.data.length === 0 && <Text c="dimmed">Sin alertas.</Text>}
        {alerts.data && alerts.data.length > 0 && (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Dispositivo</Table.Th>
                <Table.Th>Temperatura</Table.Th>
                <Table.Th>Umbral</Table.Th>
                <Table.Th>Desde</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {alerts.data.map((a) => (
                <Table.Tr key={a.deviceId}>
                  <Table.Td>
                    <Link to={`/devices/${a.deviceId}`}>{a.deviceId}</Link>
                  </Table.Td>
                  <Table.Td c="red">{a.temperature.toFixed(1)} °C</Table.Td>
                  <Table.Td>{a.threshold} °C</Table.Td>
                  <Table.Td>{relativeTime(a.at)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Card>

      {o && (
        <Text size="xs" c="dimmed">
          MQTT {o.mqttConnected ? 'conectado' : 'DESCONECTADO'} · {formatNumber(o.telemetryRows)} mediciones guardadas ·{' '}
          {formatNumber(o.duplicates)} duplicados descartados · {formatNumber(o.invalid)} mensajes inválidos
        </Text>
      )}
    </Stack>
  );
}
