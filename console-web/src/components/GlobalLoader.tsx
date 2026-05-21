import { useIsFetching, useIsMutating } from '@tanstack/react-query';

/**
 * Thin progress bar pinned at the top of the viewport. Visible as soon as
 * any TanStack Query is fetching or any mutation is in flight, regardless
 * of which page you're on. Pure CSS animation, no JS work per frame.
 *
 * Use case: feedback when navigating between pages while their data is
 * loading from the API.
 */
export function GlobalLoader() {
  const fetching = useIsFetching();
  const mutating = useIsMutating();
  const active = fetching + mutating > 0;

  return (
    <div aria-hidden={!active}
         className={`fixed top-0 left-0 right-0 h-1 z-[60] pointer-events-none
                     transition-opacity duration-200
                     ${active ? 'opacity-100' : 'opacity-0'}`}>
      <div className="h-full bg-sigdep-500 animate-progress origin-left shadow-sm" />
    </div>
  );
}
