import { FC, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueries } from '@tanstack/react-query';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useMyReputationQuery } from '../queries/members';
import { useMyApplicationsQuery } from '../queries/applications';
import { queryKeys } from '../queries/queryKeys';
import { getClub } from '../api/clubs';
import { BrandBackdrop } from '../components/BrandBackdrop';
import type { ClubDetailDto } from '../types/api';

const STATUS_LABELS: Record<string, string> = {
  pending: 'На рассмотрении',
  approved: 'Одобрено',
  rejected: 'Отклонено',
  auto_rejected: 'Отклонено',
};

const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};

/** Russian plural form picker: forms = [one, few, many] */
function pluralRu(n: number, forms: [string, string, string]): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return forms[0];
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return forms[1];
  return forms[2];
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

  const reputation = useMemo(() => reputationQuery.data ?? [], [reputationQuery.data]);
  const applications = applicationsQuery.data ?? [];
  const pendingApps = applications.filter((a) => a.status === 'pending');

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
  const empty = reputation.length === 0 && pendingApps.length === 0;

  return (
    <div className="brand-page">
      <BrandBackdrop />

      <header className="mc-hero">
        <div className="mc-hero-row">
          <h1>
            Твой <span className="accent">профиль</span>
          </h1>
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
        </div>
      </div>

      {/* Reputation per club */}
      {reputation.length > 0 && (
        <>
          <div className="mc-section-label">
            Моя репутация <span className="count">· {reputation.length}</span>
          </div>
          <div className="pf-rep-list">
            {reputation.map((r) => {
              const tier = reliabilityTier(r.reliabilityIndex);
              const roleLabel = r.role === 'organizer' ? 'Организатор' : 'Участник';
              const categoryLabel = CATEGORY_LABELS[r.category] ?? r.category;
              const hasActivity = r.totalAttendances > 0 || r.promiseFulfillmentPct > 0;
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
                    <div className="role">{categoryLabel} · {roleLabel}</div>
                    {hasActivity && (
                      <div className="metrics">
                        обещания {Math.round(r.promiseFulfillmentPct)}% · {r.totalAttendances}{' '}
                        {pluralRu(r.totalAttendances, ['посещение', 'посещения', 'посещений'])}
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
        </>
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

      {empty && (
        <div className="mc-empty">
          <div className="title">Профиль пока пуст</div>
          <div className="sub">
            Вступите в клуб — здесь появится ваша репутация по&nbsp;каждому из них.
          </div>
          <div className="actions">
            <button type="button" className="ghost-btn" onClick={() => { haptic.impact('light'); navigate('/'); }}>
              Найти клуб
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
