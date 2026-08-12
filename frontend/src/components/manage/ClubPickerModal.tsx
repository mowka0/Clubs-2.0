import { FC } from 'react';
import { PickerStepHeader } from './CreateActivityPicker';

export interface ClubPickerOption {
  id: string;
  name: string;
  avatarUrl: string | null;
  category: string;
}

interface ClubPickerListProps {
  clubs: ClubPickerOption[];
  /** Вызывается с id выбранного клуба. Без побочных эффектов здесь — шаг/haptics владеет родительский flow. */
  onPick: (clubId: string) => void;
  /** Возврат на предыдущий шаг flow; не задан — возвращаться некуда. */
  onBack?: () => void;
}

function getInitials(name: string): string {
  return name
    .replace(/[«»"']/g, '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

/**
 * Список клубов — только контент (без обёртки Modal). Рендерится внутри единственного Modal,
 * которым владеет CreateActivityFlow — он управляет переходами шагов и haptics.
 */
export const ClubPickerList: FC<ClubPickerListProps> = ({ clubs, onPick, onBack }) => (
  <div className="club-picker">
    {/* Шапка общая с остальными шагами пикера — ради единого «Назад» во всём flow.
        Подписи шага в ней нет: решение PO 2026-08-11 (см. PickerStepHeader). */}
    <PickerStepHeader onBack={onBack} />
    {clubs.map((club) => (
      <button
        key={club.id}
        type="button"
        className="picker-row"
        onClick={() => onPick(club.id)}
      >
        <span className="avt" data-cat={club.category}>
          {club.avatarUrl ? <img src={club.avatarUrl} alt="" /> : getInitials(club.name)}
        </span>
        <span className="name">{club.name}</span>
        <span className="chevron" aria-hidden="true">›</span>
      </button>
    ))}
  </div>
);
