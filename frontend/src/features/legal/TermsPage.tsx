import { Link } from 'react-router-dom';
import { LEGAL } from '@/lib/legal';
import { LegalLayout } from './LegalLayout';

/** 이용약관(운영정책 포함). 서비스가 실제로 하는 일과 AI 결과물, 공고 정보의 한계를 이용자가 알 수 있게 적는다 */
export function TermsPage() {
  return (
    <LegalLayout title="이용약관">
      <section>
        <h2>1. 목적</h2>
        <p>이 약관은 {LEGAL.operator}(이하 ‘운영팀’)가 제공하는 {LEGAL.service} 서비스를 쓰는 조건과 운영 원칙을 정합니다.</p>
      </section>

      <section>
        <h2>2. 서비스</h2>
        <ul>
          <li>GitHub 기록에서 내가 한 일을 찾아 경험 카드로 정리합니다.</li>
          <li>경험 카드로 채용 공고와 맞춰 보고, 자소서 초안을 씁니다.</li>
          <li>서비스는 카카오테크캠프 프로젝트로 운영되며, 기능은 미리 알리고 바꾸거나 멈출 수 있습니다.</li>
        </ul>
      </section>

      <section>
        <h2>3. 계정</h2>
        <ul>
          <li>GitHub 계정으로 로그인합니다. 본인 계정만 써야 합니다.</li>
          <li>계정과 연결된 GitHub 권한은 GitHub 설정이나 서비스의 GitHub 연결 화면에서 언제든 끊을 수 있습니다.</li>
        </ul>
      </section>

      <section>
        <h2>4. AI가 만든 결과</h2>
        <ul>
          <li>카드 초안, 질문, 공고 맞춤, 자소서 초안은 AI가 만든 참고 자료입니다. 내용이 사실인지는 이용자가 확인한 뒤 써야 합니다.</li>
          <li>근거를 찾지 못한 수치나 표현에는 ‘확인 필요’를 표시합니다. 표시가 없다고 내용이 모두 맞다는 뜻은 아닙니다.</li>
        </ul>
      </section>

      <section>
        <h2>5. 공고 정보</h2>
        <p>공고는 외부에 공개된 채용 공고를 모아 보여 줍니다. 마감일과 조건이 바뀌었을 수 있으니 지원 전에 공고 원문을 확인해야 합니다.</p>
      </section>

      <section>
        <h2>6. 이용자의 콘텐츠</h2>
        <ul>
          <li>경험 카드, 답변, 자소서 등 이용자가 만든 글의 권리는 이용자에게 있습니다.</li>
          <li>운영팀은 이 글을 서비스를 제공하는 데에만 쓰고, 이용자 동의 없이 공개하지 않습니다.</li>
        </ul>
      </section>

      <section>
        <h2>7. 하지 말아야 할 일</h2>
        <ul>
          <li>다른 사람의 계정이나 레포 기록을 자기 것처럼 쓰는 일</li>
          <li>자동화 도구로 짧은 시간에 요청을 몰아 보내 서비스를 방해하는 일</li>
          <li>AI 입력에 서비스나 다른 이용자를 해치려는 지시를 넣는 일</li>
        </ul>
        <p>이런 일이 확인되면 이용을 제한할 수 있습니다.</p>
      </section>

      <section>
        <h2>8. 책임</h2>
        <p>운영팀은 서비스를 안정적으로 운영하려고 노력하지만, 무료로 제공하는 교육 프로젝트이므로 서비스 중단이나 AI 결과물로 생긴 손해에 대해 법이 정한 범위를 넘어 책임지지 않습니다.</p>
      </section>

      <section>
        <h2>9. 개인정보와 문의</h2>
        <p>개인정보는 <Link to="/privacy">개인정보처리방침</Link>에 따라 다룹니다. 문의는 <a href={`mailto:${LEGAL.contact}`}>{LEGAL.contact}</a> 로 보내 주세요.</p>
      </section>

      <section>
        <h2>10. 시행</h2>
        <p>이 약관은 {LEGAL.effective}부터 시행합니다. 약관을 바꾸면 시행 7일 전에 서비스 안에서 알립니다.</p>
      </section>
    </LegalLayout>
  );
}
