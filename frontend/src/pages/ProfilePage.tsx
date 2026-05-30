import { FC, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueries } from '@tanstack/react-query';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useMyReputationQuery } from '../queries/members';
import { useMyInterestsQuery } from '../queries/profile';
import { useMyApplicationsQuery } from '../queries/applications';
import { queryKeys } from '../queries/queryKeys';
import { getClub } from '../api/clubs';
import { BrandBackdrop } from '../components/BrandBackdrop';
import { countryNameByCode } from '../components/CityPicker';
import { ProfileEditModal } from '../components/profile/ProfileEditModal';
import type { ClubDetailDto } from '../types/api';

const GearIcon: FC = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </svg>
);

const STATUS_LABELS: Record<string, string> = {
  pending: 'На рассмотрении',
  approved: 'Одобрено',
  rejected: 'Отклонено',
  auto_rejected: 'Отклонено',
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

function reliabilityTier(score: number): 'high' | 'mid' | 'low' {
  if (score >= 85) return 'high';
  if (score >= 70) return 'mid';
  return 'low';
}

export const ProfilePage: FC = () => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const { user, login, isLoading: authLoading } = useAuthStore();

  const reputationQuery = useMyReputationQuery();
  const applicationsQuery = useMyApplicationsQuery();
  const interestsQuery = useMyInterestsQuery();

  const reputation = useMemo(() => reputationQuery.data ?? [], [reputationQuery.data]);
  const applications = applicationsQuery.data ?? [];
  const pendingApps = applications.filter((a) => a.status === 'pending');
  const interests = useMemo(() => interestsQuery.data ?? [], [interestsQuery.data]);

  const [editOpen, setEditOpen] = useState(false);

  useEffect(() => {
    if (!user) login();
  }, [user, login]);

  // Applications carry no club name — resolve it for the pending-apps list only.
  const appClubIds = useMemo(
    () => Array.from(new Set(pendingApps.map((a) => a.clubId))),
    [pendingApps],
  );
  const appClubQueries = useQueries({
    queries: appClubIds.map((id) => ({
      queryKey: queryKeys.clubs.detail(id),
      queryFn: () => getClub(id),
    })),
  });
  const appClubs: Record<string, ClubDetailDto> = {};
  appClubIds.forEach((id, idx) => {
    const data = appClubQueries[idx]?.data;
    if (data) appClubs[id] = data;
  });

  if (authLoading || reputationQuery.isPending) {
    return (
      <div className="brand-page" style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
        <BrandBackdrop />
        <Spinner size="l" />
      </div>
    );
  }

  if (!user) {
    return (
      <div className="brand-page">
        <BrandBackdrop />
        <Placeholder header="Ошибка" description="Не удалось загрузить профиль" />
      </div>
    );
  }

  const fullName = `${user.firstName}${user.lastName ? ` ${user.lastName}` : ''}`;
  const locationLabel = user.city
    ? [user.city, countryNameByCode(user.country)].filter(Boolean).join(', ')
    : null;

  return (
    <div className="brand-page">
      <BrandBackdrop />

      <header className="mc-hero">
        <div className="mc-hero-row">
          <h1>
            Твой <span className="accent">профиль</span>
          </h1>
          <button
            type="button"
            className="pf-gear"
            onClick={() => { haptic.impact('light'); setEditOpen(true); }}
            disabled={interestsQuery.isPending}
            aria-label="Редактировать профиль"
          >
            <GearIcon />
          </button>
        </div>
      </header>

      {/* Identity card */}
      <div className="pf-identity">
        <div className="avt">
          {user.avatarUrl ? <img src={user.avatarUrl} alt="" /> : getInitials(fullName) || '👤'}
        </div>
        <div className="meta">
          <div className="name">{fullName}</div>
          {user.telegramUsername && <div className="handle">@{user.telegramUsername}</div>}
          {locationLabel && <div className="location">{locationLabel}</div>}
        </div>
      </div>

      {/* Bio */}
      {user.bio && (
        <div className="pf-bio">{user.bio}</div>
      )}

      {/* Interests */}
      {interests.length > 0 && (
        <>
          <div className="mc-section-label">Интересы</div>
          <div className="pf-tags">
            {interests.map((interest) => (
              <span key={interest} className="pf-tag">{interest}</span>
            ))}
          </div>
        </>
      )}

      {/* Reputation per club — always shown; plate explains the section when empty */}
      <div className="mc-section-label">
        Моя репутация
        {reputation.length > 0 && <span className="count"> · {reputation.length}</span>}
      </div>
      {reputation.length === 0 ? (
        <div className="mc-empty">
          <div className="title">Тут появится репутация</div>
          <div className="sub">
            Вступи в клуб — будем считать твою надёжность по&nbsp;каждому из них.
          </div>
          <div className="actions">
            <button type="button" className="ghost-btn" onClick={() => { haptic.impact('light'); navigate('/'); }}>
              Найти клуб
            </button>
          </div>
        </div>
      ) : (
        <div className="pf-rep-list">
            {reputation.map((r) => {
              const tier = reliabilityTier(r.reliabilityIndex);
              const hasActivity =
                r.totalAttendances > 0 ||
                r.totalConfirmations > 0 ||
                r.promiseFulfillmentPct > 0;
              return (
                <button
                  key={r.clubId}
                  type="button"
                  className="pf-rep-card"
                  onClick={() => { haptic.impact('light'); navigate(`/clubs/${r.clubId}`); }}
                >
                  <span className="avt">
                    {r.clubAvatarUrl ? <img src={r.clubAvatarUrl} alt="" /> : getInitials(r.clubName)}
                  </span>
                  <div className="body">
                    <div className="name">{r.clubName}</div>
                    {hasActivity && (
                      <div className="metrics">
                        обещания {Math.round(r.promiseFulfillmentPct)}% · {r.totalConfirmations} подтв. · {r.totalAttendances} посещ.
                      </div>
                    )}
                  </div>
                  <div className="score">
                    <span className={`val ${tier}`}>{r.reliabilityIndex}</span>
                    <span className="cap">надёжность</span>
                  </div>
                </button>
              );
            })}
        </div>
      )}

      {/* Pending applications */}
      {pendingApps.length > 0 && (
        <>
          <div className="mc-section-label">
            Активные заявки <span className="count">· {pendingApps.length}</span>
          </div>
          <div className="pf-rep-list">
            {pendingApps.map((app) => {
              const club = appClubs[app.clubId];
              const name = club?.name ?? `Клуб ${app.clubId.slice(0, 8)}…`;
              return (
                <button
                  key={app.id}
                  type="button"
                  className="pf-rep-card"
                  onClick={() => { haptic.impact('light'); navigate(`/clubs/${app.clubId}`); }}
                >
                  <span className="avt">{getInitials(name)}</span>
                  <div className="body">
                    <div className="name">{name}</div>
                    <div className="role">{STATUS_LABELS[app.status] ?? app.status}</div>
                  </div>
                </button>
              );
            })}
          </div>
        </>
      )}

      {editOpen && (
        <ProfileEditModal initialInterests={interests} onClose={() => setEditOpen(false)} />
      )}
    </div>
  );
};
