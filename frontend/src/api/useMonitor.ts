import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { appendBounded, rates } from '../lib/rates';
import type { RatePoint } from '../components/RateChart';
import { api } from './client';
import type { MonitorSnapshot } from './types';

const HISTORY = 120; // 2 minutos a 1 lectura por segundo

/** Lee /api/metrics/monitor cada `intervalMs` y deriva tasas e historial acotado. */
export function useMonitor(intervalMs = 1000) {
  const q = useQuery({ queryKey: ['monitor'], queryFn: api.monitor, refetchInterval: intervalMs });
  const prev = useRef<MonitorSnapshot | undefined>(undefined);
  const [current, setCurrent] = useState<{ snapshot: MonitorSnapshot; rates: Partial<Record<string, number>> } | null>(null);
  const [history, setHistory] = useState<RatePoint[]>([]);

  useEffect(() => {
    const snap = q.data;
    if (!snap || snap === prev.current) return;
    const r = rates(prev.current, snap);
    prev.current = snap;
    setCurrent({ snapshot: snap, rates: r });
    if (Object.keys(r).length > 0) {
      const point: RatePoint = { t: Date.parse(snap.at) };
      for (const [k, v] of Object.entries(r)) if (v !== undefined) point[k] = Math.round(v * 10) / 10;
      setHistory((h) => appendBounded(h, point, HISTORY));
    }
  }, [q.data]);

  return { ...current, history, error: q.error, isLoading: q.isLoading };
}
