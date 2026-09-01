package com.inhalab.holdfast.api;

import java.util.List;

/**
 * API 계약에 정의된 오류·거절. {@link ApiExceptionHandler}가 RFC 9457
 * ProblemDetail로 변환한다.
 *
 * <p><b>이 예외를 던지는 것이 오류 응답을 만드는 유일한 경로다.</b> 컨트롤러가
 * 각자 {@code ResponseEntity}로 오류를 조립하면 같은 상황이 엔드포인트마다 다른
 * 모양으로 나가고, k6가 {@code code}로 태깅해 집계하는 것이 깨진다
 * (api-spec.md 3.2절, concurrency-spec.md 7.1절).
 *
 * <p>{@link RuntimeException}인 이유는 트랜잭션 롤백이다. 홀드는 전부 아니면
 * 전무이므로(api-spec.md 4절) 일부 좌석을 이미 쓴 뒤 거절이 나면 되돌려야 한다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient List<SeatConflict> conflicts;

    public ApiException(ErrorCode code) {
        this(code, code.defaultDetail(), List.of());
    }

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of());
    }

    public ApiException(ErrorCode code, List<SeatConflict> conflicts) {
        this(code, code.defaultDetail(), conflicts);
    }

    public ApiException(ErrorCode code, String detail, List<SeatConflict> conflicts) {
        super(detail);
        this.code = code;
        this.conflicts = List.copyOf(conflicts);
    }

    public ErrorCode getCode() {
        return code;
    }

    /** 좌석별 사유. 없으면 빈 리스트이며 응답에서 생략된다. */
    public List<SeatConflict> getConflicts() {
        return conflicts;
    }
}
