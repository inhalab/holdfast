package com.inhalab.holdfast.seat;

/**
 * openapi.yaml {@code NotFound} / {@code ErrorCode.SESSION_NOT_FOUND}.
 * 메시지는 계약 예시의 {@code detail} 문구와 그대로 맞춘다.
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(Long sessionId) {
        super("회차를 찾을 수 없습니다.");
    }
}
