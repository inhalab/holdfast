package com.inhalab.holdfast.api;

import org.springframework.http.ProblemDetail;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RFC 9457 ProblemDetail을 만드는 단일 지점. api-spec.md 3.4절.
 *
 * <p>{@link ApiExceptionHandler}와 멱등 응답 저장이 <b>같은 함수</b>를 쓴다.
 * 각자 만들면 재생된 오류 응답과 즉석에서 만든 오류 응답의 모양이 달라져,
 * 클라이언트가 두 가지 형태를 처리해야 하고 k6 집계도 갈라진다.
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(ErrorCode code, String detail, List<SeatConflict> conflicts) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        problem.setProperty("serverTime", Instant.now().toString());
        if (!conflicts.isEmpty()) {
            problem.setProperty("conflicts", conflicts.stream()
                    .map(conflict -> Map.<String, Object>of(
                            "seatId", conflict.seatId(),
                            "code", conflict.code().name()))
                    .toList());
        }
        return problem;
    }
}
