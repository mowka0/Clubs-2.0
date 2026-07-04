import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

import { ImageLightbox } from '../../components/ImageLightbox';
import { PhotoAttach } from '../../components/PhotoAttach';

vi.mock('../../api/clubs', () => ({ uploadImage: vi.fn() }));

describe('ImageLightbox', () => {
  it('renders nothing when src is null', () => {
    const { container } = render(<ImageLightbox src={null} onClose={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the image full-screen and closes on the ✕ button', () => {
    const onClose = vi.fn();
    render(<ImageLightbox src="https://x/receipt.jpg" alt="чек" onClose={onClose} />);
    expect(screen.getByAltText('чек')).toHaveAttribute('src', 'https://x/receipt.jpg');
    fireEvent.click(screen.getByLabelText('Закрыть'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on backdrop tap but NOT when the image itself is tapped', () => {
    const onClose = vi.fn();
    render(<ImageLightbox src="https://x/receipt.jpg" alt="чек" onClose={onClose} />);
    fireEvent.click(screen.getByAltText('чек')); // картинка — остаётся открытым (чтобы можно было скроллить)
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('dialog')); // подложка (backdrop)
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('PhotoAttach', () => {
  it('shows the attach button with the custom label when empty', () => {
    render(<PhotoAttach value={null} onChange={vi.fn()} addLabel="Прикрепить чек" />);
    expect(screen.getByText('📎 Прикрепить чек')).toBeInTheDocument();
  });

  it('shows a preview + replace/remove controls when a photo is set', () => {
    render(<PhotoAttach value="https://x/receipt.jpg" onChange={vi.fn()} />);
    expect(screen.getByRole('img')).toHaveAttribute('src', 'https://x/receipt.jpg');
    expect(screen.getByText('Заменить')).toBeInTheDocument();
    expect(screen.getByText('Убрать')).toBeInTheDocument();
    expect(screen.getByText('Нажмите на фото, чтобы открыть на весь экран')).toBeInTheDocument();
  });

  it('opens the full-screen viewer when the preview is tapped', () => {
    render(<PhotoAttach value="https://x/receipt.jpg" onChange={vi.fn()} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('img'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('clears the photo when «Убрать» is pressed', () => {
    const onChange = vi.fn();
    render(<PhotoAttach value="https://x/receipt.jpg" onChange={onChange} />);
    fireEvent.click(screen.getByText('Убрать'));
    expect(onChange).toHaveBeenCalledWith(null);
  });
});
