import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

export interface RatePoint {
  t: number;
  [series: string]: number;
}

const COLORS = ['#228be6', '#40c057', '#fab005', '#fa5252', '#7950f2'];
const time = (t: number) => new Date(t).toLocaleTimeString('es-AR', { hour12: false });

/** Series de tasas (msg/s) en una ventana deslizante acotada. */
export function RateChart({ data, series, unit = '/s' }: { data: RatePoint[]; series: { key: string; label: string }[]; unit?: string }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
        <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} tickFormatter={time} scale="time" />
        <YAxis unit={unit} width={70} />
        <Tooltip labelFormatter={(t) => time(Number(t))} />
        <Legend />
        {series.map((s, i) => (
          <Line key={s.key} dataKey={s.key} name={s.label} dot={false} isAnimationActive={false} stroke={COLORS[i % COLORS.length]} strokeWidth={2} />
        ))}
      </LineChart>
    </ResponsiveContainer>
  );
}
