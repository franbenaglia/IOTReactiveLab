import { describe, expect, it } from 'vitest';
import type { MonitorSnapshot } from '../api/types';
import { appendBounded, formatRate, rates, relativeTime } from './rates';

const snap = (at: string, counters: Record<string, number>): MonitorSnapshot => ({ at, counters, gauges: {} });

describe('rates', () => {
  it('derives per-second rates from two cumulative readings', () => {
    const r = rates(snap('2026-10-25T12:00:00Z', { valid: 100, invalid: 5 }), snap('2026-10-25T12:00:02Z', { valid: 300, invalid: 5 }));
    expect(r.valid).toBe(100);
    expect(r.invalid).toBe(0);
  });

  it('omits a counter missing in either reading (never invents a zero)', () => {
    const r = rates(snap('2026-10-25T12:00:00Z', { valid: 1 }), snap('2026-10-25T12:00:01Z', { valid: 2, persisted: 9 }));
    expect(r.persisted).toBeUndefined();
    expect(formatRate(r.persisted)).toBe('no disponible');
  });

  it('omits counters that went down (backend restarted)', () => {
    const r = rates(snap('2026-10-25T12:00:00Z', { valid: 500 }), snap('2026-10-25T12:00:01Z', { valid: 10 }));
    expect(r.valid).toBeUndefined();
  });

  it('returns nothing without a previous reading', () => {
    expect(rates(undefined, snap('2026-10-25T12:00:00Z', { valid: 1 }))).toEqual({});
  });
});

describe('appendBounded', () => {
  it('keeps only the newest N items', () => {
    let list: number[] = [];
    for (let i = 0; i < 10; i++) list = appendBounded(list, i, 3);
    expect(list).toEqual([7, 8, 9]);
  });
});

describe('relativeTime', () => {
  it('formats', () => {
    const now = Date.parse('2026-10-25T12:00:00Z');
    expect(relativeTime('2026-10-25T11:59:58Z', now)).toBe('ahora');
    expect(relativeTime('2026-10-25T11:59:30Z', now)).toBe('hace 30 s');
    expect(relativeTime('2026-10-25T11:50:00Z', now)).toBe('hace 10 min');
    expect(relativeTime(null, now)).toBe('—');
  });
});
