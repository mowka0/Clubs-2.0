import { FC, useEffect, useMemo, useRef, useState } from 'react';
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
import { ProfileQuestCard, ProfileQuestCongrats } from '../components/profile/ProfileQuestCard';
import { SubscriptionCard } from '../components/subscription/SubscriptionCard';
import { tierWord, clubsPrepositional } from '../utils/reputationTier';

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

  // Поздравление профиль-квеста показываем ТОЛЬКО при переходе «не завершён → завершён»
  // в текущей сессии (после сохранения профиля). Уже-завершённый квест при загрузке
  // (старые пользователи, backfill V66) не поздравляем — карточки у них нет вовсе.
  const questCompleted = gamificationQuery.data?.quest.completed;
  const prevQuestCompleted = useRef<boolean | null>(null);
  const [congratsOpen, setCongratsOpen] = useState(false);
  useEffect(() => {
    if (questCompleted === undefined) return;
    if (prevQuestCompleted.current === false && questCompleted) setCongratsOpen(true);
    prevQuestCompleted.current = questCompleted;
  }, [questCompleted]);

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

      {user.bio ? (
        <div className="rd-bio">{user.bio}</div>
      ) : (
        // Пустой bio раньше просто скрывал секцию — теперь мягкий нудж, открывающий редактор профиля.
        <button
          type="button"
          className="rd-bio-nudge"
          onClick={() => { haptic.impact('light'); setEditOpen(true); }}
        >
          Добавь пару слов о себе →
        </button>
      )}

      {/* Карточка-квест «Прокачай профиль» — пока не завершён; на её месте поздравление
          при завершении в этой сессии (profile-quest.md, мокап 02-quest-card). */}
      {gam && !gam.quest.completed && (
        <ProfileQuestCard
          quest={gam.quest}
          onFill={() => { haptic.impact('light'); setEditOpen(true); }}
        />
      )}
      {congratsOpen && (
        <ProfileQuestCongrats
          // «Уровень 2!» — только если уровень взят именно квестом; при XP участия ≥ 50
          // (xp − 50 профильных ≥ порога «Свой») уровень 2 был и до квеста — титул нейтральный.
          title={gam && (gam.level > 2 || gam.xp >= 100) ? 'Профиль заполнен!' : 'Уровень 2 — «Свой»!'}
          onAck={() => { haptic.impact('light'); setCongratsOpen(false); }}
        />
      )}

      {interests.length > 0 ? (
        <>
          <div className="rd-section-sub-h">Интересы</div>
          <div className="rd-tags">
            {interests.map((interest) => (
              <span key={interest} className="rd-tag">{interest}</span>
            ))}
          </div>
        </>
      ) : interestsQuery.isError ? (
        // Сбой загрузки нельзя выдавать за «интересов нет»: пустота зовёт добавить,
        // а здесь честный ответ — ошибка и повтор. Ошибка фонового рефетча поверх
        // устаревших данных сюда не попадает — ветка выше продолжает показывать теги.
        <>
          <div className="rd-section-sub-h">Интересы</div>
          {/* role="alert" — скринридер озвучит сбой сразу при появлении плашки */}
          <div className="rd-glass rd-empty" role="alert">
            <div className="rd-title">Не удалось загрузить интересы</div>
            <button
              type="button"
              className="rd-ghost-btn"
              onClick={() => { haptic.impact('light'); interestsQuery.refetch(); }}
            >
              Повторить
            </button>
          </div>
        </>
      ) : null}
      {/* Лис-экран пустых интересов снят (PO 2026-07-22): к заполнению теперь ведёт
          карточка-квест выше — при подтверждённой пустоте секция просто скрыта. */}

      {/* «Уровень» — над статистикой (решение PO 2026-07-22): XP-прогресс первым. */}
      {gam ? (
        // Секцию «Уровень» показываем всегда при успешной загрузке: панель сама честно рендерит
        // нулевой стейт (Гость, 0 XP, пустой прогресс-бар) — это и есть тизер-скелет для новичка.
        // При ошибке фонового рефетча поверх устаревших данных остаёмся здесь, а не на плашке.
        <>
          <div className="rd-section-sub-h">Уровень</div>
          <GamificationPanel data={gam} />
          {gam.xp === 0 && gam.badges.length === 0 && (
            // Пояснение под нулевой панелью — откуда берётся XP и зачем уровни. Только на старте.
            <div className="rd-cta-hint">
              Это твой старт. XP начисляется за заполненный профиль, посещённые встречи
              и оплаченные складчины&nbsp;— с ним растут уровни и открываются бейджи.
            </div>
          )}
        </>
      ) : gamificationQuery.isError ? (
        // Сбой загрузки нельзя выдавать за «уровня нет»: честная плашка + повтор (F5-20), а не
        // молчаливо скрытая секция. Зеркало паттерна «Интересов» выше.
        <>
          <div className="rd-section-sub-h">Уровень</div>
          {/* role="alert" — скринридер озвучит сбой сразу при появлении плашки */}
          <div className="rd-glass rd-empty" role="alert">
            <div className="rd-title">Не удалось загрузить уровень</div>
            <button
              type="button"
              className="rd-ghost-btn"
              onClick={() => { haptic.impact('light'); gamificationQuery.refetch(); }}
            >
              Повторить
            </button>
          </div>
        </>
      ) : null}

      {/* «Статистика»: главные показатели одной панелью (решение PO 2026-07-22 — плитки
          слиты сюда). Надёжность — герой-строка: самый важный показатель профиля.
          Строки репутации — из ledger; посещения — сырые факты вне репутации. */}
      {(hasReputation || (rep?.visits?.totalEventsAttended ?? 0) > 0) && (
        <>
          <div className="rd-section-sub-h">Статистика</div>
          <div className="rd-glass rd-ostat" style={{ marginTop: 0, marginBottom: 14 }}>
            {hasReputation && (
              <>
                <div className="rd-ostat-row rd-ostat-hero">
                  <span className="rd-ostat-ico rd-ost-shield" aria-hidden="true">🛡</span>
                  <span>
                    <span className="rd-ostat-lbl">Надёжность</span>
                    <div className="rd-ostat-sub">{reliablePhrase}</div>
                  </span>
                  <span className="rd-ostat-val"><b>{globalScore ?? '—'}</b></span>
                </div>
                <div className="rd-ostat-row">
                  <span className="rd-ostat-ico rd-ost-clubs" aria-hidden="true">🤝</span>
                  <span>
                    <span className="rd-ostat-lbl">В клубах</span>
                    <div className="rd-ostat-sub">активных участий</div>
                  </span>
                  <span className="rd-ostat-val"><b>{activeClubs.length}</b></span>
                </div>
              </>
            )}
            {(rep?.visits?.totalEventsAttended ?? 0) > 0 && (
              <>
                <div className="rd-ostat-row">
                  <span className="rd-ostat-ico rd-ost-ticket" aria-hidden="true">🎟</span>
                  <span>
                    <span className="rd-ostat-lbl">Всего посетил событий</span>
                    <div className="rd-ostat-sub">по всем клубам, включая открытые встречи</div>
                  </span>
                  <span className="rd-ostat-val"><b>{rep!.visits.totalEventsAttended}</b></span>
                </div>
                {rep!.visits.openEventsAttended > 0 && (
                  <div className="rd-ostat-row">
                    <span className="rd-ostat-ico rd-ost-wave" aria-hidden="true">🌊</span>
                    <span>
                      <span className="rd-ostat-lbl">Из них открытых встреч</span>
                      <div className="rd-ostat-sub">вне репутации — просто факт участия</div>
                    </span>
                    <span className="rd-ostat-val"><b>{rep!.visits.openEventsAttended}</b></span>
                  </div>
                )}
              </>
            )}
          </div>
        </>
      )}

      <SubscriptionCard />

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
      ) : null}
      {/* Per-club список репутации переехал в «Мои клубы» (раскрывающиеся карточки клубов) —
          reputation-path-back.md. Здесь остаётся только глобальный блок (rd-stats выше). */}

      {editOpen && (
        <ProfileEditModal initialInterests={interests} onClose={() => setEditOpen(false)} />
      )}
    </div>
  );
};
