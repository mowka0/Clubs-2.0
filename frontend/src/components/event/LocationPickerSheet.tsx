import { FC, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useHaptic } from '../../hooks/useHaptic';
import {
  DEFAULT_CENTER,
  GeocoderHttpError,
  PICKER_ZOOM_DEFAULT,
  PICKER_ZOOM_FOCUSED,
  createPickerMap,
  geocode,
  reverseGeocode,
  type GeoPoint,
  type PickerMap,
} from '../../utils/yandexMaps';

interface LocationPickerSheetProps {
  /** Ранее выбранная точка («Изменить») — карта открывается на ней с ближним зумом. */
  initial: GeoPoint | null;
  onSelect: (point: GeoPoint, address: string) => void;
  onClose: () => void;
}

/**
 * Боттом-шит «Место события» (event-geo, кадр B мокапа): строка поиска адреса → «Найти»
 * (1 запрос геокодера, live-саджеста нет — бережём лимит) → карта летит к результату →
 * пин закреплён в ЦЕНТРЕ контейнера (не маркер на карте) — тянешь карту, уточняешь точку →
 * «Готово» берёт координаты центра + 1 запрос обратного геокодера за адресом.
 * Fail-closed (решение PO): если JS API Яндекса не загрузился — «Готово» неактивна,
 * событие без точки создать нельзя.
 */
export const LocationPickerSheet: FC<LocationPickerSheetProps> = ({ initial, onSelect, onClose }) => {
  const haptic = useHaptic();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<PickerMap | null>(null);
  const [mapState, setMapState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);
  const [foundAddress, setFoundAddress] = useState<string | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    let map: PickerMap | null = null;
    const container = containerRef.current;
    if (!container) return undefined;
    createPickerMap(
      container,
      initial ?? DEFAULT_CENTER,
      initial ? PICKER_ZOOM_FOCUSED : PICKER_ZOOM_DEFAULT,
    )
      .then((created) => {
        if (cancelled) {
          created.destroy();
          return;
        }
        map = created;
        mapRef.current = created;
        setMapState('ready');
      })
      .catch(() => { if (!cancelled) setMapState('error'); });
    return () => {
      cancelled = true;
      map?.destroy();
      mapRef.current = null;
    };
    // Пикер монтируется заново при каждом открытии — initial фиксирован на время жизни шита.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSearch = async () => {
    const trimmed = query.trim();
    if (!trimmed || searching || mapState !== 'ready') return;
    haptic.impact('light');
    setSearching(true);
    setSearchError(null);
    try {
      const result = await geocode(trimmed);
      if (!result) {
        setFoundAddress(null);
        setSearchError('Не нашли адрес — попробуйте сформулировать иначе.');
      } else {
        setFoundAddress(result.address);
        mapRef.current?.panTo(result.point, PICKER_ZOOM_FOCUSED);
      }
    } catch (e) {
      setFoundAddress(null);
      // HTTP-ошибка (исчерпан суточный лимит / невалидный ключ) — это не проблема соединения,
      // не отправляем пользователя зря дёргать сеть.
      setSearchError(
        e instanceof GeocoderHttpError
          ? 'Поиск временно недоступен, попробуйте позже.'
          : 'Не получилось найти адрес — проверьте соединение и попробуйте ещё раз.',
      );
    } finally {
      setSearching(false);
    }
  };

  const handleDone = async () => {
    const map = mapRef.current;
    if (!map || mapState !== 'ready' || saving) return;
    haptic.impact('medium');
    setSaving(true);
    const { lat, lon } = map.getCenter();
    // Адрес — приятное дополнение, а не условие: если реверс-геокодер упал, местом
    // становятся координаты строкой (точка при этом полноценно выбрана).
    let address: string | null = null;
    try {
      address = await reverseGeocode({ lat, lon });
    } catch (_e) {
      address = null;
    }
    haptic.notify('success');
    onSelect({ lat, lon }, address ?? `${lat.toFixed(6)}, ${lon.toFixed(6)}`);
  };

  return createPortal(
    <>
      <div className="rd-sheet-overlay" onClick={onClose} aria-hidden="true" />
      <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Место события">
        <div className="rd-sheet-grabber" aria-hidden="true" />

        <div className="rd-sheet-body">
          <div className="rd-sheet-head"><h2>Место события</h2></div>

          <div className="rd-geo-search">
            <input
              className="rd-input"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') void handleSearch(); }}
              placeholder="Адрес: улица, дом, город"
              disabled={mapState === 'error'}
            />
            <button
              type="button"
              className="rd-geo-go"
              onClick={handleSearch}
              disabled={searching || mapState !== 'ready'}
              aria-label="Найти адрес"
            >
              {searching ? '…' : '🔍'}
            </button>
          </div>

          {foundAddress && !searchError && (
            <div className="rd-geo-found">
              <span className="ok" aria-hidden="true">✓</span>
              <span>Найдено: {foundAddress} — уточните точку, потянув карту</span>
            </div>
          )}
          {searchError && <div className="rd-error" style={{ textAlign: 'left' }}>{searchError}</div>}

          <div className="rd-geo-map">
            <div ref={containerRef} className="rd-geo-map-canvas" />
            {mapState === 'ready' && (
              <>
                <span className="rd-geo-pin" aria-hidden="true">📍</span>
                <span className="rd-geo-pin-hint">Пин в центре — тяните карту</span>
              </>
            )}
            {mapState === 'loading' && <div className="rd-geo-map-msg">Загружаем карту…</div>}
            {mapState === 'error' && (
              <div className="rd-geo-map-msg">Карты недоступны, попробуйте позже</div>
            )}
          </div>

          <div className="rd-sheet-actions">
            <button type="button" className="rd-btn-outline" onClick={onClose}>
              Отмена
            </button>
            <button
              type="button"
              className="rd-btn-primary"
              onClick={handleDone}
              disabled={mapState !== 'ready' || saving}
            >
              {saving ? 'Сохраняем…' : 'Готово'}
            </button>
          </div>
        </div>
      </div>
    </>,
    document.body,
  );
};
