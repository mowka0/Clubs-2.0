import { FC, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { List, Section, Cell, Button, Spinner, Placeholder, Text, Badge } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { getClubByInvite } from '../api/clubs';
import { joinByInviteCode } from '../api/membership';
import type { ClubDetailDto } from '../types/api';

const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};

export const InvitePage: FC = () => {
  useBackButton(true);
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();

  const [club, setClub] = useState<ClubDetailDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [joining, setJoining] = useState(false);
  const [joined, setJoined] = useState(false);

  useEffect(() => {
    if (!code) return;
    getClubByInvite(code)
      .then((data) => { setClub(data); setLoading(false); })
      .catch((e) => { setError((e as Error).message); setLoading(false); });
  }, [code]);

  const handleJoin = async () => {
    if (!code) return;
    setJoining(true);
    setError(null);
    try {
      await joinByInviteCode(code);
      setJoined(true);
    } catch (e) {
      setError((e as Error).message);
      setJoining(false);
    }
  };

  if (loading) {
    return <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}><Spinner size="l" /></div>;
  }

  if (error && !club) {
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

      {error && (
        <div style={{ padding: '8px 16px', color: 'var(--tgui--destructive_text_color)' }}>{error}</div>
      )}

      <Section>
        <Button size="l" onClick={handleJoin} disabled={joining} stretched>
          {joining ? <Spinner size="s" /> : 'Вступить в клуб'}
        </Button>
      </Section>
    </List>
  );
};
