import { FC, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useThemeStore } from '../store/useThemeStore';
import { useMyReputationQuery, useMyGamificationQuery } from '../queries/members';
import { useMyInterestsQuery } from '../queries/profile';
import { countryNameByCode } from '../components/CityPicker';
import { ProfileEditModal } from '../components/profile/ProfileEditModal';
import { GamificationPanel } from '../components/profile/GamificationPanel';
import { SubscriptionCard } from '../components/subscription/SubscriptionCard';
import { reliabilityTier, tierWord, clubsPrepositional } from '../utils/reputationTier';
import type { UserClubReputationDto } from '../types/api';

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

interface ReputationRowProps {
  row: UserClubReputationDto;
  onOpen: (clubId: string) => void;
}

const ReputationRow: FC<ReputationRowProps> = ({ row: r, onOpen }) => {
  const isOwnClub = r.role === 'organizer';
  const hasScore = r.trust !== null;
  const tier = reliabilityTier(r.trust);
  const hasActivity =
    hasScore &&
    ((r.totalAttendances ?? 0) > 0 ||
      (r.totalConfirmations ?? 0) > 0 ||
      (r.promiseFulfillmentPct ?? 0) > 0);
  return (
    <button
      type="button"
      className="rd-rep-row"
      onClick={() => onOpen(r.clubId)}
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
            <span className={`rd-v rd-${tier}`}>{r.trust}</span>
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
};

export const ProfilePage: FC = () => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const { user, login, isLoading: authLoading } = useAuthStore();
  const themeMode = useThemeStore((s) => s.mode);
  const cycleTheme = useThemeStore((s) => s.cycle);

  const reputationQuery = useMyReputationQuery();
  const gamificationQuery = useMyGamificationQuery();
  const interestsQuery = useMyInterestsQuery();

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

  const rep = reputationQuery.data;
  const gam = gamificationQuery.data;
  const activeClubs = rep?.activeClubs ?? [];
  const global = rep?.global;
  // История (покинутые клубы) живёт во вкладке «Клубы»; профиль показывает активную репутацию
  // + глобальный показатель (он считается по всей истории, включая покинутые клубы).
  const hasReputation = activeClubs.length > 0 || (global?.trackRecordClubs ?? 0) > 0;

  // Главный показатель: балл 0-100 + слово-уровень + широта опыта («опыт в N клубах»). Внутренний
  // показатель «N из M reliable» участвует в ранжировании, но не показывается на карточке
  // (см. TrustPolicy / design §9.1).
  const globalScore = global?.score ?? null;
  const reliablePhrase =
    global && global.trackRecordClubs > 0 && globalScore !== null
      ? `${tierWord(globalScore)} · опыт в ${global.trackRecordClubs} ${clubsPrepositional(global.trackRecordClubs)}`
      : 'пока недостаточно истории';

  const openClub = (clubId: string) => {
    haptic.impact('light');
    navigate(`/clubs/${clubId}`);
  };

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

      {hasReputation && (
        <div className="rd-stats">
          <div className="rd-stat rd-glass">
            <div className="rd-stat-label">Надёжность</div>
            <div className="rd-stat-value">{globalScore ?? '—'}</div>
            <div className="rd-stat-foot">{reliablePhrase}</div>
          </div>
          <div className="rd-stat rd-glass">
            <div className="rd-stat-label">В клубах</div>
            <div className="rd-stat-value rd-plain">{activeClubs.length}</div>
            <div className="rd-stat-foot">активных участий</div>
          </div>
        </div>
      )}

      {gam && (gam.xp > 0 || gam.badges.length > 0) && (
        <>
          <div className="rd-section-sub-h">Уровень</div>
          <GamificationPanel data={gam} />
        </>
      )}

      <SubscriptionCard />

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

      {!rep && reputationQuery.error ? (
        // Провалившийся запрос репутации не должен маскироваться под онбординг «клубов пока
        // нет» — вместо этого показываем явную ошибку + повтор (F5-20). Только когда данных
        // нет вообще; ошибка фонового рефетча поверх устаревших данных ниже продолжает
        // показывать устаревший список.
        <>
          <div className="rd-section-sub-h">Репутация</div>
          <div className="rd-glass rd-empty">
            <div className="rd-title">Не удалось загрузить репутацию</div>
            <div className="rd-sub">Проверьте соединение и попробуйте ещё раз.</div>
            <button
              type="button"
              className="rd-ghost-btn"
              onClick={() => { haptic.impact('light'); reputationQuery.refetch(); }}
            >
              Повторить
            </button>
          </div>
        </>
      ) : !hasReputation ? (
        <>
          <div className="rd-section-sub-h">Репутация</div>
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
        </>
      ) : activeClubs.length > 0 ? (
        <>
          <div className="rd-section-sub-h">
            Репутация
            <span className="rd-count"> · {activeClubs.length}</span>
          </div>
          <div className="rd-glass rd-rep-panel">
            {activeClubs.map((r) => (
              <ReputationRow key={r.clubId} row={r} onOpen={openClub} />
            ))}
          </div>
        </>
      ) : (
        <>
          <div className="rd-section-sub-h">Репутация</div>
          <div className="rd-glass rd-empty">
            <div className="rd-sub">Активных клубов сейчас нет — история клубов во&nbsp;вкладке «Клубы».</div>
          </div>
        </>
      )}

      {editOpen && (
        <ProfileEditModal initialInterests={interests} onClose={() => setEditOpen(false)} />
      )}
    </div>
  );
};
