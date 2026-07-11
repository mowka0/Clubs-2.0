import { FC, useState } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import { openMapUrl, routeUrl, staticMapUrl, type GeoPoint } from '../../utils/yandexMaps';

interface EventPlaceCardProps {
  locationText: string;
  locationHint: string | null;
  point: GeoPoint;
}

/**
 * Блок «Место» события с гео-точкой (event-geo, кадр C мокапа): адрес + серое уточнение +
 * статичная мини-карта с пином (Static API, лёгкая картинка) + «🧭 Маршрут» /
 * «Открыть в Картах» (бесключевые deep-link'и). Тап по карте открывает точку в Яндекс.Картах.
 * Если картинка Static API не загрузилась — прячется, адрес и кнопки остаются.
 */
export const EventPlaceCard: FC<EventPlaceCardProps> = ({ locationText, locationHint, point }) => {
  const haptic = useHaptic();
  const [mapImageFailed, setMapImageFailed] = useState(false);

  const openExternal = (url: string) => {
    haptic.impact('light');
    window.open(url, '_blank', 'noopener,noreferrer');
  };

  return (
    <div className="rd-glass rd-geo-place" style={{ marginBottom: 14 }}>
      <div className="rd-geo-addr">
        <span className="rd-geo-addr-ic" aria-hidden="true">📍</span>
        <span className="rd-geo-addr-txt">
          <b>{locationText}</b>
          {locationHint && <span>{locationHint}</span>}
        </span>
      </div>

      {!mapImageFailed && (
        <img
          className="rd-geo-minimap"
          src={staticMapUrl(point)}
          alt="Карта места события"
          loading="lazy"
          onClick={() => openExternal(openMapUrl(point))}
          onError={() => setMapImageFailed(true)}
        />
      )}

      <div className="rd-geo-route">
        <button type="button" className="rd-btn-primary" onClick={() => openExternal(routeUrl(point))}>
          🧭 Маршрут
        </button>
        <button type="button" className="rd-btn-outline" onClick={() => openExternal(openMapUrl(point))}>
          Открыть в Картах
        </button>
      </div>
    </div>
  );
};
