import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Check } from '@/components/ui';

afterEach(cleanup);

describe('Check', () => {
  it('keeps its accessible name and boolean state while hiding the SVG selection mark', () => {
    const { rerender } = render(<Check checked label="후보 선택" />);
    const check = screen.getByRole('checkbox', { name: '후보 선택' });
    expect(check).toHaveAttribute('aria-checked', 'true');
    expect(check).toHaveAttribute('type', 'button');
    expect(check.tagName).toBe('BUTTON');
    const icon = check.querySelector('svg');
    expect(icon).not.toBeNull();
    expect(icon).toHaveAttribute('aria-hidden', 'true');
    expect(check.textContent).toBe('');

    rerender(<Check checked={false} label="후보 선택" />);
    expect(check).toHaveAttribute('aria-checked', 'false');
    expect(check.querySelector('svg')).toBeNull();
  });

  it('requests one state change without activating the containing row', () => {
    const onChange = vi.fn();
    const onRowClick = vi.fn();
    const { rerender } = render(
      <div onClick={onRowClick}><Check checked={false} onChange={onChange} label="후보 선택" /></div>,
    );

    fireEvent.click(screen.getByRole('checkbox', { name: '후보 선택' }));
    expect(onChange).toHaveBeenCalledExactlyOnceWith(true);
    expect(onRowClick).not.toHaveBeenCalled();

    rerender(<div onClick={onRowClick}><Check checked onChange={onChange} label="후보 선택" /></div>);
    fireEvent.click(screen.getByRole('checkbox', { name: '후보 선택' }));
    expect(onChange).toHaveBeenCalledTimes(2);
    expect(onChange).toHaveBeenLastCalledWith(false);
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it('uses native disabled behavior to prevent activation and focus', () => {
    const onChange = vi.fn();
    const onRowClick = vi.fn();
    render(<div onClick={onRowClick}><Check checked disabled onChange={onChange} label="후보 선택" /></div>);
    const check = screen.getByRole('checkbox', { name: '후보 선택' });

    expect(check).toBeDisabled();
    check.click();
    check.focus();
    expect(check).not.toHaveFocus();
    expect(onChange).not.toHaveBeenCalled();
    expect(onRowClick).not.toHaveBeenCalled();
    expect(check).toHaveAttribute('aria-checked', 'true');
  });
});
