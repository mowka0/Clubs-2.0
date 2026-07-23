import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ActivityTypeOptions } from '../../components/manage/CreateActivityPicker';

// Состав пунктов шита «+» — сюда переехал бывший тост-гардрейл AppDock:
// создание видят только организаторы, обратная связь доступна всем.
describe('ActivityTypeOptions — состав пунктов шита «+»', () => {
  it('организатору показывает создание и обратную связь', () => {
    render(<ActivityTypeOptions onPick={vi.fn()} onPickFeedback={vi.fn()} canCreate />);

    expect(screen.getByText('Создать активность')).toBeInTheDocument();
    expect(screen.getByText('Событие')).toBeInTheDocument();
    expect(screen.getByText('Сбор')).toBeInTheDocument();
    expect(screen.getByText('Сообщить о проблеме')).toBeInTheDocument();
  });

  it('не-организатору показывает только обратную связь', () => {
    render(<ActivityTypeOptions onPick={vi.fn()} onPickFeedback={vi.fn()} canCreate={false} />);

    expect(screen.getByText('Обратная связь')).toBeInTheDocument();
    expect(screen.getByText('Сообщить о проблеме')).toBeInTheDocument();
    expect(screen.queryByText('Событие')).toBeNull();
    expect(screen.queryByText('Сбор')).toBeNull();
  });

  it('тап по «Сообщить о проблеме» зовёт onPickFeedback, а не onPick', async () => {
    const user = userEvent.setup();
    const onPick = vi.fn();
    const onPickFeedback = vi.fn();
    render(<ActivityTypeOptions onPick={onPick} onPickFeedback={onPickFeedback} canCreate />);

    await user.click(screen.getByText('Сообщить о проблеме'));

    expect(onPickFeedback).toHaveBeenCalledTimes(1);
    expect(onPick).not.toHaveBeenCalled();
  });
});
