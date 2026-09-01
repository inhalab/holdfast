package com.inhalab.holdfast.seat;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.api.ErrorCode;
import com.inhalab.holdfast.reservation.SeatInventoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 좌석맵·좌석 상태 스냅샷 조회. openapi.yaml {@code getSeatMap}·
 * {@code getSeatStatusSnapshot}의 구현이다.
 *
 * <p>여기 있는 모든 메서드는 조회 전용이며 락을 잡지 않는다. 잔여 수량을
 * 별도로 세지 않는다 — {@code SeatMapSeat}·{@code SeatStatusEntry}는 좌석별
 * {@code status}만 담고, "몇 석 남았는지"는 그 목록에서 클라이언트가
 * {@code AVAILABLE}을 세면 된다. 별도 카운터 컬럼이나 집계 쿼리를 두면
 * CS-5(회차 잔여 수량 경합)를 새로 만드는 것이다(concurrency-spec.md 1절).
 */
@Service
public class SeatQueryService {

    private final EventSessionRepository eventSessionRepository;
    private final SeatInventoryRepository seatInventoryRepository;

    /**
     * 선점 유지 시간(초). 부하 측정 시에는 짧게 바꾸므로(concurrency-spec.md 3절)
     * 클라이언트가 값을 하드코딩하지 않도록 응답 필드로 노출한다.
     */
    @Value("${holdfast.hold-ttl-seconds:300}")
    private int holdTtlSeconds;

    public SeatQueryService(EventSessionRepository eventSessionRepository,
                             SeatInventoryRepository seatInventoryRepository) {
        this.eventSessionRepository = eventSessionRepository;
        this.seatInventoryRepository = seatInventoryRepository;
    }

    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(Long sessionId) {
        EventSession session = eventSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));

        List<SeatMapRow> rows = seatInventoryRepository.findSeatMapRows(sessionId);
        List<SeatMapZoneResponse> zones = groupByZone(rows);

        return new SeatMapResponse(
                session.getId(),
                session.getStatus(),
                session.getReserveOpensAt(),
                session.getStartsAt(),
                session.getEndsAt(),
                session.getMaxPerUser(),
                holdTtlSeconds,
                Instant.now(),
                zones
        );
    }

    /** 정렬은 리포지토리 쿼리(zone.sortOrder → row → col)가 이미 끝냈다. 여기서는 묶기만 한다. */
    private List<SeatMapZoneResponse> groupByZone(List<SeatMapRow> rows) {
        Map<Long, String> zoneNames = new LinkedHashMap<>();
        Map<Long, Integer> zoneSortOrders = new LinkedHashMap<>();
        Map<Long, List<SeatMapSeatResponse>> zoneSeats = new LinkedHashMap<>();

        for (SeatMapRow row : rows) {
            zoneNames.putIfAbsent(row.zoneId(), row.zoneName());
            zoneSortOrders.putIfAbsent(row.zoneId(), row.sortOrder());
            zoneSeats.computeIfAbsent(row.zoneId(), key -> new ArrayList<>())
                    .add(new SeatMapSeatResponse(row.seatId(), row.seatNo(), row.rowIndex(), row.colIndex(), row.status()));
        }

        List<SeatMapZoneResponse> zones = new ArrayList<>();
        for (Long zoneId : zoneNames.keySet()) {
            zones.add(new SeatMapZoneResponse(zoneId, zoneNames.get(zoneId), zoneSortOrders.get(zoneId), zoneSeats.get(zoneId)));
        }
        return zones;
    }

    @Transactional(readOnly = true)
    public SeatStatusSnapshotResult getSeatStatusSnapshot(Long sessionId) {
        if (!eventSessionRepository.existsById(sessionId)) {
            throw new ApiException(ErrorCode.SESSION_NOT_FOUND);
        }

        List<SeatStatusRow> rows = seatInventoryRepository.findStatusRows(sessionId);
        List<SeatStatusEntryResponse> entries = rows.stream()
                .map(row -> new SeatStatusEntryResponse(row.seatId(), row.status()))
                .toList();

        String etag = computeEtag(sessionId, rows);
        SeatStatusSnapshotResponse body = new SeatStatusSnapshotResponse(sessionId, Instant.now(), entries);
        return new SeatStatusSnapshotResult(body, etag);
    }

    /**
     * 상태 스냅샷의 해시. {@code seatId}·{@code status} 쌍만으로 만든다 —
     * {@code serverTime}처럼 매 호출마다 바뀌는 값을 넣으면 상태가 그대로여도
     * ETag가 항상 달라져 304의 의미가 없어진다.
     *
     * <p>리포지토리 쿼리가 {@code seatId} 오름차순으로 이미 정렬해 주므로 여기서
     * 다시 정렬하지 않는다 — 순서가 흔들리면 같은 상태에서도 다른 해시가 나온다.
     */
    private String computeEtag(Long sessionId, List<SeatStatusRow> rows) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("session=").append(sessionId);
        for (SeatStatusRow row : rows) {
            canonical.append(';').append(row.seatId()).append(':').append(row.status());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 표준으로 지원한다(JLS 플랫폼 필수 알고리즘) — 실질적으로 발생하지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }
}
