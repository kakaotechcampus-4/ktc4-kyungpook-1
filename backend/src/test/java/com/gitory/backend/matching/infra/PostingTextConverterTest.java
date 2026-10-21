package com.gitory.backend.matching.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostingTextConverterTest {

    @Test
    @DisplayName("엔티티로 한 번 더 감싼 HTML 을 먼저 풀어 태그를 걷어 낸다")
    void unwrapsEscapedHtmlAndStripsTags() {

        String escaped = "&lt;div&gt;&lt;strong&gt;Java&lt;/strong&gt; 개발 경험&lt;/div&gt;";

        assertThat(PostingTextConverter.fromHtml(escaped)).isEqualTo("Java 개발 경험");

    }

    @Test
    @DisplayName("제목·문단·목록·줄바꿈 경계는 줄바꿈으로 남기고 빈 줄은 두지 않는다")
    void keepsBlockBoundariesAsNewlines() {

        String escaped = "&lt;h3&gt;자격 요건&lt;/h3&gt;&lt;ul&gt;&lt;li&gt;Java&lt;/li&gt;&lt;li&gt;Spring&lt;/li&gt;&lt;/ul&gt;"
                + "&lt;p&gt;우대&lt;br&gt;AWS&lt;/p&gt;&lt;p&gt;&lt;/p&gt;";

        assertThat(PostingTextConverter.fromHtml(escaped)).isEqualTo("자격 요건\nJava\nSpring\n우대\nAWS");

    }

    @Test
    @DisplayName("안쪽에 남은 엔티티와 줄바꿈 없는 공백은 일반 글자와 한 칸 공백으로 바꾼다")
    void decodesInnerEntitiesAndNormalizesSpaces() {

        String escaped = "&lt;p&gt;Java &amp;amp; Spring&amp;nbsp;&amp;nbsp;경험&lt;/p&gt;";

        assertThat(PostingTextConverter.fromHtml(escaped)).isEqualTo("Java & Spring 경험");

    }

    @Test
    @DisplayName("스크립트와 스타일 안의 내용은 글에 넣지 않는다")
    void dropsScriptAndStyleContent() {

        String escaped = "&lt;style&gt;p { color: red }&lt;/style&gt;&lt;p&gt;본문&lt;/p&gt;&lt;script&gt;alert(1)&lt;/script&gt;";

        assertThat(PostingTextConverter.fromHtml(escaped)).isEqualTo("본문");

    }

    @Test
    @DisplayName("본문이 없거나 비어 있으면 빈 글을 돌려준다")
    void returnsEmptyForMissingContent() {

        assertThat(PostingTextConverter.fromHtml(null)).isEmpty();
        assertThat(PostingTextConverter.fromHtml("  ")).isEmpty();

    }
}
