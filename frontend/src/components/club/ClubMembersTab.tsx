import { FC, useState } from 'react';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useClubMembersQuery, useMarkMemberDuesPaidMutation } from '../../queries/members';
import { useHaptic } from '../../hooks/useHaptic';
import { ApiError } from '../../api/apiClient';
import { Toast } from '../Toast';
import { MemberProfileModal } from './MemberProfileModal';
import { isManagerRole, membershipRoleLabel } from '../../utils/membershipRole';
import { reliabilityTier } from '../../utils/reputationTier';
import type { MemberListItemDto } from '../../types/api';

interface ClubMembersTabProps {
  clubId: string;
  /**
   * Является ли вызывающий менеджером [clubId] (владелец или активный со-организатор). Включает
   * организаторский вид строк участников (инфо о доступе + тап для управления). Обычные участники
   * видят спокойный список только активных (поля доступа null).
   */
  isOrganizer?: boolean;
  /**
   * Вызывающий — владелец клуба (co-organizers): открывает в карточке участника секцию смены роли
   * («Сделать/Снять со-организатора»). Со-организатор — менеджер (isOrganizer), но не владелец.
   */
  isOwner?: boolean;
  /**
   * Организаторский дашборд-вид: рендерит attention-блоки «Скоро закончится» / «Оплата вступления».
   * Участники живут на вкладке «Участники» страницы клуба (без дубля в «Управлении»), поэтому там
   * передаётся managementView={isOrganizer}: владелец видит бакеты, обычный участник — плоский список.
   */
  managementView?: boolean;
}

// Окно (в днях), в котором истекающий платный доступ попадает в «Скоро закончится» (зеркалит red-dot бэкенда).
const EXPIRING_SOON_DAYS = 7;
// Миллисекунд в сутках — для расчёта дней до/после даты.
const MS_PER_DAY = 86_400_000;

function getInitials(firstName: string, lastName: string | null): string {
  const first = firstName.charAt(0).toUpperCase();
  const last = lastName ? lastName.charAt(0).toUpperCase() : '';
  return first + last;
}

function fullNameOf(member: MemberListItemDto): string {
  return `${member.firstName}${member.lastName ? ` ${member.lastName}` : ''}`;
}

function pluralRu(n: number, forms: [string, string, string]): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return forms[0];
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return forms[1];
  return forms[2];
}

/** Целых дней от «сейчас» до [iso] (округление вверх — «через 3 дня»). Отрицательно после даты. */
function daysUntil(iso: string): number {
  return Math.ceil((new Date(iso).getTime() - Date.now()) / MS_PER_DAY);
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
}

/** «до 28 июня · через 3 дня» для блока «Скоро закончится» (уже-истёкшие живут в бакете «Доступ истёк»). */
function formatExpiringMeta(iso: string): string {
  const days = daysUntil(iso);
  const date = `до ${formatDate(iso)}`;
  if (days <= 0) return `${date} · истекла`;
  if (days === 1) return `${date} · завтра`;
  return `${date} · через ${days} ${pluralRu(days, ['день', 'дня', 'дней'])}`;
}

/** «истекла 5 июня · 3 дня назад» для блока «Доступ истёк». */
function formatExpiredMeta(iso: string): string {
  const daysAgo = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / MS_PER_DAY));
  const date = `истекла ${formatDate(iso)}`;
  if (daysAgo === 0) return `${date} · сегодня`;
  if (daysAgo === 1) return `${date} · вчера`;
  return `${date} · ${daysAgo} ${pluralRu(daysAgo, ['день', 'дня', 'дней'])} назад`;
}

/** «вступил(а) 2 дня назад» для блока «Ждут оплаты» (frozen). */
function formatJoinedMeta(iso: string | null): string {
  if (!iso) return 'ждёт первой оплаты';
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / MS_PER_DAY);
  if (days <= 0) return 'вступил(а) сегодня';
  if (days === 1) return 'вступил(а) вчера';
  if (days < 7) return `вступил(а) ${days} ${pluralRu(days, ['день', 'дня', 'дней'])} назад`;
  return `вступил(а) ${formatDate(iso)}`;
}

type Bucket = 'expired' | 'expiring' | 'awaiting' | 'calm';

/** Дописывает к мете бакета пометку живого claim — участник заявил оплату (в т.ч. раннее
 *  продление из «Скоро закончится»), организатору осталось проверить и нажать «Взнос получен». */
function withClaimMark(member: MemberListItemDto, meta: string): string {
  if (!member.duesClaimedAt) return meta;
  return meta ? `${meta} · оплата заявлена` : 'оплата заявлена';
}

/**
 * Раскладывает участника по бакету состояния доступа (de-Stars дашборд). У обычного зрителя поля
 * доступа null, поэтому все участники попадают в «calm» — прежний список только активных.
 * Бакет «Доступ истёк» — статусный (accessStatus === 'expired', должники по продлению, стабильное
 * множество); active с уже прошедшим окном попадает туда же как safety-окно до ближайшего тика
 * шедулера (processExpiry, 9:00, переводит таких в expired). «frozen» — только новые участники,
 * ждущие ПЕРВОГО взноса («Оплата вступления»).
 */
function bucketOf(member: MemberListItemDto): Bucket {
  if (member.accessStatus === 'expired') return 'expired';
  if (member.accessStatus === 'frozen') return 'awaiting';
  if (member.accessStatus === 'active' && member.subscriptionExpiresAt) {
    const days = daysUntil(member.subscriptionExpiresAt);
    if (days <= 0) return 'expired';
    if (days <= EXPIRING_SOON_DAYS) return 'expiring';
  }
  return 'calm';
}

interface DuesActionRowProps {
  clubId: string;
  member: MemberListItemDto;
  metaText: string;
  onOpenProfile: (member: MemberListItemDto) => void;
  onFeedback: (message: string) => void;
}

/**
 * Строка «Скоро закончится» / «Ждут оплаты»: тап по участнику открывает карточку профиля; кнопка
 * «Взнос получен» открывает доступ и продлевает оплаченное окно на +30 дней. Строка — div (не button),
 * чтобы два тап-таргета не вкладывались друг в друга. 409 (проигранная гонка) глотается — кэш списка
 * уже обновлён.
 */
const DuesActionRow: FC<DuesActionRowProps> = ({ clubId, member, metaText, onOpenProfile, onFeedback }) => {
  const haptic = useHaptic();
  const markPaid = useMarkMemberDuesPaidMutation();

  const handleDuesPaid = () => {
    if (markPaid.isPending) return;
    haptic.impact('medium');
    markPaid.mutate(
      { clubId, userId: member.userId },
      {
        onSuccess: () => {
          haptic.notify('success');
          onFeedback(`Взнос принят — доступ ${member.firstName} продлён на 30 дней`);
        },
        onError: (e) => {
          if (e instanceof ApiError && e.status === 409) return;
          haptic.notify('error');
          onFeedback(e instanceof Error ? e.message : 'Не удалось отметить взнос');
        },
      },
    );
  };

  return (
    <div className="rd-rep-row">
      <button
        type="button"
        className="rd-rep-row-main"
        onClick={() => { haptic.impact('light'); onOpenProfile(member); }}
      >
        <span className="rd-ico">
          {member.avatarUrl ? <img src={member.avatarUrl} alt="" /> : getInitials(member.firstName, member.lastName)}
        </span>
        <div className="rd-info">
          <div className="rd-ttl">{fullNameOf(member)}</div>
          <div className="rd-met">{metaText}</div>
        </div>
      </button>
      <button type="button" className="rd-row-act-pri" disabled={markPaid.isPending} onClick={handleDuesPaid}>
        {markPaid.isPending ? '…' : 'Взнос получен'}
      </button>
    </div>
  );
};

interface CalmMemberRowProps {
  member: MemberListItemDto;
  forOrganizer: boolean;
  onOpenProfile: (member: MemberListItemDto) => void;
}

/** Спокойная строка «Активные»: очки репутации справа; организатору у платного участника также
 *  показывается «Активен · до DATE». */
const CalmMemberRow: FC<CalmMemberRowProps> = ({ member, forOrganizer, onOpenProfile }) => {
  const haptic = useHaptic();
  const isOwner = member.role === 'organizer';
  // Бейдж роли у имени (PO №4): владелец — «Организатор», со-орг — «Со-организатор», участник — без бейджа.
  const roleBadge = isManagerRole(member.role) ? membershipRoleLabel(member.role) : null;
  // Без доступа: frozen (первый взнос не подтверждён) или expired (просрочил продление). Такие строки
  // видит только организатор (бэкенд скрывает их от обычных зрителей), так что «ледяное» оформление —
  // только у него.
  const isFrozen = member.accessStatus === 'frozen';
  const isExpired = member.accessStatus === 'expired';
  const hasScore = member.trust !== null;
  // Строку обещаний показываем только при наличии событийного трека; участник «только финансы»
  // (запись по складчине, 0 подтверждений) сохраняет очки, но прячет обманчивое «Обещания 0%» (F5-08).
  const hasActivity = hasScore && (member.totalConfirmations ?? 0) > 0;
  const tier = reliabilityTier(member.trust);
  // trust=null неотличим от «нет истории» ↔ «скрыто асимметрией» (бэкенд занулил чужие скоры для
  // не-организатора). Поэтому фолбэк-мету «Пока нет данных» показываем ТОЛЬКО организатору: у него
  // null = честно «нет истории». Обычному зрителю — ничего (иначе врём «новичок» тому, у кого история есть).
  const repMeta = hasActivity
    ? `Обещания ${Math.round(member.promiseFulfillmentPct ?? 0)}%`
    : isOwner
      ? 'Репутация за организаторские качества'
      : hasScore
        ? null
        : forOrganizer
          ? 'Пока нет данных'
          : null;
  // Видимая только организатору строка доступа платного активного участника (у бесплатных членств нет
  // срока). Гейт по accessStatus === 'active': у expired тоже есть subscriptionExpiresAt, но «Активен» врал бы.
  const accessMeta = forOrganizer && !isOwner && member.accessStatus === 'active' && member.subscriptionExpiresAt
    ? `Активен · до ${formatDate(member.subscriptionExpiresAt)}`
    : null;
  // Публичные награды клуба (R3) — защищаемся от payload без этого поля (граница не проверяется схемой).
  const awards = member.awards ?? [];

  return (
    <button
      type="button"
      className={`rd-rep-row${isFrozen || isExpired ? ' rd-frozen' : ''}`}
      onClick={() => { haptic.impact('light'); onOpenProfile(member); }}
    >
      <span className="rd-ico">
        {member.avatarUrl ? <img src={member.avatarUrl} alt="" /> : getInitials(member.firstName, member.lastName)}
      </span>
      <div className="rd-info">
        <div className="rd-ttl">
          {fullNameOf(member)}
          {roleBadge && (
            <span className="rd-badge rd-rep" style={{ marginLeft: 8, fontSize: 10, padding: '2px 8px' }}>{roleBadge}</span>
          )}
        </div>
        {/* Чипы наград идут сразу под именем (над очками надёжности). */}
        {awards.length > 0 && (
          <div className="rd-member-awards">
            {awards.map((a) => (
              <span key={a.id} className="rd-award-chip rd-award-chip-ro rd-award-chip-sm">
                <span className="rd-award-emoji" aria-hidden="true">{a.emoji}</span>{a.label}
              </span>
            ))}
          </div>
        )}
        {isFrozen && <div className="rd-met rd-met-frozen">❄️ Доступ закрыт</div>}
        {isExpired && <div className="rd-met rd-met-frozen">⛔ Доступ истёк</div>}
        {accessMeta && <div className="rd-met rd-met-ok">{accessMeta}</div>}
        {repMeta && <div className="rd-met">{repMeta}</div>}
      </div>
      <span className="rd-score">
        {hasScore ? (
          <>
            <span className={`rd-v rd-${tier}`}>{member.trust}</span>
            <span className="rd-cap">надёжность</span>
          </>
        ) : isOwner ? (
          // «Орг» — ролевая метка организатора клуба, не скор → видна всем.
          <span className="rd-v rd-new">Орг</span>
        ) : forOrganizer ? (
          // «Новичок» — фолбэк отсутствия истории; только организатору (у него trust=null честен).
          // Обычному зрителю не пишем ничего: его null может скрывать реальную историю (асимметрия #94).
          <span className="rd-v rd-new">Новичок</span>
        ) : null}
      </span>
    </button>
  );
};

export const ClubMembersTab: FC<ClubMembersTabProps> = ({ clubId, isOrganizer = false, isOwner = false, managementView = false }) => {
  const [selectedMember, setSelectedMember] = useState<MemberListItemDto | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const membersQuery = useClubMembersQuery(clubId);

  if (membersQuery.isPending) {
    return (
      <div className="rd-spinner-row">
        <Spinner size="l" />
      </div>
    );
  }

  if (membersQuery.error) {
    return <Placeholder header="Ошибка" description={membersQuery.error.message} />;
  }

  const members = membersQuery.data ?? [];
  // Attention-бакеты — управленческая информация: их видит только организатор в managementView
  // (страница клуба передаёт managementView={isOrganizer}). Обычный зритель — плоский список.
  const showBuckets = isOrganizer && managementView;
  const expired = showBuckets ? members.filter((m) => bucketOf(m) === 'expired') : [];
  const expiring = showBuckets ? members.filter((m) => bucketOf(m) === 'expiring') : [];
  const awaiting = showBuckets ? members.filter((m) => bucketOf(m) === 'awaiting') : [];
  // В бакет-виде участники без доступа живут в «Оплата вступления» (frozen) / «Доступ истёк» (expired).
  // В плоском списке (без managementView) они остаются, но тонут вниз, «ледяные» — организатор с
  // одного взгляда видит, у кого нет доступа, не теряя их в общей массе. (sort стабильный → сохраняет
  // порядок бэкенда.)
  const hasNoAccess = (m: MemberListItemDto) => m.accessStatus === 'frozen' || m.accessStatus === 'expired';
  const calm = showBuckets
    ? members.filter((m) => bucketOf(m) === 'calm')
    : [...members].sort((a, b) => Number(hasNoAccess(a)) - Number(hasNoAccess(b)));

  return (
    <>
      {expired.length > 0 && (
        <>
          <div className="rd-section-sub-h rd-attn-exp">
            ⛔ Доступ истёк <span className="rd-count">· {expired.length}</span>
          </div>
          <div className="rd-attn-hint">
            Оплаченный период закончился — доступ закрыт до нового взноса. Получил продление — подтверди.
          </div>
          <div className="rd-glass rd-rep-panel rd-attn-block rd-attn-block-exp">
            {expired.map((member) => (
              <DuesActionRow
                key={member.userId}
                clubId={clubId}
                member={member}
                metaText={withClaimMark(member, member.subscriptionExpiresAt ? formatExpiredMeta(member.subscriptionExpiresAt) : '')}
                onOpenProfile={setSelectedMember}
                onFeedback={setToast}
              />
            ))}
          </div>
        </>
      )}

      {expiring.length > 0 && (
        <>
          <div className="rd-section-sub-h rd-attn-exp">
            ⏳ Скоро закончится <span className="rd-count">· {expiring.length}</span>
          </div>
          <div className="rd-attn-hint">Подписка кончается в ближайшую неделю. Получил продление — подтверди.</div>
          <div className="rd-glass rd-rep-panel rd-attn-block rd-attn-block-exp">
            {expiring.map((member) => (
              <DuesActionRow
                key={member.userId}
                clubId={clubId}
                member={member}
                metaText={withClaimMark(member, member.subscriptionExpiresAt ? formatExpiringMeta(member.subscriptionExpiresAt) : '')}
                onOpenProfile={setSelectedMember}
                onFeedback={setToast}
              />
            ))}
          </div>
        </>
      )}

      {awaiting.length > 0 && (
        <>
          <div className="rd-section-sub-h rd-attn-pay">
            💸 Оплата вступления <span className="rd-count">· {awaiting.length}</span>
          </div>
          <div className="rd-attn-hint">Вступили — подтвердите взнос, чтобы открыть доступ.</div>
          <div className="rd-glass rd-rep-panel rd-attn-block rd-attn-block-pay">
            {awaiting.map((member) => (
              <DuesActionRow
                key={member.userId}
                clubId={clubId}
                member={member}
                metaText={formatJoinedMeta(member.joinedAt)}
                onOpenProfile={setSelectedMember}
                onFeedback={setToast}
              />
            ))}
          </div>
        </>
      )}

      <div className="rd-section-sub-h">
        Участники <span className="rd-count">· {calm.length}</span>
      </div>

      {calm.length === 0 ? (
        <div className="rd-glass rd-empty">
          <div className="rd-sub">Список участников пуст</div>
        </div>
      ) : (
        <div className="rd-glass rd-rep-panel">
          {calm.map((member) => (
            <CalmMemberRow
              key={member.userId}
              member={member}
              forOrganizer={isOrganizer}
              onOpenProfile={setSelectedMember}
            />
          ))}
        </div>
      )}

      {selectedMember && (
        <MemberProfileModal
          member={selectedMember}
          clubId={clubId}
          isOrganizer={isOrganizer}
          isOwner={isOwner}
          onClose={() => setSelectedMember(null)}
          onActionToast={setToast}
        />
      )}

      {toast && <Toast message={toast} onClose={() => setToast(null)} />}
    </>
  );
};
