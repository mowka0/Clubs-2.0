import { FC, ReactNode, useRef } from 'react';
import { useSwipeNavigation } from '../hooks/useSwipeNavigation';

interface SwipeNavigatorProps {
  children: ReactNode;
}

/**
 * Слой жестовой навигации: оборачивает содержимое страницы и даёт свайпам от
 * кромок экрана уводить назад/вперёд по истории.
 *
 * Три слоя вместо одного нужны для самой анимации: `host` ловит касания и знает
 * ширину экрана, `page` едет за пальцем, `scrim` затемняет то, что под ней.
 * Вся логика — в [[useSwipeNavigation]], здесь только разметка.
 */
export const SwipeNavigator: FC<SwipeNavigatorProps> = ({ children }) => {
  const hostRef = useRef<HTMLDivElement>(null);
  const pageRef = useRef<HTMLDivElement>(null);
  const scrimRef = useRef<HTMLDivElement>(null);

  useSwipeNavigation({ hostRef, pageRef, scrimRef });

  return (
    <div className="rd-swipe-host" ref={hostRef}>
      <div className="rd-swipe-scrim" ref={scrimRef} aria-hidden="true" />
      <div className="rd-swipe-page" ref={pageRef}>
        {children}
      </div>
    </div>
  );
};
