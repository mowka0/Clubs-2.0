import { FC } from 'react';

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
export const ClubPickerList: FC<ClubPickerListProps> = ({ clubs, onPick }) => (
  <div className="club-picker">
    <div className="picker-header">Выберите клуб</div>
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
