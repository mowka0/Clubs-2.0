import { FC, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useHaptic } from '../../hooks/useHaptic';
import { PlaceSuggestionList } from './PlaceSuggestionList';
import { usePlaceSearch } from './usePlaceSearch';
import {
  DEFAULT_CENTER,
  PICKER_ZOOM_DEFAULT,
  PICKER_ZOOM_FOCUSED,
  createPickerMap,
  reverseGeocode,
  type GeoPoint,
  type PickerMap,
} from '../../utils/yandexMaps';

// Порог «центр карты не двигали» по каждой координате: 1e-6° ≈ 10 см — меньше, чем можно
// сдвинуть карту пальцем, но выше округления координат при перелёте к выбранной точке.
const SAME_POINT_EPSILON = 1e-6;

interface LocationPickerSheetProps {
  /** Ранее выбранная точка («Изменить») — карта открывается на ней с ближним зумом. */
  initial: GeoPoint | null;
  /** Клуб встречи: по его городу бэкенд сужает поиск подсказок. */
  clubId?: string;
  onSelect: (point: GeoPoint, address: string) => void;
  onClose: () => void;
}

/**
 * Боттом-шит «Место события» (venue-search, вариант C мокапа): строка поиска → лупа
 * (1 запрос Геосаджеста, live-автодополнения нет — бережём квоту) → подсказки шторкой
 * ПОВЕРХ карты → выбор строки (1 запрос за координатами) → карта летит к точке.
 * Пин закреплён в ЦЕНТРЕ контейнера (не маркер на карте) — тянешь карту, уточняешь точку.
 * «Готово» берёт координаты центра; название выбранного заведения при этом не теряется —
 * см. SAME_POINT_EPSILON в handleDone.
 * Fail-closed (решение PO): если JS API Яндекса не загрузился — «Готово» неактивна,
 * событие без точки создать нельзя.
 */
export const LocationPickerSheet: FC<LocationPickerSheetProps> = ({
  initial,
  clubId,
  onSelect,
  onClose,
}) => {
  const haptic = useHaptic();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<PickerMap | null>(null);
  const [mapState, setMapState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [saving, setSaving] = useState(false);
  const place = usePlaceSearch({
    clubId,
    onPicked: (point) => mapRef.current?.panTo(point, PICKER_ZOOM_FOCUSED),
  });

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

  const runSearch = () => { if (mapState === 'ready') void place.search(); };

  const handleDone = async () => {
    const map = mapRef.current;
    if (!map || mapState !== 'ready' || saving) return;
    // Координаты выбранной подсказки ещё едут — «Готово» сейчас взяло бы центр карты, то есть
    // Москву по умолчанию у того, кто карту не двигал. Кнопка на это время выключена, здесь —
    // страховка от гонки (тап успел пройти до ререндера).
    if (place.resolvingUri) return;
    haptic.impact('medium');
    const { lat, lon } = map.getCenter();
    const chosen = place.chosen;
    // Центр остался на выбранной подсказке — отдаём её название как есть. Иначе обратный
    // геокодинг затёр бы «Old Friends» улицей с номером дома (и стоил бы лишнего запроса).
    if (
      chosen
      && Math.abs(chosen.point.lat - lat) < SAME_POINT_EPSILON
      && Math.abs(chosen.point.lon - lon) < SAME_POINT_EPSILON
    ) {
      haptic.notify('success');
      onSelect(chosen.point, chosen.text);
      return;
    }
    setSaving(true);
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
              value={place.query}
              onChange={(e) => place.setQuery(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') runSearch(); }}
              placeholder="Название места или адрес"
              disabled={mapState === 'error'}
            />
            <button
              type="button"
              className="rd-geo-go"
              onClick={runSearch}
              disabled={place.searching || mapState !== 'ready'}
              aria-label="Найти адрес"
            >
              {place.searching ? '…' : '🔍'}
            </button>
          </div>

          {/* Одна строка-статус под поиском: ошибка → пустая выдача → выбранное место.
              Порядок = «последнее действие важнее»: пустая выдача возможна только после
              нового поиска, то есть свежее любого прежнего выбора. */}
          {place.error && <div className="rd-error" style={{ textAlign: 'left' }}>{place.error}</div>}
          {!place.error && place.nothingFound && (
            <div className="rd-geo-empty">
              <span aria-hidden="true">🤷</span>
              <span>Ничего не нашли. Уточните запрос или поставьте точку на карте вручную.</span>
            </div>
          )}
          {!place.error && !place.nothingFound && place.chosen && (
            <div className="rd-geo-found">
              <span className="ok" aria-hidden="true">✓</span>
              <span>{place.chosen.text} — уточните точку, потянув карту</span>
            </div>
          )}

          <div className="rd-geo-map">
            <div ref={containerRef} className="rd-geo-map-canvas" />
            {mapState === 'ready' && (
              <>
                <span className="rd-geo-pin" aria-hidden="true">📍</span>
                <span className="rd-geo-pin-hint">Пин в центре — тяните карту</span>
              </>
            )}
            {place.listOpen && place.suggestions && (
              <>
                {/* Тап мимо шторки закрывает список и возвращает карте жесты. */}
                <div className="rd-geo-sugg-backdrop" onClick={place.closeList} aria-hidden="true" />
                <PlaceSuggestionList
                  items={place.suggestions}
                  query={place.suggestedFor}
                  resolvingUri={place.resolvingUri}
                  onPick={place.pick}
                />
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
              disabled={mapState !== 'ready' || saving || place.resolvingUri !== null}
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
