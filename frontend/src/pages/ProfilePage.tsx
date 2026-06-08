import { FC, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useThemeStore } from '../store/useThemeStore';
import { useMyReputationQuery } from '../queries/members';
import { useMyInterestsQuery } from '../queries/profile';
import { countryNameByCode } from '../components/CityPicker';
import { ProfileEditModal } from '../components/profile/ProfileEditModal';

const GearIcon: FC = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </svg>
);

const THEME_META: Record<'system' | 'light' | 'dark', { label: string; glyph: string }> = {
  system: { label: 'Авто', glyph: '◐' },
  light: { label: 'Светлая', glyph: '☀' },
  dark: { label: 'Тёмная', glyph: '☾' },
};

function getInitials(name: string): string {
  return name
    .replace(/[«»"']/g, '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

type ReliabilityTier = 'high' | 'mid' | 'low' | 'new';

function reliabilityTier(score: number | null): ReliabilityTier {
  if (score === null) return 'new';
  if (score >= 85) return 'high';
  if (score >= 70) return 'mid';
  return 'low';
}

function tierLabel(tier: ReliabilityTier): string {
  if (tier === 'new') return '';
  return tier === 'high' ? 'высокая' : tier === 'mid' ? 'средняя' : 'низкая';
}

export const ProfilePage: FC = () => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const { user, login, isLoading: authLoading } = useAuthStore();
  const themeMode = useThemeStore((s) => s.mode);
  const cycleTheme = useThemeStore((s) => s.cycle);

  const reputationQuery = useMyReputationQuery();
  const interestsQuery = useMyInterestsQuery();

  const reputation = useMemo(() => reputationQuery.data ?? [], [reputationQuery.data]);
  const interests = useMemo(() => interestsQuery.data ?? [], [interestsQuery.data]);

  const [editOpen, setEditOpen] = useState(false);

  useEffect(() => {
    if (!user) login();
  }, [user, login]);

  if (authLoading || reputationQuery.isPending) {
    return (
      <div className="rd-page" style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
        <Spinner size="l" />
      </div>
    );
  }

  if (!user) {
    return (
      <div className="rd-page">
        <Placeholder header="Ошибка" description="Не удалось загрузить профиль" />
      </div>
    );
  }

  const fullName = `${user.firstName}${user.lastName ? ` ${user.lastName}` : ''}`;
  const locationLabel = user.city
    ? [user.city, countryNameByCode(user.country)].filter(Boolean).join(', ')
    : null;
  const handleParts = [user.telegramUsername ? `@${user.telegramUsername}` : null, locationLabel]
    .filter(Boolean)
    .join(' · ');

  const clubsJoined = reputation.length;
  // Average only over clubs with a real number — newcomers and own-club (organizer)
  // rows are null and must be excluded, not counted as 0 (and never poison the avg).
  const scoredClubs = reputation.filter((r) => r.reliabilityIndex !== null);
  const avgReputation = scoredClubs.length > 0
    ? Math.round(scoredClubs.reduce((sum, r) => sum + (r.reliabilityIndex ?? 0), 0) / scoredClubs.length)
    : null;
  const avgTier = avgReputation !== null ? reliabilityTier(avgReputation) : null;

  const theme = THEME_META[themeMode];

  return (
    <div className="rd-page">
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginBottom: 8 }}>
        <button
          type="button"
          className="rd-city-pill"
          onClick={() => { haptic.select(); cycleTheme(); }}
          aria-label="Сменить тему оформления"
        >
          <span aria-hidden="true">{theme.glyph}</span>
          {theme.label}
        </button>
        <button
          type="button"
          className="rd-icon-btn"
          onClick={() => { haptic.impact('light'); setEditOpen(true); }}
          disabled={interestsQuery.isPending}
          aria-label="Редактировать профиль"
        >
          <GearIcon />
        </button>
      </div>

      <div className="rd-pf-identity">
        <div className="rd-avt">
          {user.avatarUrl ? <img src={user.avatarUrl} alt="" /> : getInitials(fullName) || '👤'}
        </div>
        <div className="rd-name">
          {fullName}
          <span className="rd-badge-star" aria-hidden="true" />
        </div>
        {handleParts && <div className="rd-handle">{handleParts}</div>}
      </div>

      {user.bio && <div className="rd-bio">{user.bio}</div>}

      {clubsJoined > 0 && (
        <div className="rd-stats">
          <div className="rd-stat rd-glass">
            <div className="rd-stat-label">Надёжность</div>
            <div className="rd-stat-value">{avgReputation ?? '—'}</div>
            <div className="rd-stat-foot">{avgTier ? tierLabel(avgTier) : 'пока нет данных'}</div>
          </div>
          <div className="rd-stat rd-glass">
            <div className="rd-stat-label">В клубах</div>
            <div className="rd-stat-value rd-plain">{clubsJoined}</div>
            <div className="rd-stat-foot">активных участий</div>
          </div>
        </div>
      )}

      {interests.length > 0 && (
        <>
          <div className="rd-section-sub-h">Интересы</div>
          <div className="rd-tags">
            {interests.map((interest) => (
              <span key={interest} className="rd-tag">{interest}</span>
            ))}
          </div>
        </>
      )}

      <div className="rd-section-sub-h">
        Репутация
        {clubsJoined > 0 && <span className="rd-count"> · {clubsJoined}</span>}
      </div>

      {clubsJoined === 0 ? (
        <div className="rd-glass rd-empty">
          <div className="rd-title">Тут появится репутация</div>
          <div className="rd-sub">
            Вступи в клуб — будем считать твою надёжность по&nbsp;каждому из них.
          </div>
          <button
            type="button"
            className="rd-ghost-btn"
            onClick={() => { haptic.impact('light'); navigate('/'); }}
          >
            Найти клуб
          </button>
        </div>
      ) : (
        <div className="rd-glass rd-rep-panel">
          {reputation.map((r) => {
            const isOwnClub = r.role === 'organizer';
            const hasScore = r.reliabilityIndex !== null;
            const tier = reliabilityTier(r.reliabilityIndex);
            const hasActivity =
              hasScore &&
              ((r.totalAttendances ?? 0) > 0 ||
                (r.totalConfirmations ?? 0) > 0 ||
                (r.promiseFulfillmentPct ?? 0) > 0);
            return (
              <button
                key={r.clubId}
                type="button"
                className="rd-rep-row"
                onClick={() => { haptic.impact('light'); navigate(`/clubs/${r.clubId}`); }}
              >
                <span className="rd-ico">
                  {r.clubAvatarUrl ? <img src={r.clubAvatarUrl} alt="" /> : getInitials(r.clubName)}
                </span>
                <div className="rd-info">
                  <div className="rd-ttl">{r.clubName}</div>
                  {hasActivity && (
                    <div className="rd-met">
                      обещания {Math.round(r.promiseFulfillmentPct ?? 0)}% · {r.totalConfirmations} подтв. · {r.totalAttendances} посещ.
                      {(r.spontaneityCount ?? 0) > 0 && ` · ${r.spontaneityCount} спонт.`}
                    </div>
                  )}
                  {!hasScore && isOwnClub && (
                    <div className="rd-met">Здесь репутация начисляется за организаторские качества</div>
                  )}
                </div>
                <div className="rd-score">
                  {hasScore ? (
                    <>
                      <span className={`rd-v rd-${tier}`}>{r.reliabilityIndex}</span>
                      <span className="rd-cap">надёжность</span>
                    </>
                  ) : isOwnClub ? (
                    <>
                      <span className="rd-v rd-new">Организатор</span>
                      <span className="rd-cap">ваш клуб</span>
                    </>
                  ) : (
                    <>
                      <span className="rd-v rd-new">Новичок</span>
                      <span className="rd-cap">пока нет данных</span>
                    </>
                  )}
                </div>
              </button>
            );
          })}
        </div>
      )}

      {editOpen && (
        <ProfileEditModal initialInterests={interests} onClose={() => setEditOpen(false)} />
      )}
    </div>
  );
};
