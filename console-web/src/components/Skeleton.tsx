/**
 * Skeleton placeholders for pages waiting on data from the API. Each
 * component mirrors the layout of the real component it replaces, so the
 * page doesn't visibly reflow once data arrives.
 *
 * The shimmer effect uses a CSS gradient + the `animate-shimmer` keyframe
 * defined in tailwind.config.js. No JS animation, no impact on the render
 * loop.
 */

const SHIMMER =
  'bg-[linear-gradient(110deg,#e2e8f0_0%,#f1f5f9_30%,#e2e8f0_60%)] ' +
  'bg-[length:200%_100%] animate-shimmer';

function Bar({ className = '' }: Readonly<{ className?: string }>) {
  return <div className={`${SHIMMER} rounded-md ${className}`} />;
}

/** Single KPI card placeholder. Mirrors the real <Kpi> dimensions. */
export function KpiSkeleton() {
  return (
    <div className="card relative p-4 overflow-hidden">
      <span aria-hidden="true"
            className="absolute inset-x-0 top-0 h-1 bg-slate-200" />
      <div className="flex items-start justify-between gap-3">
        <Bar className="h-3 w-24" />
        <div className={`${SHIMMER} h-8 w-8 rounded-md shrink-0`} />
      </div>
      <Bar className="h-7 w-28 mt-2" />
      <Bar className="h-3 w-32 mt-2" />
    </div>
  );
}

/** Row of N KPI skeletons. Default 4 to match most business pages. */
export function KpiRowSkeleton({ count = 4 }: Readonly<{ count?: number }>) {
  return (
    <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
      {Array.from({ length: count }).map((_, i) => <KpiSkeleton key={i} />)}
    </div>
  );
}

/**
 * Table body placeholder. Use inside an existing <table> in place of the
 * real <tbody>, or pass `withHeader` to include a placeholder header row.
 */
export function TableSkeleton({
  rows = 6, cols = 6,
}: Readonly<{ rows?: number; cols?: number }>) {
  return (
    <tbody className="divide-y divide-slate-100">
      {Array.from({ length: rows }).map((_, r) => (
        <tr key={r}>
          {Array.from({ length: cols }).map((_, c) => (
            <td key={c} className="px-4 py-3">
              <Bar className={`h-3 ${c === 0 ? 'w-24' : c === cols - 1 ? 'w-16' : 'w-32'}`} />
            </td>
          ))}
        </tr>
      ))}
    </tbody>
  );
}

/** Chart placeholder. Matches the typical 48–56 viewport height we use. */
export function ChartSkeleton({ height = 'h-56' }: Readonly<{ height?: string }>) {
  return (
    <div className={`${SHIMMER} ${height} rounded-md`} />
  );
}

/** Block of N stacked bars — useful for distribution / list-style cards. */
export function ListSkeleton({ rows = 4 }: Readonly<{ rows?: number }>) {
  return (
    <div className="space-y-2">
      {Array.from({ length: rows }).map((_, i) => (
        <Bar key={i} className="h-4 w-full" />
      ))}
    </div>
  );
}
