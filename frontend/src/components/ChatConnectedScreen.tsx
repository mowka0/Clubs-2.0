import { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { ClubCityPrompt } from './club/ClubCityPrompt';
import type { ClubDetailDto } from '../types/api';

interface ChatConnectedScreenProps {
  club: ClubDetailDto;
}

/**
 * Экран после подключения чата: подтверждение, что всё срослось, и единственный вопрос — город.
 *
 * Показывается на «/», когда человек вернулся из Telegram сам. Вторая дверь в новый клуб —
 * кнопка из закрепа в чате — ведёт сразу на страницу клуба, и там ту же карточку города
 * показывает ClubPage (см. ClubCityPrompt).
 */
export const ChatConnectedScreen: FC<ChatConnectedScreenProps> = ({ club }) => {
  const navigate = useNavigate();
  const openClub = () => navigate(`/clubs/${club.id}`, { replace: true });

  return (
    <div className="rd-page">
      <div className="rd-glass" style={{ padding: 16, marginBottom: 14 }}>
        <div className="rd-chip rd-chip-live" style={{ marginBottom: 10 }}>Чат подключён</div>
        <div className="rd-section-h" style={{ marginTop: 0 }}>{club.name}</div>
        <p className="rd-sub" style={{ margin: '6px 0 0' }}>
          Клуб создан из чата. Участники появятся здесь сами, когда начнут отвечать боту —
          список группы Telegram нам не отдаёт.
        </p>
      </div>

      <ClubCityPrompt club={club} onSaved={openClub} onSkip={openClub} />
    </div>
  );
};
