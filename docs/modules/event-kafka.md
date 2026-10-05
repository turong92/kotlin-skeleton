# event-kafka

이벤트를 Kafka 로 발행하는 `KafkaEventPublisher` 와 키 · 메시지 · 직렬화 전략을 준다. 직렬화는 `json` 의 `JsonCodec` 을 쓴다.
켜기 전에는 로그만 남기는 sender 로 동작한다. `json` 이 새로 끌고 오는 웹 스택은 없다: webflux · validation · springdoc 은 `platform` 이 이미 가져온다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:event-kafka"))` |
| 함께 오는 모듈 | `platform`, `json` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.event-kafka` — [docs/config/modules/event-kafka.yml](../config/modules/event-kafka.yml) |
| 기본 동작 | 조건부. 기본은 로그만 남기는 sender 이고, `enabled=true` 와 `KafkaOperations` 빈이 있어야 Kafka 로 보낸다. |
| 부팅에 필요한 것 | 없음. Kafka 로 보내려면 브로커가 필요하다. |
| 교체 지점 | `KafkaEventSender`, `KafkaEventPublisher`, `KafkaEventPartitionKeyStrategy`, `KafkaEventMessageFactory`, `KafkaEventPayloadSerializer` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 |
| 테스트 | `modules/event-kafka/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
