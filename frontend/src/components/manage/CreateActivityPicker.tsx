import { FC } from 'react';
import { Modal } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';

interface CreateActivityPickerProps {
  open: boolean;
  onClose: () => void;
  onSelectEvent: () => void;
  onSelectSkladchina: () => void;
}

interface PickerOption {
  key: 'event' | 'skladchina';
  emoji: string;
  title: string;
  subtitle: string;
}

const OPTIONS: PickerOption[] = [
  {
    key: 'event',
    emoji: '🗓',
    title: 'Событие',
    subtitle: 'Встреча с датой, временем, лимитом',
  },
  {
    key: 'skladchina',
    emoji: '💰',
    title: 'Сбор',
    subtitle: 'Сбор денег на бронь / инвентарь / подарок',
  },
];

const headerStyle: React.CSSProperties = {
  padding: '8px 16px 16px',
  fontSize: 13,
  fontWeight: 600,
  letterSpacing: 0.6,
  textTransform: 'uppercase',
  color: 'var(--tgui--hint_color, rgba(255,255,255,0.55))',
};

const optionStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 14,
  width: '100%',
  padding: '14px 16px',
  background: 'transparent',
  border: 'none',
  borderTop: '1px solid var(--tgui--divider, rgba(255,255,255,0.08))',
  cursor: 'pointer',
  textAlign: 'left',
  color: 'var(--tgui--text_color, #fff)',
};

const emojiStyle: React.CSSProperties = {
  fontSize: 24,
  flex: '0 0 auto',
  width: 32,
  textAlign: 'center',
};

const textStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
};

const titleStyle: React.CSSProperties = {
  fontSize: 16,
  fontWeight: 600,
};

const subtitleStyle: React.CSSProperties = {
  fontSize: 13,
  color: 'var(--tgui--hint_color, rgba(255,255,255,0.6))',
};

export const CreateActivityPicker: FC<CreateActivityPickerProps> = ({
  open,
  onClose,
  onSelectEvent,
  onSelectSkladchina,
}) => {
  const haptic = useHaptic();

  const handleSelect = (key: PickerOption['key']) => {
    haptic.impact('medium');
    onClose();
    if (key === 'event') onSelectEvent();
    else onSelectSkladchina();
  };

  const handleOpenChange = (next: boolean) => {
    if (!next) {
      haptic.impact('light');
      onClose();
    }
  };

  return (
    <Modal open={open} onOpenChange={handleOpenChange}>
      <div style={{ paddingBottom: 8 }}>
        <div style={headerStyle}>Создать активность</div>
        {OPTIONS.map((opt) => (
          <button
            key={opt.key}
            type="button"
            style={optionStyle}
            onClick={() => handleSelect(opt.key)}
          >
            <span style={emojiStyle} aria-hidden="true">{opt.emoji}</span>
            <span style={textStyle}>
              <span style={titleStyle}>{opt.title}</span>
              <span style={subtitleStyle}>{opt.subtitle}</span>
            </span>
          </button>
        ))}
      </div>
    </Modal>
  );
};
