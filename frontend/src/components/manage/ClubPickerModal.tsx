import { FC } from 'react';

export interface ClubPickerOption {
  id: string;
  name: string;
  avatarUrl: string | null;
  category: string;
}

interface ClubPickerListProps {
  clubs: ClubPickerOption[];
  /** Called with the chosen club id. No side effects here — the parent flow owns step/haptics. */
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
 * Content-only club list (no Modal wrapper). Rendered inside the single Modal
 * owned by CreateActivityFlow, which controls step transitions and haptics.
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
