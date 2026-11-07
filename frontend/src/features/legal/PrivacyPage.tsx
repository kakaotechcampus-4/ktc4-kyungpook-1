import { COLLECTED, COVER_LETTER_COLLECTED, LEGAL, PROFILE_COLLECTED } from '@/lib/legal';
import { LegalLayout } from './LegalLayout';

/**
 * 개인정보처리방침. 적는 내용은 코드와 운영 문서로 확인한 사실만이다 —
 * 모으는 항목(lib/legal.ts, 마이페이지와 같은 목록), GitHub 읽기 범위(LandingPage READS·SKIPS), AI 로 보내기 전 가림(docs/GUARDRAILS.md),
 * 서버 위치(docs/DEPLOYMENT.md). 기능이 바뀌면 이 문서와 시행일을 같이 바꾼다.
 */
export function PrivacyPage() {
  const rows = [...COLLECTED, ...PROFILE_COLLECTED, ...COVER_LETTER_COLLECTED];
  return (
    <LegalLayout title="개인정보처리방침">
      <p>{LEGAL.operator}(이하 ‘운영팀’)는 {LEGAL.service} 서비스를 쓰는 분의 개인정보를 아래와 같이 다룹니다.</p>

      <section>
        <h2>1. 모으는 정보와 쓰는 곳</h2>
        <div className="legal__table">
          <table>
            <thead><tr><th>정보</th><th>쓰는 곳</th><th>보관</th></tr></thead>
            <tbody>{rows.map(([name, why, keep]) => <tr key={name}><td>{name}</td><td>{why}</td><td>{keep}</td></tr>)}</tbody>
          </table>
        </div>
        <p>인적사항은 직접 입력한 경우에만 모읍니다. 비공개 레포와 다른 사람만 한 일은 읽지 않습니다.</p>
      </section>

      <section>
        <h2>2. 모으는 방법</h2>
        <ul>
          <li>GitHub 로그인: GitHub 아이디, 프로필 사진, 레포를 읽기 위한 액세스 토큰을 받습니다. 쓰기 권한은 요청하지 않습니다.</li>
          <li>레포 정리: 고른 공개 레포에서 내가 올린 커밋, 내가 만든 PR, 내가 남긴 리뷰 코멘트를 읽습니다.</li>
          <li>직접 입력: 경험 카드, 질문 답변, 활동, 인적사항, 자소서 문항과 답은 이용자가 입력한 것을 저장합니다.</li>
        </ul>
      </section>

      <section>
        <h2>3. 보관 기간과 파기</h2>
        <ul>
          <li>모은 정보는 탈퇴할 때까지 보관하고, 탈퇴하면 지체 없이 파기합니다. GitHub 액세스 토큰은 탈퇴 즉시 파기합니다.</li>
          <li>화면에서 탈퇴하는 기능은 준비 중입니다. 그전까지는 아래 연락처로 삭제를 요청하면 지체 없이 처리하고 결과를 메일로 알립니다.</li>
        </ul>
      </section>

      <section>
        <h2>4. 다른 곳에 보내거나 맡기는 정보</h2>
        <ul>
          <li>AI 처리: 카드 초안, 질문, 공고 매칭, 자소서 초안을 만들 때 커밋 메시지, PR 본문, 카드 문장, 답변, 자소서 문항을 외부 AI 모델 API 로 보냅니다. 보내기 전에 이메일 주소와 비밀값(토큰, 키)은 가립니다.</li>
          <li>서버: 서비스와 데이터는 Amazon Web Services 서울 리전의 서버에 둡니다.</li>
          <li>로그인: GitHub 계정으로 로그인합니다. GitHub 에 따로 보내는 개인정보는 없습니다.</li>
          <li>이 밖에 개인정보를 다른 사람이나 회사에 팔거나 넘기지 않습니다.</li>
        </ul>
      </section>

      <section>
        <h2>5. 이용자의 권리</h2>
        <ul>
          <li>마이페이지에서 모은 정보 목록을 보고, 인적사항을 고치거나 지울 수 있습니다.</li>
          <li>카드와 자소서는 각 화면에서 고치거나 지울 수 있습니다.</li>
          <li>열람, 정정, 삭제, 처리 정지를 아래 연락처로 요청할 수 있습니다.</li>
        </ul>
      </section>

      <section>
        <h2>6. 쿠키와 브라우저 저장소</h2>
        <p>로그인을 유지하는 세션 쿠키를 씁니다. 화면 테마 같은 설정은 이 브라우저에만 저장합니다. 광고나 추적용 쿠키는 쓰지 않습니다. 서비스를 고치려고 어떤 기능을 썼는지 세는 사용 지표를 모으며, 여기에는 카드 id 와 개수만 담고 글 내용은 담지 않습니다.</p>
      </section>

      <section>
        <h2>7. 개인정보 보호 담당과 문의</h2>
        <p>{LEGAL.operator}<br />메일 <a href={`mailto:${LEGAL.contact}`}>{LEGAL.contact}</a></p>
      </section>

      <section>
        <h2>8. 바뀐 내용</h2>
        <p>이 방침을 바꾸면 시행 7일 전에 서비스 안에서 알립니다. 지금 방침은 {LEGAL.effective}부터 시행합니다.</p>
      </section>
    </LegalLayout>
  );
}
