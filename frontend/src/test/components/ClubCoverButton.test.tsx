import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

import { ClubCoverButton } from '../../components/club/ClubCoverButton';
import { uploadImage } from '../../api/clubs';
import { useUpdateClubMutation } from '../../queries/clubs';

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../api/clubs', () => ({ uploadImage: vi.fn() }));
vi.mock('../../queries/clubs', () => ({ useUpdateClubMutation: vi.fn() }));

const mockedUpload = vi.mocked(uploadImage);
const mockedMutation = vi.mocked(useUpdateClubMutation);

function mockMutation(mutateAsync = vi.fn().mockResolvedValue(undefined)) {
  mockedMutation.mockReturnValue(
    { mutateAsync, isPending: false } as unknown as ReturnType<typeof useUpdateClubMutation>,
  );
  return mutateAsync;
}

/** См. ClubAvatarButton.test: userEvent.upload не работает со скрытым инпутом и фильтрует по accept. */
function attach(file: File) {
  fireEvent.change(screen.getByTestId('club-cover-input'), { target: { files: [file] } });
}

describe('ClubCoverButton', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockMutation();
  });

  it('лейбл зависит от того, есть ли обложка', () => {
    const { unmount } = render(<ClubCoverButton clubId="c1" hasCover={false} />);
    expect(screen.getByRole('button', { name: /добавить обложку клуба/i })).toBeInTheDocument();
    unmount();

    render(<ClubCoverButton clubId="c1" hasCover />);
    expect(screen.getByRole('button', { name: /заменить обложку клуба/i })).toBeInTheDocument();
  });

  it('сохраняет картинку в coverUrl и не задевает аватар (V70)', async () => {
    const mutateAsync = mockMutation();
    mockedUpload.mockResolvedValue('https://cdn/cover.png');
    render(<ClubCoverButton clubId="c1" hasCover={false} />);

    attach(new File(['x'], 'cover.png', { type: 'image/png' }));

    await waitFor(() => {
      // Ровно одно поле в теле запроса: avatarUrl остаётся нетронутым (null у бэкенда = «не менять»).
      expect(mutateAsync).toHaveBeenCalledWith({
        id: 'c1',
        body: { coverUrl: 'https://cdn/cover.png' },
      });
    });
  });

  it('показывает ошибку валидации и не грузит файл', async () => {
    const mutateAsync = mockMutation();
    render(<ClubCoverButton clubId="c1" hasCover={false} />);

    attach(new File(['x'], 'doc.gif', { type: 'image/gif' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/только jpeg, png или webp/i);
    expect(mockedUpload).not.toHaveBeenCalled();
    expect(mutateAsync).not.toHaveBeenCalled();
  });
});
