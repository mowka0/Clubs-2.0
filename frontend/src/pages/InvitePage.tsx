import { FC, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { List, Section, Cell, Button, Spinner, Placeholder, Text, Badge } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useClubByInviteQuery, useJoinByInviteMutation } from '../queries/clubs';

const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};

export const InvitePage: FC = () => {
  useBackButton(true);
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();

  const clubQuery = useClubByInviteQuery(code);
  const joinMutation = useJoinByInviteMutation();

  const [actionError, setActionError] = useState<string | null>(null);
  const [joined, setJoined] = useState(false);

  const club = clubQuery.data;
  const loading = clubQuery.isPending;
  const loadError = clubQuery.error?.message;
  const joining = joinMutation.isPending;

  const handleJoin = () => {
    if (!code) return;
    haptic.impact('medium');
    setActionError(null);
    joinMutation.mutate(code, {
      onSuccess: () => {
        setJoined(true);
        haptic.notify('success');
      },
      onError: (e) => {
        setActionError(e.message);
        haptic.notify('error');
      },
    });
  };

  if (loading) {
    return <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}><Spinner size="l" /></div>;
  }

  if (loadError && !club) {
    return <Placeholder header="Ссылка недействительна" description="Эта ссылка-приглашение устарела или не существует" />;
  }

  if (!club) return null;

  if (joined) {
    return (
      <List>
        <Placeholder header="Добро пожаловать!" description={`Вы вступили в клуб «${club.name}»`}>
          <Button size="l" onClick={() => navigate(`/clubs/${club.id}`)}>Перейти в клуб</Button>
        </Placeholder>
      </List>
    );
  }

  return (
    <List>
      <Section header="Приглашение в клуб">
        <div style={{ padding: 16, display: 'flex', gap: 16, alignItems: 'center' }}>
          {club.avatarUrl ? (
            <img src={club.avatarUrl} alt="" style={{ width: 64, height: 64, borderRadius: 12, objectFit: 'cover', flexShrink: 0 }} />
          ) : (
            <div style={{ width: 64, height: 64, borderRadius: 12, background: 'var(--tgui--secondary_bg_color)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 32, flexShrink: 0 }}>🏠</div>
          )}
          <div>
            <Text weight="1" style={{ fontSize: 18, display: 'block' }}>{club.name}</Text>
            <Badge type="number" style={{ fontSize: 11, marginTop: 4 }}>{CATEGORY_LABELS[club.category] ?? club.category}</Badge>
          </div>
        </div>
      </Section>

      <Section>
        <Cell subtitle="Город">{club.city}</Cell>
        <Cell subtitle="Участники">{club.memberCount} / {club.memberLimit}</Cell>
        {club.subscriptionPrice > 0 && <Cell subtitle="Подписка">{club.subscriptionPrice} Stars / мес</Cell>}
      </Section>

      {club.description && (
        <Section header="О клубе">
          <div style={{ padding: '12px 16px', lineHeight: 1.5, color: 'var(--tgui--hint_color)' }}>
            {club.description}
          </div>
        </Section>
      )}

      {actionError && (
        <div style={{ padding: '8px 16px', color: 'var(--tgui--destructive_text_color)' }}>{actionError}</div>
      )}

      <Section>
        <Button size="l" onClick={handleJoin} disabled={joining} stretched>
          {joining ? <Spinner size="s" /> : 'Вступить в клуб'}
        </Button>
      </Section>
    </List>
  );
};
