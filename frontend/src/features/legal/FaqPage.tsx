import { FAQ } from '@/lib/faq';
import { LEGAL } from '@/lib/legal';
import { LegalLayout } from './LegalLayout';

export function FaqPage() {
  return (
    <LegalLayout title="자주 묻는 질문" meta={false}>
      <div className="faq">
        {FAQ.map(({ q, a }) => (
          <details key={q} className="faq__item">
            <summary>{q}</summary>
            <p>{a}</p>
          </details>
        ))}
      </div>
      <p>찾는 답이 없으면 문의해 주세요. <a href={`mailto:${LEGAL.contact}`}>{LEGAL.contact}</a></p>
    </LegalLayout>
  );
}
