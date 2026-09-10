-- 테스트 전용: persistence-jdbc 의 시각 3종 왕복(JVM 시간대 무관) 검증용 테이블
CREATE TABLE utc_probe (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    happened_at   DATETIME(6) NOT NULL,   -- Instant (UTC)
    birthday      DATE        NULL,       -- LocalDate (변환 없음)
    local_wall    DATETIME(6) NULL        -- LocalDateTime (벽시계 리터럴)
);
