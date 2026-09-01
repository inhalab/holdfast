package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.api.ErrorCode;
import com.inhalab.holdfast.api.ProblemDetails;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code Idempotency-Key} 처리. concurrency-spec.md 6절, api-spec.md 6절.
 *
 * <table>
 *   <caption>동작</caption>
 *   <tr><td>같은 키 + 같은 본문</td><td>저장된 응답을 그대로 재생. 상태는 다시 바뀌지 않는다</td></tr>
 *   <tr><td>같은 키 + 다른 본문</td><td>409 {@code IDEMPOTENCY_KEY_REUSED}</td></tr>
 *   <tr><td>같은 키가 처리 중</td><td>409 {@code IDEMPOTENCY_KEY_IN_PROGRESS}</td></tr>
 * </table>
 *
 * <p>요청 해시에 <b>HTTP 메서드와 경로를 포함한다.</b> 그래야 같은 키를 다른
 * 엔드포인트에 재사용한 경우도 걸린다(api-spec.md 6절).
 *
 * <p><b>정상 거절도 결과로 저장한다.</b> 첫 요청이 409 {@code SEAT_HELD_BY_OTHER}로
 * 거절됐다면 같은 키의 재요청도 같은 409를 돌려준다 — 멱등키는 "이 요청의 결과가
 * 무엇이었는가"를 고정하는 장치이고, 다른 결과를 원하면 새 키를 쓰는 것이 계약이다
 * (api-spec.md 6.1절). 반면 <b>서버 결함(5xx)은 저장하지 않고 선점을 푼다</b> —
 * 결과가 확정되지 않았으므로 같은 키로 다시 시도할 수 있어야 한다.
 */
@Service
public class IdempotencyService {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * 멱등 보장 아래 작업을 실행한다.
     *
     * @param key         {@code Idempotency-Key} 헤더 값
     * @param method      HTTP 메서드 — 해시에 포함된다
     * @param path        요청 경로 — 해시에 포함된다
     * @param requestBody 요청 본문. 없으면 {@code null}
     * @param operation   실제 작업. 성공 응답을 돌려준다
     */
    public ResponseEntity<?> execute(String key,
                                     String method,
                                     String path,
                                     Object requestBody,
                                     Supplier<ResponseEntity<?>> operation) {
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 헤더가 필요합니다.");
        }

        String requestHash = hash(method + " " + path + " " + serialize(requestBody));

        if (!store.claim(key, requestHash)) {
            return replayOrReject(key, requestHash);
        }

        try {
            ResponseEntity<?> response = operation.get();
            store.storeResponse(key, response.getStatusCode().value(), serialize(response.getBody()));
            return response;
        } catch (ApiException ex) {
            // 확정된 결과다. 같은 키의 재요청이 같은 답을 받도록 저장한다.
            ProblemDetail problem = ProblemDetails.of(ex.getCode(), ex.getMessage(), ex.getConflicts());
            store.storeResponse(key, ex.getCode().status().value(), serialize(problem));
            throw ex;
        } catch (RuntimeException ex) {
            // 결과가 확정되지 않았다. 선점을 풀어 재시도할 수 있게 둔다.
            store.release(key);
            throw ex;
        }
    }

    private ResponseEntity<?> replayOrReject(String key, String requestHash) {
        Optional<IdempotencyRecord> found = store.find(key);
        if (found.isEmpty()) {
            // 선점 직후 다른 요청이 결함으로 선점을 푼 드문 경우. 처리 중으로 본다.
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
        }

        IdempotencyRecord record = found.get();
        if (!requestHash.equals(record.getRequestHash())) {
            // 키 재사용 버그를 조용히 통과시키지 않는다(api-spec.md 6절).
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (record.getResponseStatus() == null) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
        }

        int status = record.getResponseStatus();
        MediaType contentType = status >= 400
                ? MediaType.APPLICATION_PROBLEM_JSON
                : MediaType.APPLICATION_JSON;
        return ResponseEntity.status(status)
                .contentType(contentType)
                .body(record.getResponseBody());
    }

    private String serialize(Object value) {
        if (value == null) {
            return "";
        }
        return objectMapper.writeValueAsString(value);
    }

    private String hash(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
