import { Alert as MAlert, Card, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { useMonitor } from '../api/useMonitor';
import { buildStages, PipelineDiagram } from '../components/PipelineDiagram';
import { RateChart } from '../components/RateChart';
import { StatCard } from '../components/StatCard';
import { formatBytes, formatNumber, formatRate } from '../lib/rates';

/**
 * Reactive Monitor (main.md §12). Todo proviene de /api/metrics/monitor (Micrometer). Las tasas
 * se derivan de dos lecturas sucesivas de contadores acumulados.
 */
export function MonitorPage() {
  const { snapshot, rates: r = {}, history, error } = useMonitor(1000);
  const g = snapshot?.gauges ?? {};

  return (
    <Stack>
      <Title order={2}>Reactive Monitor</Title>
      {error && <MAlert color="red">No se pudo leer el backend: {String(error.message)}</MAlert>}

      <Card withBorder radius="md">
        <Title order={4} mb="sm">
          Pipeline A
        </Title>
        <PipelineDiagram stages={buildStages(g, r)} />
      </Card>

      <SimpleGrid cols={{ base: 2, md: 4, lg: 6 }}>
        <StatCard label="MQTT recibidos" value={formatRate(r.mqttReceived)} />
        <StatCard label="Procesados" value={formatRate(r.processed)} />
        <StatCard label="Escrituras PostgreSQL" value={formatRate(r.persisted)} />
        <StatCard label="Operaciones R2DBC en curso" value={formatNumber(g.r2dbcAcquired)} hint={`pool máx. ${formatNumber(g.r2dbcMaxAllocated)}`} />
        <StatCard label="Latencia p99" value={g.latencyP99Ms === undefined ? 'no disponible' : `${g.latencyP99Ms.toFixed(2)} ms`} hint="recepción → estado final" />
        <StatCard label="Errores" value={formatRate((r.persistFailed ?? 0) + (r.sinkDropped ?? 0))} hint="persistencia fallida + rechazos del Sink" />
        <StatCard label="Rechazados" value={formatRate(r.invalid)} hint="validación del contrato" />
        <StatCard label="Clientes WebSocket" value={formatNumber(g.wsClients)} hint={`descartes ${formatRate(r.wsDropped)}`} />
        <StatCard label="Heap JVM" value={formatBytes(g.jvmHeapUsed)} hint={`de ${formatBytes(g.jvmHeapMax)}`} />
        <StatCard label="CPU del proceso" value={g.processCpu === undefined ? 'no disponible' : `${(g.processCpu * 100).toFixed(1)} %`} hint={g.cpuCount === undefined ? undefined : `del total de ${g.cpuCount} núcleos`} />
        <StatCard label="Conexiones Netty" value={formatNumber(g.nettyConnectionsActive)} hint="HTTP + WebSocket activas" />
        <StatCard label="Memoria directa Netty" value={formatBytes(g.nettyDirectMemoryUsed)} />
      </SimpleGrid>

      <Card withBorder radius="md">
        <Title order={4}>Caudal</Title>
        <RateChart
          data={history}
          series={[
            { key: 'mqttReceived', label: 'MQTT recibidos' },
            { key: 'processed', label: 'procesados' },
            { key: 'persisted', label: 'escrituras' },
            { key: 'invalid', label: 'inválidos' },
          ]}
        />
      </Card>
      <Card withBorder radius="md">
        <Title order={4}>Presión</Title>
        <RateChart data={history} series={[{ key: 'persistRetries', label: 'reintentos' }, { key: 'duplicates', label: 'duplicados' }, { key: 'wsDropped', label: 'descartes WS' }]} />
        <Text size="xs" c="dimmed">
          Con la base lenta o caída suben los reintentos y "sin PUBACK" llega al receiveMaximum (100): es el backpressure hacia el broker.
        </Text>
      </Card>
    </Stack>
  );
}
