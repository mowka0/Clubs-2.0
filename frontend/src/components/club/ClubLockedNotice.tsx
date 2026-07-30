import { FC } from 'react';

const LockIcon: FC = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="4" y="11" width="16" height="11" rx="2.5" />
    <path d="M8 11V7a4 4 0 1 1 8 0v4" />
  </svg>
);

interface ClubLockedNoticeProps {
  title: string;
  description: string;
}

/**
 * Плашка «содержимое под замком» — единственная форма объяснения, почему зритель не видит
 * активностей: гость, участник без взноса, должник по продлению и приглашённый. Текст всегда
 * разный, вид всегда один, поэтому вёрстка живёт здесь, а не копируется по страницам.
 */
export const ClubLockedNotice: FC<ClubLockedNoticeProps> = ({ title, description }) => (
  <div className="rd-glass rd-locked">
    <div className="rd-lock-ico"><LockIcon /></div>
    <div className="rd-text">
      <strong>{title}</strong>
      {description}
    </div>
  </div>
);
