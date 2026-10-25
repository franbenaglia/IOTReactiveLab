import { AppShell, Burger, Group, Loader, NavLink, Text, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { lazy, Suspense } from 'react';
import { NavLink as RouterLink, Route, Routes, useLocation } from 'react-router';

// Carga diferida: Recharts (el grueso del bundle) solo se descarga en las vistas con gráficos.
const OverviewPage = lazy(() => import('./pages/OverviewPage').then((m) => ({ default: m.OverviewPage })));
const DevicesPage = lazy(() => import('./pages/DevicesPage').then((m) => ({ default: m.DevicesPage })));
const DevicePage = lazy(() => import('./pages/DevicePage').then((m) => ({ default: m.DevicePage })));
const MonitorPage = lazy(() => import('./pages/MonitorPage').then((m) => ({ default: m.MonitorPage })));

const LINKS = [
  { to: '/', label: 'Vista general' },
  { to: '/devices', label: 'Dispositivos' },
  { to: '/monitor', label: 'Reactive Monitor' },
];

export function App() {
  const [opened, { toggle, close }] = useDisclosure();
  const { pathname } = useLocation();
  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 220, breakpoint: 'sm', collapsed: { mobile: !opened } }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" gap="sm">
          <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="menú" />
          <Title order={4}>IoT Reactive Lab</Title>
          <Text size="xs" c="dimmed" visibleFrom="sm">
            Spring WebFlux · Project Reactor · MQTT
          </Text>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        {LINKS.map((l) => (
          <NavLink
            key={l.to}
            component={RouterLink}
            to={l.to}
            label={l.label}
            onClick={close}
            active={l.to === '/' ? pathname === '/' : pathname.startsWith(l.to)}
          />
        ))}
      </AppShell.Navbar>
      <AppShell.Main>
        <Suspense fallback={<Loader />}>
        <Routes>
          <Route path="/" element={<OverviewPage />} />
          <Route path="/devices" element={<DevicesPage />} />
          <Route path="/devices/:deviceId" element={<DevicePage />} />
          <Route path="/monitor" element={<MonitorPage />} />
        </Routes>
        </Suspense>
      </AppShell.Main>
    </AppShell>
  );
}
