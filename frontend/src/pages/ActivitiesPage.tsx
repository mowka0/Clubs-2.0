import { FC, useCallback, useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { Toast } from '../components/Toast';
import { EventsTab } from '../components/activities/EventsTab';
import { SkladchinasTab } from '../components/activities/SkladchinasTab';
import { useSkladchinaActionRequiredCountQuery } from '../queries/skladchina';

type SegmentKey = 'events' | 'skladchina';

interface ActivitiesLocationState {
  toast?: string;
}

function routeToSegment(pathname: string): SegmentKey {
  if (pathname.startsWith('/skladchina')) return 'skladchina';
  return 'events';
}

function segmentToRoute(seg: SegmentKey): string {
  return seg === 'skladchina' ? '/skladchina' : '/events';
}

export const ActivitiesPage: FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const haptic = useHaptic();

  const segment = routeToSegment(location.pathname);

  const { data: unpaidCount = 0 } = useSkladchinaActionRequiredCountQuery();

  const navState = location.state as ActivitiesLocationState | null;
  const [toastMessage, setToastMessage] = useState<string | null>(navState?.toast ?? null);
  useEffect(() => {
    if (navState?.toast) {
      window.history.replaceState(null, '', location.pathname);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSelect = useCallback(
    (next: SegmentKey) => {
      if (next === segment) return;
      haptic.select();
      navigate(segmentToRoute(next));
    },
    [segment, navigate, haptic],
  );

  return (
    <div className="rd-page">
      <header className="rd-header">
        <div className="rd-info">
          <div className="rd-ft-eyebrow">Что вокруг</div>
          <div className="rd-page-h">Активности</div>
        </div>
      </header>

      <div className="rd-seg-control" role="tablist" aria-label="Тип активностей">
        <button
          type="button"
          role="tab"
          aria-selected={segment === 'events'}
          className={segment === 'events' ? 'rd-seg-item rd-active' : 'rd-seg-item'}
          onClick={() => handleSelect('events')}
        >
          События
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={segment === 'skladchina'}
          className={segment === 'skladchina' ? 'rd-seg-item rd-active' : 'rd-seg-item'}
          onClick={() => handleSelect('skladchina')}
        >
          Сборы
          {unpaidCount > 0 && (
            <span className="rd-seg-badge" aria-label={`Требует оплаты: ${unpaidCount}`}>
              {unpaidCount}
            </span>
          )}
        </button>
      </div>

      {segment === 'events' ? <EventsTab /> : <SkladchinasTab />}

      {toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
    </div>
  );
};
