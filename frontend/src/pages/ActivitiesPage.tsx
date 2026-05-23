import { FC, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { BrandBackdrop } from '../components/BrandBackdrop';
import { EventsTab } from '../components/activities/EventsTab';
import { SkladchinasTab } from '../components/activities/SkladchinasTab';

type SegmentKey = 'events' | 'skladchina';

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

  const handleSelect = useCallback(
    (next: SegmentKey) => {
      if (next === segment) return;
      haptic.select();
      navigate(segmentToRoute(next));
    },
    [segment, navigate, haptic],
  );

  return (
    <div className="brand-page">
      <BrandBackdrop />

      <header className="mc-hero">
        <div className="mc-hero-row">
          <h1>
            Твои <span className="accent">активности</span>
          </h1>
        </div>
      </header>

      <div className="activities-segments" role="tablist" aria-label="Тип активностей">
        <button
          type="button"
          role="tab"
          aria-selected={segment === 'events'}
          className={segment === 'events' ? 'segment active' : 'segment'}
          onClick={() => handleSelect('events')}
        >
          События
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={segment === 'skladchina'}
          className={segment === 'skladchina' ? 'segment active' : 'segment'}
          onClick={() => handleSelect('skladchina')}
        >
          Сборы
        </button>
      </div>

      {segment === 'events' ? <EventsTab /> : <SkladchinasTab />}
    </div>
  );
};
