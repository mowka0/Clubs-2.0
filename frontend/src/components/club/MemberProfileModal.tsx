import { FC, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useMemberProfileQuery } from '../../queries/members';
import type { MemberListItemDto } from '../../types/api';

interface MemberProfileModalProps {
  member: MemberListItemDto;
  clubId: string;
  onClose: () => void;
}

export const MemberProfileModal: FC<MemberProfileModalProps> = ({ member, clubId, onClose }) => {
  const profileQuery = useMemberProfileQuery(clubId, member.userId);
  const profile = profileQuery.data;
  const loading = profileQuery.isPending;

  // Lock background scroll while the sheet is open (same as the other rd-sheets).
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  const joinedAt = member.joinedAt
    ? new Date(member.joinedAt).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })
    : '—';
  const roleLabel = member.role === 'organizer' ? 'Организатор' : 'Участник';
  const initials = `${member.firstName.charAt(0)}${member.lastName?.charAt(0) ?? ''}`;

  return createPortal(
    <>
      <div className="rd-sheet-overlay" onClick={onClose} aria-hidden="true" />
      <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Профиль участника">
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <div className="rd-sheet-head">
          <h2>Профиль участника</h2>
          <button type="button" className="rd-sheet-close" onClick={onClose}>Закрыть</button>
        </div>

        <div className="rd-sheet-body">
          {/* Avatar + name */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span className="rd-avatar" style={{ width: 56, height: 56, borderRadius: '50%', fontSize: 18 }}>
              {member.avatarUrl ? <img src={member.avatarUrl} alt="" /> : initials}
            </span>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 17, fontWeight: 700, color: 'var(--text)' }}>
                {member.firstName} {member.lastName ?? ''}
              </div>
              {profile?.username && (
                <div style={{ fontSize: 13, color: 'var(--text-dim)' }}>@{profile.username}</div>
              )}
              {member.levelName && (
                <span
                  className="rd-badge rd-neutral"
                  style={{ marginTop: 4, fontSize: 10, padding: '2px 8px', display: 'inline-block' }}
                  title="Глобальный уровень участника"
                >
                  {member.levelName}
                </span>
              )}
            </div>
          </div>

          {/* Status in club */}
          <div>
            <div className="rd-section-sub-h" style={{ margin: '0 0 8px' }}>Статус в клубе</div>
            <div className="rd-glass rd-rep-panel">
              <div className="rd-kv"><span className="rd-v">Роль</span><span>{roleLabel}</span></div>
              <div className="rd-kv"><span className="rd-v">В клубе с</span><span>{joinedAt}</span></div>
            </div>
          </div>

          {/* Reputation */}
          {loading ? (
            <div className="rd-spinner-row" style={{ padding: '8px 0' }}><Spinner size="s" /></div>
          ) : profile ? (
            <div>
              <div className="rd-section-sub-h" style={{ margin: '0 0 8px' }}>Репутация</div>
              <div className="rd-glass rd-rep-panel">
                {profile.trust !== null ? (
                  <>
                    <div className="rd-kv"><span className="rd-v">Надёжность</span><span>{profile.trust}</span></div>
                    <div className="rd-kv"><span className="rd-v">Выполнение обещаний</span><span>{Math.round(profile.promiseFulfillmentPct ?? 0)}%</span></div>
                    <div className="rd-kv"><span className="rd-v">Подтверждений участия</span><span>{profile.totalConfirmations}</span></div>
                    <div className="rd-kv"><span className="rd-v">Посещений событий</span><span>{profile.totalAttendances}</span></div>
                    <div className="rd-kv"><span className="rd-v">Спонтанные визиты</span><span>{profile.spontaneityCount ?? 0}</span></div>
                  </>
                ) : profile.role === 'organizer' ? (
                  <div className="rd-kv"><span>Здесь репутация начисляется за организаторские качества</span></div>
                ) : (
                  <div className="rd-kv"><span>Новичок — пока недостаточно данных</span></div>
                )}
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </>,
    document.body,
  );
};
