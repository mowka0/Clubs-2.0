import { FC, useState } from 'react';
import { BrandStepper } from '../BrandStepper';
import { useHaptic } from '../../hooks/useHaptic';

// Границы максимума участников — зеркалят валидацию бэкенда на participantLimit.
export const PARTICIPANT_MIN = 1;
export const PARTICIPANT_MAX = 1000;
// Минимум при включении переключателя (решение PO, event-formats.md § 9.2): двое — уже встреча.
const MIN_PARTICIPANTS_DEFAULT = 2;

/** Максимум и минимум участников обычной встречи; minParticipants = null — минимум выключен. */
export interface RosterLimits {
  participantLimit: number;
  minParticipants: number | null;
}

/**
 * Состояние пары «максимум + минимум» с инвариантами § 9.2 — ОДНО на форму создания и шторку
 * правки, чтобы правила не разъехались: включение даёт минимум 2 (не выше максимума), снижение
 * максимума ниже минимума подтягивает минимум. Дата ближе интервала набора здесь не учитывается:
 * такую встречу отклоняет сервер целиком, а не гасит минимум (решение PO 2026-09-05).
 */
export function useRosterLimits(initial: RosterLimits) {
  const [limits, setLimits] = useState<RosterLimits>(initial);

  const setParticipantLimit = (participantLimit: number) =>
    setLimits((l) => ({
      participantLimit,
      minParticipants: l.minParticipants === null ? null : Math.min(l.minParticipants, participantLimit),
    }));

  const setMinEnabled = (enabled: boolean) =>
    setLimits((l) => ({
      ...l,
      minParticipants: enabled ? Math.min(MIN_PARTICIPANTS_DEFAULT, l.participantLimit) : null,
    }));

  const setMinParticipants = (minParticipants: number) =>
    setLimits((l) => ({
      ...l,
      minParticipants: Math.min(Math.max(minParticipants, PARTICIPANT_MIN), l.participantLimit),
    }));

  return { limits, setLimits, setParticipantLimit, setMinEnabled, setMinParticipants };
}

export type RosterLimitsState = ReturnType<typeof useRosterLimits>;

interface RosterLimitsFieldsProps {
  state: RosterLimitsState;
}

/**
 * Степпер максимума и переключатель минимума со степпером — одна разметка на форму создания
 * и шторку правки (§ 9.2, § 9.3). Подписи называют правило вслух: это ровно то обещание,
 * которое система исполнит в дедлайн набора.
 */
export const RosterLimitsFields: FC<RosterLimitsFieldsProps> = ({ state }) => {
  const haptic = useHaptic();
  const { limits, setParticipantLimit, setMinEnabled, setMinParticipants } = state;
  const minEnabled = limits.minParticipants !== null;

  return (
    <>
      <div className="rd-field">
        <span className="rd-label">Максимум участников <span className="rd-req">*</span></span>
        <BrandStepper
          value={limits.participantLimit}
          onChange={setParticipantLimit}
          min={PARTICIPANT_MIN}
          max={PARTICIPANT_MAX}
          ariaLabel="Максимум участников"
        />
        <span className="rd-hint">
          Мест {limits.participantLimit}. Кто не успел — встанет в очередь на замену
        </span>
      </div>
      <div className="rd-field">
        <div className="rd-roster-min-row">
          <span className="rd-label">Минимум участников</span>
          <button
            type="button"
            className={`rd-cl-tgl${minEnabled ? ' on' : ''}`}
            role="switch"
            aria-checked={minEnabled}
            aria-label="Минимум участников"
            onClick={() => { haptic.select(); setMinEnabled(!minEnabled); }}
          />
        </div>
        {minEnabled && (
          <BrandStepper
            value={limits.minParticipants ?? PARTICIPANT_MIN}
            onChange={setMinParticipants}
            min={PARTICIPANT_MIN}
            max={limits.participantLimit}
            ariaLabel="Значение минимума"
          />
        )}
        <span className="rd-hint">
          {minEnabled
            ? `Собираемся, если будет минимум ${limits.minParticipants}. Не наберём к закрытию набора — встреча отменится`
            : 'Выключен — встреча состоится при любом составе'}
        </span>
      </div>
    </>
  );
};
