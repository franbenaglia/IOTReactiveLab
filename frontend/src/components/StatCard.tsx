import { Card, Text } from '@mantine/core';
import type { ReactNode } from 'react';

export function StatCard({ label, value, hint, color }: { label: string; value: ReactNode; hint?: ReactNode; color?: string }) {
  return (
    <Card withBorder padding="md" radius="md">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {label}
      </Text>
      <Text size="xl" fw={700} c={color} data-testid={`stat-${label}`}>
        {value}
      </Text>
      {hint !== undefined && (
        <Text size="xs" c="dimmed">
          {hint}
        </Text>
      )}
    </Card>
  );
}
