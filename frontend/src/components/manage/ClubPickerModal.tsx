import { FC } from 'react';
import { Modal } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';

export interface ClubPickerOption {
  id: string;
  name: string;
  avatarUrl: string | null;
  category: string;
}

interface ClubPickerModalProps {
  open: boolean;
  clubs: ClubPickerOption[];
  onClose: () => void;
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

export const ClubPickerModal: FC<ClubPickerModalProps> = ({
  open,
  clubs,
  onClose,
  onPick,
}) => {
  const haptic = useHaptic();

  const handleSelect = (clubId: string) => {
    haptic.impact('medium');
    onClose();
    onPick(clubId);
  };

  const handleOpenChange = (next: boolean) => {
    if (!next) {
      haptic.impact('light');
      onClose();
    }
  };

  return (
    <Modal open={open} onOpenChange={handleOpenChange}>
      <div className="club-picker">
        <div className="picker-header">Выберите клуб</div>
        {clubs.map((club) => (
          <button
            key={club.id}
            type="button"
            className="picker-row"
            onClick={() => handleSelect(club.id)}
          >
            <span className="avt" data-cat={club.category}>
              {club.avatarUrl ? <img src={club.avatarUrl} alt="" /> : getInitials(club.name)}
            </span>
            <span className="name">{club.name}</span>
            <span className="chevron" aria-hidden="true">›</span>
          </button>
        ))}
      </div>
    </Modal>
  );
};
