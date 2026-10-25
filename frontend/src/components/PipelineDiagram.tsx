import { Box, Group, Paper, Stack, Text } from '@mantine/core';
import { formatNumber, formatRate } from '../lib/rates';

export interface StageMetric {
  label: string;
  value: string;
  warn?: boolean;
}

export interface Stage {
  name: string;
  operator: string;
  metrics: StageMetric[];
}

/**
 * Representación del Pipeline A (main.md §12). Cada etapa muestra métricas reales de su
 * actividad; "no disponible" si el backend no la expone.
 */
export function PipelineDiagram({ stages }: { stages: Stage[] }) {
  return (
    <Group align="stretch" gap={0} wrap="wrap">
      {stages.map((s, i) => (
        <Group key={s.name} gap={0} align="center" wrap="nowrap">
          <Paper withBorder radius="md" p="sm" w={190} data-testid={`stage-${s.name}`}>
            <Text fw={700} size="sm">
              {s.name}
            </Text>
            <Text size="xs" c="dimmed" ff="monospace" mb={6}>
              {s.operator}
            </Text>
            <Stack gap={2}>
              {s.metrics.map((m) => (
                <Group key={m.label} justify="space-between" gap="xs" wrap="nowrap">
                  <Text size="xs" c="dimmed">
                    {m.label}
                  </Text>
                  <Text size="xs" fw={600} c={m.warn ? 'red' : undefined}>
                    {m.value}
                  </Text>
                </Group>
              ))}
            </Stack>
          </Paper>
          {i < stages.length - 1 && (
            <Box px={6} c="dimmed" aria-hidden>
              →
            </Box>
          )}
        </Group>
      ))}
    </Group>
  );
}

/** Arma las etapas desde una lectura del monitor y sus tasas. */
export function buildStages(gauges: Partial<Record<string, number>>, r: Partial<Record<string, number>>): Stage[] {
  const g = (k: string) => gauges[k];
  return [
    {
      name: 'MQTT RECEIVER',
      operator: 'Paho → Sinks.unicast',
      metrics: [
        { label: 'recibidos', value: formatRate(r.mqttReceived) },
        { label: 'en cola del Sink', value: formatNumber(g('sinkQueued')) },
        { label: 'sin PUBACK', value: formatNumber(g('acksPending')), warn: (g('acksPending') ?? 0) >= 100 },
        { label: 'conectado', value: g('mqttConnected') === undefined ? 'no disponible' : g('mqttConnected') === 1 ? 'sí' : 'no', warn: g('mqttConnected') === 0 },
      ],
    },
    {
      name: 'VALIDATION',
      operator: 'map(parser::parse)',
      metrics: [
        { label: 'válidos', value: formatRate(r.valid) },
        { label: 'inválidos', value: formatRate(r.invalid), warn: (r.invalid ?? 0) > 0 },
      ],
    },
    {
      name: 'TRANSFORMATION',
      operator: 'normalización + doOnNext',
      metrics: [
        { label: 'procesados', value: formatRate(r.processed) },
        { label: 'latencia p95', value: g('latencyP95Ms') === undefined ? 'no disponible' : `${g('latencyP95Ms')!.toFixed(2)} ms` },
        { label: 'latencia p99', value: g('latencyP99Ms') === undefined ? 'no disponible' : `${g('latencyP99Ms')!.toFixed(2)} ms` },
      ],
    },
    {
      name: 'PERSISTENCE',
      operator: 'flatMap(save, n) + retryWhen',
      metrics: [
        { label: 'en curso', value: formatNumber(g('storeInFlight')) },
        { label: 'reintentos', value: formatRate(r.persistRetries), warn: (r.persistRetries ?? 0) > 0 },
        { label: 'duplicados', value: formatRate(r.duplicates) },
        { label: 'fallidos', value: formatRate(r.persistFailed), warn: (r.persistFailed ?? 0) > 0 },
      ],
    },
    {
      name: 'POSTGRESQL',
      operator: 'R2DBC pool',
      metrics: [
        { label: 'escrituras', value: formatRate(r.persisted) },
        { label: 'conexiones en uso', value: formatNumber(g('r2dbcAcquired')) },
        { label: 'esperando conexión', value: formatNumber(g('r2dbcPending')), warn: (g('r2dbcPending') ?? 0) > 0 },
        { label: 'máximo del pool', value: formatNumber(g('r2dbcMaxAllocated')) },
      ],
    },
  ];
}
