package com.gitory.backend.coverletter.domain;

/** AI 가 잠시 응답하지 못해 첨삭을 받지 못했고, 저장한 것이 없어 다시 요청해도 될 때 발생하는 에러 */
public class CoverLetterAiUnavailableException extends RuntimeException {

    public CoverLetterAiUnavailableException(Throwable cause) {
        super("AI 가 잠시 응답하지 못해 첨삭을 받지 못했습니다.", cause);
    }
}
