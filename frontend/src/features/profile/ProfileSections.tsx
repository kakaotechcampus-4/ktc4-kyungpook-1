import { PROFILE_LIMITS, type Interest } from '@/api/profile';
import { Field, Input, SectionHead } from '@/components/ui';
import { INTEREST_OPTIONS } from '@/lib/profileForm';
import { INTERESTS_ID, MAIL_ID, type ProfileFormState } from './useProfileFormState';

/** 관심 분야 고르기 — 1개부터 3개까지. 셋을 고르면 나머지 타일은 잠긴다. 오류 문구에 role 을 주지 않는 건 폼의 '입력을 확인해 주세요' 알림과 겹치지 않게 해서다 */
export function InterestPicker({ value, onChange, error }: { value: Interest[]; onChange: (next: Interest[]) => void; error?: string }) {
  const max = PROFILE_LIMITS.INTERESTS;
  const full = value.length >= max;
  const toggle = (interest: Interest, on: boolean) => onChange(on ? [...value, interest] : value.filter((chosen) => chosen !== interest));
  return (
    <section className="settings-section profile-section" aria-label="관심 분야">
      <SectionHead label="관심 분야" count={value.length} right={<span className="t-12 c-3">최대 {max}개</span>} />
      <ul id={INTERESTS_ID} className="interest-grid" aria-describedby={error ? `${INTERESTS_ID}-error` : undefined}>
        {INTEREST_OPTIONS.map(({ value: interest, label }) => {
          const on = value.includes(interest);
          const off = !on && full;
          return (
            <li key={interest}>
              <label className={`interest-tile ${on ? 'is-on' : ''} ${off ? 'is-off' : ''}`}>
                <input type="checkbox" checked={on} disabled={off} onChange={(e) => toggle(interest, e.target.checked)} />
                <span>{label}</span>
              </label>
            </li>
          );
        })}
      </ul>
      {error && <p id={`${INTERESTS_ID}-error`} className="profile-error">{error}</p>}
    </section>
  );
}

/** 메일 알림 — 주소와 수신 체크는 둘 다 선택이다. 체크하면 주소가 필요하다(처음 입력 창과 마이페이지가 같이 쓴다) */
export function MailSection({ state }: { state: ProfileFormState }) {
  const error = state.errors.email;
  return (
    <section className="settings-section profile-section mail-section" aria-label="메일 알림">
      <SectionHead label="메일 알림" />
      <p className="mail-section__why">새로 올라온 공고 중 내 카드와 잘 맞는 곳을 찾으면 이 주소로 알려 드려요. 다른 용도로는 쓰지 않고, 언제든 끌 수 있어요.</p>
      <div className={`profile-cell mail-section__field ${error ? 'profile-cell--invalid' : ''}`}>
        <Field label="메일 주소">
          <Input id={MAIL_ID} type="email" autoComplete="email" spellCheck={false} inputMode="email" aria-invalid={error ? true : undefined} aria-describedby={error ? `${MAIL_ID}-error` : undefined}
            value={state.form.email} onChange={(e) => state.setMail({ email: e.target.value })} />
        </Field>
        {error && <p id={`${MAIL_ID}-error`} className="profile-error mail-section__error" role="alert">{error}</p>}
      </div>
      <label className="mail-section__check">
        <input type="checkbox" checked={state.form.mailOptIn} onChange={(e) => state.setMail({ mailOptIn: e.target.checked })} />
        <span>내 경험에 맞는 공고를 메일로 받을게요</span>
      </label>
    </section>
  );
}
