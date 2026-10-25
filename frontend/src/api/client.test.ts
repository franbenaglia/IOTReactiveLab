import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './client';

afterEach(() => vi.unstubAllGlobals());

function mockFetch(status: number, body: unknown) {
  const fn = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status, statusText: 'x' }));
  vi.stubGlobal('fetch', fn);
  return fn;
}

describe('api client', () => {
  it('builds history query strings', async () => {
    const fn = mockFetch(200, { items: [] });
    await api.history('esp32-sim-00001', { size: 50, page: 2 });
    expect(fn).toHaveBeenCalledWith('/api/devices/esp32-sim-00001/history?size=50&page=2', expect.anything());
  });

  it('turns ProblemDetail into ApiError with the detail message', async () => {
    mockFetch(404, { status: 404, detail: 'dispositivo desconocido: x' });
    await expect(api.device('x')).rejects.toEqual(new ApiError(404, 'dispositivo desconocido: x'));
  });

  it('parses JSON on success', async () => {
    mockFetch(200, [{ deviceId: 'a' }]);
    await expect(api.devices()).resolves.toEqual([{ deviceId: 'a' }]);
  });
});
