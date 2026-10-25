import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

export interface Point {
  t: number; // epoch ms
  temperature: number;
}

const time = (t: number) => new Date(t).toLocaleTimeString('es-AR', { hour12: false });

export function TemperatureChart({ points, threshold }: { points: Point[]; threshold?: number }) {
  if (points.length === 0) return null;
  return (
    <ResponsiveContainer width="100%" height={280}>
      <LineChart data={points} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
        <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} tickFormatter={time} scale="time" />
        <YAxis unit=" °C" domain={['auto', 'auto']} width={64} />
        <Tooltip labelFormatter={(t) => time(Number(t))} formatter={(v) => [`${Number(v).toFixed(2)} °C`, 'temperatura']} />
        {threshold !== undefined && <ReferenceLine y={threshold} stroke="red" strokeDasharray="4 4" label="umbral" />}
        <Line type="monotone" dataKey="temperature" dot={false} isAnimationActive={false} strokeWidth={2} />
      </LineChart>
    </ResponsiveContainer>
  );
}
