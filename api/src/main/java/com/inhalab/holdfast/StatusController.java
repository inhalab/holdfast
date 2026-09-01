package com.inhalab.holdfast;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class StatusController {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    @Value("${holdfast.instance-id:unknown}")
    private String instanceId;

    public StatusController(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    // 로드밸런서가 어느 인스턴스로 보냈는지 확인용.
    // nginx 뒤에서 여러 번 호출하면 instance가 app1/app2로 번갈아 나와야 정상.
    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "holdfast");
        body.put("instance", instanceId);
        body.put("time", Instant.now().toString());
        return body;
    }

    // DB·Redis 연결이 살아 있는지 확인.
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instance", instanceId);
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            body.put("db", "up");
        } catch (Exception e) {
            body.put("db", "down");
        }
        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            body.put("redis", "PONG".equalsIgnoreCase(pong) ? "up" : "down");
        } catch (Exception e) {
            body.put("redis", "down");
        }
        return body;
    }
}
