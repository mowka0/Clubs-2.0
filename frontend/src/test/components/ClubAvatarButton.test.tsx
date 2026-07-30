import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

import { ClubAvatarButton } from '../../components/club/ClubAvatarButton';
import { uploadImage } from '../../api/clubs';
import { useUpdateClubMutation } from '../../queries/clubs';

// Spinner из telegram-ui требует обёртки <AppRoot> (в приложении она есть на корне) —
// в тестах, как и везде в проекте, подменяем библиотеку минимальными моками.
vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../api/clubs', () => ({ uploadImage: vi.fn() }));
vi.mock('../../queries/clubs', () => ({ useUpdateClubMutation: vi.fn() }));

const mockedUpload = vi.mocked(uploadImage);
const mockedMutation = vi.mocked(useUpdateClubMutation);

/** Мутация обновления клуба: важен только факт вызова с аватаром, кэш TanStack Query здесь не нужен. */
function mockMutation(mutateAsync = vi.fn().mockResolvedValue(undefined)) {
  mockedMutation.mockReturnValue(
    { mutateAsync, isPending: false } as unknown as ReturnType<typeof useUpdateClubMutation>,
  );
  return mutateAsync;
}

function imageFile(name = 'ava.png', type = 'image/png', sizeBytes = 10) {
  const file = new File(['x'], name, { type });
  // Размер File в jsdom задаётся содержимым, а гонять 5 МБ строку ради проверки лимита незачем.
  Object.defineProperty(file, 'size', { value: sizeBytes });
  return file;
}

/**
 * Файл кладём через fireEvent, а не userEvent.upload: инпут скрыт (`display:none`), с невидимыми
 * элементами userEvent не взаимодействует, и он же отфильтровал бы файл по атрибуту `accept` —
 * то есть проверка нашей собственной валидации до инпута вообще не дошла бы.
 */
function attach(file: File) {
  fireEvent.change(screen.getByTestId('club-avatar-input'), { target: { files: [file] } });
}

describe('ClubAvatarButton', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockMutation();
  });

  it('участнику аватар не кликабелен — только картинка', () => {
    render(<ClubAvatarButton clubId="c1" clubName="Партия" avatarUrl={null} editable={false} />);

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    // Без картинки — заглушка из первой буквы названия.
    expect(screen.getByText('П')).toBeInTheDocument();
  });

  it('менеджеру без аватара предлагает добавить, с аватаром — заменить', () => {
    const { unmount } = render(
      <ClubAvatarButton clubId="c1" clubName="Партия" avatarUrl={null} editable />,
    );
    expect(screen.getByRole('button', { name: /добавить аватар клуба/i })).toBeInTheDocument();
    unmount();

    render(<ClubAvatarButton clubId="c1" clubName="Партия" avatarUrl="https://x/a.png" editable />);
    expect(screen.getByRole('button', { name: /заменить аватар клуба/i })).toBeInTheDocument();
  });

  it('загружает файл и сохраняет ссылку в клубе', async () => {
    const mutateAsync = mockMutation();
    mockedUpload.mockResolvedValue('https://cdn/new.png');
    render(<ClubAvatarButton clubId="c1" clubName="Партия" avatarUrl={null} editable />);

    attach(imageFile());

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith({
        id: 'c1',
        body: { avatarUrl: 'https://cdn/new.png' },
      });
    });
  });

  it('не грузит неподходящий тип и показывает причину', async () => {
    const mutateAsync = mockMutation();
    render(<ClubAvatarButton clubId="c1" clubName="Партия" avatarUrl={null} editable />);

    attach(imageFile('doc.gif', 'image/gif'));

    expect(await screen.findByRole('alert')).toHaveTextContent(/только jpeg и png/i);
    expect(mockedUpload).not.toHaveBeenCalled();
    expect(mutateAsync).not.toHaveBeenCalled();
  });

  it('не грузит файл больше 5 МБ', async () => {
    render(<ClubAvatarButton clubId="c1" clubName="Партия" avatarUrl={null} editable />);

    attach(imageFile('big.png', 'image/png', 5 * 1024 * 1024 + 1));

    expect(await screen.findByRole('alert')).toHaveTextContent(/больше 5 МБ/i);
    expect(mockedUpload).not.toHaveBeenCalled();
  });

  it('показывает ошибку, если сохранение упало', async () => {
    mockMutation(vi.fn().mockRejectedValue(new Error('Настройки клуба задаёт владелец')));
    mockedUpload.mockResolvedValue('https://cdn/new.png');
    render(<ClubAvatarButton clubId="c1" clubName="Партия" avatarUrl={null} editable />);

    attach(imageFile());

    expect(await screen.findByRole('alert')).toHaveTextContent(/настройки клуба задаёт владелец/i);
  });
});
