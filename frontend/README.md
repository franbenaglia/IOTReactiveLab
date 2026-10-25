# Dashboard — IoT Reactive Lab

React 19 · TypeScript · Vite 8 · Mantine 9 · Recharts · TanStack Query.
Decisiones: ADR-014 en [docs/decisions.md](../docs/decisions.md).

```bash
npm install
npm run dev        # http://localhost:5173 (backend en :8080; BACKEND_URL para cambiarlo)
npm test           # Vitest
npm run build      # typecheck + build de producción en dist/
```

Requiere Node ≥ 20.19. Las versiones están elegidas para Node 20; con Node 22+
se pueden subir react-router, Vitest y jsdom (P11 en `docs/progress.md`).

| Vista | Ruta | Datos |
|---|---|---|
| Vista general | `/` | REST: overview, alertas, monitor |
| Dispositivos | `/devices` | REST cada 10 s + WebSocket filtrado a la página visible |
| Individual | `/devices/:id` | Historial REST + WebSocket de ese dispositivo |
| Reactive Monitor | `/monitor` | `/api/metrics/monitor` cada 1 s; tasas derivadas en el cliente |

Contratos: [docs/websocket-contract.md](../docs/websocket-contract.md) y los
tipos en [src/api/types.ts](src/api/types.ts).
