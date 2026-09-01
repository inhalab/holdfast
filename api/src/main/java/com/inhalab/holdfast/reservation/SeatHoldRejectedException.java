package com.inhalab.holdfast.reservation;

import java.util.List;

/**
 * 홀드가 거절됐다. api-spec.md 3.1절 기준으로 <b>정상 거절</b>이며 오류가 아니다
 * — 좌석이 이미 팔려 거절된 것은 시스템이 제 역할을 한 결과다(7.1).
 *
 * <p>{@link RuntimeException}인 이유는 트랜잭션 롤백 때문이다. 홀드는 전부
 * 아니면 전무이므로(api-spec.md 4절), 일부 좌석을 이미 쓴 뒤 거절이 나면 그
 * 쓰기를 되돌려야 한다. 전략은 결과만 보고하고, 롤백 판단은
 * {@link SeatHoldService}가 이 예외를 던져서 한다.
 */
public class SeatHoldRejectedException extends RuntimeException {

    private final String code;
    private final transient List<SeatConflict> conflicts;

    public SeatHoldRejectedException(String code, List<SeatConflict> conflicts) {
        super("홀드가 거절되었습니다. code=" + code);
        this.code = code;
        this.conflicts = List.copyOf(conflicts);
    }

    /** openapi.yaml {@code ErrorCode} 값. */
    public String getCode() {
        return code;
    }

    /** 좌석별 사유. api-spec.md 4.2절의 {@code conflicts} 배열이 된다. */
    public List<SeatConflict> getConflicts() {
        return conflicts;
    }
}
