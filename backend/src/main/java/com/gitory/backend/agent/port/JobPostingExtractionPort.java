package com.gitory.backend.agent.port;

/** 공고 본문에서 원문 인용이 있는 조건을 뽑는다, 실패하면 PostingExtractionUnavailableException 을 던진다 */
public interface JobPostingExtractionPort {

    JobPostingExtractionResult extract(JobPostingExtractionRequest request);
}
