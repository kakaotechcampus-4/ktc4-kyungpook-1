package com.gitory.backend.mail.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 두 메일의 제목과 본문을 만든다 — 메일 프로그램이 읽는 모양만 쓰려고 인라인 스타일의 단순한 HTML 로 둔다
 * 회사 이름·공고 제목은 사용자가 아닌 바깥 글이라 HTML 글자를 바꿔 넣는다
 */
final class MailContent {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String PROFILE_PATH = "/settings/profile";
    private static final String BUTTON = "display:inline-block;background:#202022;color:#FFFFFF;text-decoration:none;"
            + "font-weight:700;font-size:16px;padding:13px 22px;border-radius:8px";
    private static final String SMALL_BUTTON = "display:inline-block;background:#202022;color:#FFFFFF;"
            + "text-decoration:none;font-weight:700;font-size:15px;padding:10px 18px;border-radius:8px";

    private MailContent() {
    }

    static MailMessage optInConfirmation(String appBaseUrl) {

        String base = withoutTrailingSlash(appBaseUrl);
        String body = "<h3 style=\"font-size:22px;line-height:1.4;margin:0 0 10px\">메일 알림을 켰어요</h3>"
                + "<p style=\"margin:0 0 18px;color:#323237\">새로 올라온 공고 중 내 카드와 잘 맞는 곳을 찾으면 이 주소로 알려 드려요. "
                + "맞는 공고가 있는 날에만 보내요.</p>"
                + "<a href=\"" + escape(base) + "\" style=\"" + BUTTON + "\">Gitory 열기</a>"
                + footer(base, "margin-top:24px");
        return new MailMessage("[Gitory] 이제 이 주소로 맞는 공고를 알려 드려요", page(body));

    }

    static MailMessage matchedPostings(String appBaseUrl, List<MailPosting> postings) {

        String base = withoutTrailingSlash(appBaseUrl);
        String title = "새 공고 중 잘 맞는 곳을 " + postings.size() + "곳 찾았어요";
        StringBuilder body = new StringBuilder()
                .append("<h3 style=\"font-size:22px;line-height:1.4;margin:0 0 10px\">").append(title).append("</h3>")
                .append("<p style=\"margin:0 0 18px;color:#323237\">확정한 카드와 견줘 보고 필수 조건을 절반 넘게 채운 공고만 골랐어요.</p>");
        for (MailPosting posting : postings) {
            body.append("<div style=\"border-top:1px solid #E5E5EA;padding:18px 0\">")
                    .append("<p style=\"font-size:18px;font-weight:700;margin:0\">").append(escape(posting.companyName())).append("</p>")
                    .append("<p style=\"margin:2px 0 8px;color:#323237\">").append(escape(posting.title())).append("</p>")
                    .append("<p style=\"font-size:15px;color:#62626B;margin:0 0 12px\">필수 조건 ").append(posting.requiredTotal())
                    .append("개 중 ").append(posting.requiredMet()).append("개를 채웠어요 · ").append(deadline(posting.expiresAt()))
                    .append("</p>")
                    .append("<a href=\"").append(escape(base + "/match/" + posting.postingId())).append("\" style=\"")
                    .append(SMALL_BUTTON).append("\">공고 보기</a></div>");
        }
        body.append(footer(base, "margin-top:6px"));
        return new MailMessage("[Gitory] " + title, page(body.toString()));

    }

    private static String deadline(Instant expiresAt) {

        if (expiresAt == null) {
            return "마감일 없음";
        }
        ZonedDateTime seoul = expiresAt.atZone(SEOUL);
        return seoul.getMonthValue() + "월 " + seoul.getDayOfMonth() + "일 마감";

    }

    private static String footer(String base, String margin) {

        return "<div style=\"border-top:1px solid #E5E5EA;" + margin + ";padding-top:16px;font-size:14px;color:#6B6B75\">"
                + "받고 싶지 않으면 인적사항에서 메일 알림을 꺼 주세요. "
                + "<a href=\"" + escape(base + PROFILE_PATH) + "\" style=\"color:#2F5F96\">메일 알림 끄기</a></div>";

    }

    private static String page(String body) {

        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"></head>"
                + "<body style=\"margin:0;padding:0;background:#F3F4F6;font-family:Pretendard,-apple-system,'Segoe UI','Malgun Gothic',sans-serif;"
                + "font-size:16px;line-height:1.6;color:#202022\">"
                + "<div style=\"max-width:560px;margin:0 auto;padding:18px\">"
                + "<div style=\"background:#FFFFFF;border:1px solid #E5E5EA;border-radius:10px;padding:28px 24px\">"
                + "<p style=\"font-size:15px;font-weight:700;color:#62626B;margin:0 0 18px\">Gitory</p>"
                + body + "</div></div></body></html>";

    }

    private static String withoutTrailingSlash(String url) {

        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

    }

    private static String escape(String text) {

        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");

    }
}
