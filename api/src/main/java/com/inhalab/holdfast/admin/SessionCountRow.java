package com.inhalab.holdfast.admin;

/**
 * 회차별 집계 한 칸. {@code (회차, 무엇, 몇 개)} 셋뿐이다.
 *
 * <p>예약 상태·티켓 상태·만료 홀드가 모두 이 모양이라 레코드를 셋 만들지 않는다.
 * {@code key}가 무엇인지는 각 쿼리의 주석이 정한다.
 */
public record SessionCountRow(Long sessionId, String key, Long count) {
}
