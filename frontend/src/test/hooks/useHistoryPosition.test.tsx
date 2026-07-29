import { describe, it, expect, beforeEach } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { useHistoryPosition } from '../../hooks/useHistoryPosition';

/**
 * Снимок читаем по клику, а не в рендере: значения обновляются в useEffect,
 * то есть уже ПОСЛЕ рендера, вызванного сменой роута. В обработчике они актуальны.
 */
const Probe = () => {
  const { canGoBack, canGoForward } = useHistoryPosition();
  const navigate = useNavigate();
  const [snapshot, setSnapshot] = useState('—');

  return (
    <>
      <div data-testid="snapshot">{snapshot}</div>
      <button type="button" onClick={() => setSnapshot(`назад:${canGoBack()} вперёд:${canGoForward()}`)}>
        снять
      </button>
      <button type="button" onClick={() => navigate('/next')}>push</button>
      <button type="button" onClick={() => navigate(-1)}>pop</button>
    </>
  );
};

/**
 * Индекс записи в истории — единственный источник правды для хука.
 * happy-dom не хранит state у `replaceState` (всегда отдаёт null), поэтому
 * подменяем сам геттер: в браузере это значение ведёт react-router.
 */
function setHistoryIndex(index: number) {
  Object.defineProperty(window.history, 'state', {
    configurable: true,
    get: () => ({ idx: index }),
  });
}

function renderProbe() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<Probe />} />
        <Route path="/next" element={<Probe />} />
      </Routes>
    </MemoryRouter>,
  );
}

const snapshot = () => screen.getByTestId('snapshot').textContent;

beforeEach(() => setHistoryIndex(0));

describe('useHistoryPosition', () => {
  it('на первой записи истории идти некуда — ни назад, ни вперёд', async () => {
    const user = userEvent.setup();
    renderProbe();

    await user.click(screen.getByRole('button', { name: 'снять' }));

    expect(snapshot()).toBe('назад:false вперёд:false');
  });

  it('после перехода вглубь можно вернуться назад, но не вперёд', async () => {
    const user = userEvent.setup();
    renderProbe();

    setHistoryIndex(1);
    await user.click(screen.getByRole('button', { name: 'push' }));
    await user.click(screen.getByRole('button', { name: 'снять' }));

    expect(snapshot()).toBe('назад:true вперёд:false');
  });

  it('после возврата назад открывается путь вперёд', async () => {
    const user = userEvent.setup();
    renderProbe();

    setHistoryIndex(1);
    await user.click(screen.getByRole('button', { name: 'push' }));
    setHistoryIndex(0);
    await user.click(screen.getByRole('button', { name: 'pop' }));
    await user.click(screen.getByRole('button', { name: 'снять' }));

    expect(snapshot()).toBe('назад:false вперёд:true');
  });

  it('новый переход обрезает ветку «вперёд»', async () => {
    const user = userEvent.setup();
    renderProbe();

    setHistoryIndex(1);
    await user.click(screen.getByRole('button', { name: 'push' }));
    setHistoryIndex(0);
    await user.click(screen.getByRole('button', { name: 'pop' }));
    setHistoryIndex(1);
    await user.click(screen.getByRole('button', { name: 'push' }));
    await user.click(screen.getByRole('button', { name: 'снять' }));

    expect(snapshot()).toBe('назад:true вперёд:false');
  });
});
