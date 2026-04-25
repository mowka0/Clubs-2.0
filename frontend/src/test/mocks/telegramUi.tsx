/**
 * Minimal mock implementations of @telegram-apps/telegram-ui components
 * used in tests. These render simple HTML elements so we can query
 * them with Testing Library.
 *
 * Form components (Input/Textarea/Select) use forwardRef and spread
 * remaining props onto the underlying native element. This is required
 * for compatibility with react-hook-form's `register`, which passes
 * `{ onChange, onBlur, name, ref }` and expects them to land on the
 * native control.
 */
import React from 'react';

export const List: React.FC<React.PropsWithChildren> = ({ children }) => (
  <div data-testid="tg-list">{children}</div>
);

export const Section: React.FC<React.PropsWithChildren<{ header?: string }>> = ({
  children,
  header,
}) => (
  <div data-testid="tg-section">
    {header && <div>{header}</div>}
    {children}
  </div>
);

export const Cell: React.FC<
  React.PropsWithChildren<{
    onClick?: () => void;
    subtitle?: string;
    description?: string;
    after?: React.ReactNode;
    before?: React.ReactNode;
    Component?: string;
  }>
> = ({ children, onClick, subtitle, description, after, before }) => (
  <div data-testid="tg-cell" onClick={onClick} role={onClick ? 'button' : undefined}>
    {before}
    <span>{children}</span>
    {subtitle && <span data-testid="tg-cell-subtitle">{subtitle}</span>}
    {description && <span data-testid="tg-cell-description">{description}</span>}
    {after}
  </div>
);

export const Button: React.FC<
  React.PropsWithChildren<{
    onClick?: (e: React.MouseEvent) => void;
    disabled?: boolean;
    size?: string;
    mode?: string;
    stretched?: boolean;
  }>
> = ({ children, onClick, disabled, ...rest }) => (
  <button onClick={onClick} disabled={disabled} data-size={rest.size} data-mode={rest.mode}>
    {children}
  </button>
);

type InputProps = React.InputHTMLAttributes<HTMLInputElement> & {
  header?: string;
  status?: 'default' | 'error' | 'focused';
};

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ header, status: _status, ...rest }, ref) => (
    <div>
      {header && <label>{header}</label>}
      <input ref={ref} aria-label={typeof header === 'string' ? header : undefined} {...rest} />
    </div>
  ),
);
Input.displayName = 'Input';

type TextareaProps = React.TextareaHTMLAttributes<HTMLTextAreaElement> & {
  header?: string;
  status?: 'default' | 'error' | 'focused';
};

export const Textarea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ header, status: _status, ...rest }, ref) => (
    <div>
      {header && <label>{header}</label>}
      <textarea ref={ref} aria-label={typeof header === 'string' ? header : undefined} {...rest} />
    </div>
  ),
);
Textarea.displayName = 'Textarea';

type SelectProps = React.SelectHTMLAttributes<HTMLSelectElement> & {
  header?: string;
  status?: 'default' | 'error' | 'focused';
};

export const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({ header, status: _status, children, ...rest }, ref) => (
    <div>
      {header && <label>{header}</label>}
      <select ref={ref} aria-label={typeof header === 'string' ? header : undefined} {...rest}>
        {children}
      </select>
    </div>
  ),
);
Select.displayName = 'Select';

export const Spinner: React.FC<{ size?: string }> = ({ size }) => (
  <div data-testid="tg-spinner" data-size={size} role="progressbar">
    Loading...
  </div>
);

export const Modal: React.FC<
  React.PropsWithChildren<{
    open?: boolean;
    onOpenChange?: (open: boolean) => void;
  }>
> = ({ children, open }) => (open ? <div data-testid="tg-modal">{children}</div> : null);

export const Placeholder: React.FC<{
  header?: string;
  description?: string;
}> = ({ header, description }) => (
  <div data-testid="tg-placeholder">
    {header && <div>{header}</div>}
    {description && <div>{description}</div>}
  </div>
);

export const Text: React.FC<
  React.PropsWithChildren<{
    weight?: string;
    style?: React.CSSProperties;
  }>
> = ({ children }) => <span>{children}</span>;

export const Badge: React.FC<
  React.PropsWithChildren<{
    type?: string;
    mode?: string;
    style?: React.CSSProperties;
  }>
> = ({ children }) => <span data-testid="tg-badge">{children}</span>;

export const Avatar: React.FC<{
  size?: number;
  src?: string;
  acronym?: string;
}> = ({ acronym }) => <div data-testid="tg-avatar">{acronym}</div>;

export const Checkbox: React.FC<{
  checked?: boolean;
  onChange?: (e: React.ChangeEvent<HTMLInputElement>) => void;
}> = ({ checked, onChange }) => (
  <input type="checkbox" checked={checked} onChange={onChange} />
);

const TabsListItem: React.FC<
  React.PropsWithChildren<{
    selected?: boolean;
    onClick?: () => void;
  }>
> = ({ children, onClick, selected }) => (
  <button onClick={onClick} data-selected={selected}>
    {children}
  </button>
);

export const TabsList: React.FC<React.PropsWithChildren> & { Item: typeof TabsListItem } = Object.assign(
  ({ children }: React.PropsWithChildren) => <div data-testid="tg-tabs">{children}</div>,
  { Item: TabsListItem }
);
