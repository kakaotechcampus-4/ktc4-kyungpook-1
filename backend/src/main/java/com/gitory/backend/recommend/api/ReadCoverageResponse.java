package com.gitory.backend.recommend.api;

public record ReadCoverageResponse(int commitsRead, int commitsTotal, int prsRead, int prsTotal) {
}
