import { FC, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useMemberProfileQuery } from '../../queries/members';
import { DonutRing } from '../reputation/DonutRing';
import { TRUST_TIER_COLOR, trustTier } from '../reputation/trust-tier';
import type { MemberListItemDto, MemberProfileDto } from '../../types/api';

interface MemberProfileModalProps {
  member: MemberListItemDto;
  clubId: string;
  onClose: () => void;
}

/**
 * Per-club reputation rings + spontaneity/role footer. Надёжность (smart composite) always shows;
 * Посещаемость (events) and Сборы (reputation-affecting skladchina) appear only when there's data,
 * so a member with only one axis never sees an empty "0/0" ring.
 */
const ReputationRings: FC<{ profile: MemberProfileDto }> = ({ profile }) => {
  const trust = profile.trust ?? 0;
  const confirmations = profile.totalConfirmations ?? 0;
  const attendances = profile.totalAttendances ?? 0;
  const attendancePct = confirmations > 0 ? Math.round((attendances / confirmations) * 100) : 0;
  const skladchinaPaid = profile.skladchinaPaid ?? 0;
  const skladchinaTotal = profile.skladchinaTotal ?? 0;
  const skladchinaPct = skladchinaTotal > 0 ? Math.round((skladchinaPaid / skladchinaTotal) * 100) : 0;
  const roleLabel = profile.role === 'organizer' ? 'Организатор' : 'Участник';

  return (
    <>
      <div className="rd-glass rd-rings">
        <div className="rd-ring">
          <DonutRing
            size={72}
            fraction={trust / 100}
            color={TRUST_TIER_COLOR[trustTier(trust)]}
            ariaLabel={`Надёжность ${trust} из 100`}
          >
            <span className="rd-ring-num">{trust}</span>
            <span className="rd-ring-cap">ИЗ 100</span>
          </DonutRing>
          <div className="rd-ring-l">надёжность</div>
        </div>

        {confirmations > 0 && (
          <div className="rd-ring">
            <DonutRing
              size={72}
              fraction={attendances / confirmations}
              color="var(--accent)"
              ariaLabel={`Посещаемость ${attendances} из ${confirmations}`}
            >
              <span className="rd-ring-frac">{attendances}/{confirmations}</span>
              <span className="rd-ring-cap">{attendancePct}%</span>
            </DonutRing>
            <div className="rd-ring-l">посещаемость</div>
          </div>
        )}

        {skladchinaTotal > 0 && (
          <div className="rd-ring">
            <DonutRing
              size={72}
              fraction={skladchinaPaid / skladchinaTotal}
              color="var(--accent)"
              ariaLabel={`Сборы: оплачено ${skladchinaPaid} из ${skladchinaTotal}`}
            >
              <span className="rd-ring-frac">{skladchinaPaid}/{skladchinaTotal}</span>
              <span className="rd-ring-cap">{skladchinaPct}%</span>
            </DonutRing>
            <div className="rd-ring-l">сборы</div>
          </div>
        )}
      </div>
      <div className="rd-rep-foot">
        Спонтанных визитов: <b>{profile.spontaneityCount ?? 0}</b> · Роль: {roleLabel}
      </div>
    </>
  );
};

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
    : null;
  const initials = `${member.firstName.charAt(0)}${member.lastName?.charAt(0) ?? ''}`;
  const bio = profile?.bio?.trim();
  const hasInterests = (profile?.interests.length ?? 0) > 0;

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
          {/* Avatar + name + (@username · в клубе с DATE) */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span className="rd-avatar" style={{ width: 56, height: 56, borderRadius: '50%', fontSize: 18 }}>
              {member.avatarUrl ? <img src={member.avatarUrl} alt="" /> : initials}
            </span>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 17, fontWeight: 700, color: 'var(--text)' }}>
                {member.firstName} {member.lastName ?? ''}
              </div>
              <div style={{ fontSize: 13, color: 'var(--text-dim)' }}>
                {[profile?.username ? `@${profile.username}` : null, joinedAt ? `в клубе с ${joinedAt}` : null]
                  .filter(Boolean)
                  .join(' · ')}
              </div>
            </div>
          </div>

          {/* About */}
          {bio && (
            <div className="rd-field">
              <span className="rd-label">О себе</span>
              <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{bio}</div>
            </div>
          )}

          {/* Interests */}
          {hasInterests && (
            <div className="rd-field">
              <span className="rd-label">Интересы</span>
              <div className="rd-tags" style={{ margin: 0 }}>
                {profile!.interests.map((interest) => (
                  <span key={interest} className="rd-tag">{interest}</span>
                ))}
              </div>
            </div>
          )}

          {/* Reputation in this club */}
          <div>
            <div className="rd-section-sub-h" style={{ margin: '0 0 8px' }}>Репутация в этом клубе</div>
            {loading ? (
              <div className="rd-spinner-row" style={{ padding: '8px 0' }}><Spinner size="s" /></div>
            ) : profile && profile.trust !== null ? (
              <ReputationRings profile={profile} />
            ) : (
              <div className="rd-glass rd-rep-panel">
                <div className="rd-kv">
                  <span>
                    {profile?.role === 'organizer'
                      ? 'Здесь репутация начисляется за организаторские качества'
                      : 'Новичок — пока недостаточно данных'}
                  </span>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </>,
    document.body,
  );
};
