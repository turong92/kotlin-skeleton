-- modules:payment-jdbc — 결제 장부 (postgresql). 설계: docs/modules/payment-jdbc.md · docs/superpowers/specs/2026-10-08-payment-ledger-design.md §5
-- 다른 브랜치의 미적용 마이그레이션에 기대지 않는다 (docs/schema-management.md)
-- 표 네 개를 한 번에 만든다 — 환불 · 웹훅 표는 뒤 작업이 쓰지만, 잠근 뒤에는 고칠 수 없다.
-- 공통 규칙: 금액은 bigint 통화 최소 단위 + 통화 열 · 시각은 timestamptz · JSON 열 없음 · 부분 인덱스 없음("조건부 유니크"는 NULL 이 겹치지 않는 유니크 열로).
-- 탈퇴(TOMBSTONE)는 그 계정이 산 주문의 줄에서만 계정 id 를 바꾼다. 운영자로서 남의 주문에 남긴 decided_by · requested_by · actor_id 는 남는다 (누가 환불을 결정했는가의 기록).

-- 주문 장부 — 결제 한 번 = 한 줄. 줄의 상태가 돈의 사실이다.
create table if not exists payment_orders (
    id                  varchar(40)  primary key,         -- ord_ + 32자 16진수 (SecureRandom 16바이트). 결제사에 보내는 주문 번호이기도 하다. 순번 · 시각을 담지 않는다
    account_id          varchar(128) not null,            -- 산 계정. FK 없음 (다른 모듈의 표)
    kind                varchar(24)  not null,            -- ONE_TIME | SUBSCRIPTION_INITIAL | SUBSCRIPTION_RENEWAL
    status              varchar(24)  not null,            -- PENDING | CONFIRMING | PAID | PARTIALLY_REFUNDED | REFUNDED | FAILED | EXPIRED | CANCELED
    provider            varchar(40)  not null,            -- 만들 때 서버가 고른 결제사. 바뀌지 않는다
    provider_mode       varchar(8)   not null,            -- TEST | LIVE | UNKNOWN — 만들 때 끼워져 있던 키의 모드
    provider_payment_id varchar(200),                     -- 결제사의 결제 번호 (토스 paymentKey, 최대 200자). 선점 때 박힌다
    currency            char(3)      not null,            -- ISO 4217
    amount              bigint       not null,            -- 청구하려는 금액 (통화 최소 단위)
    paid_amount         bigint,                           -- 결제사가 실제로 받았다고 말한 총액
    tax_amount          bigint,                           -- paid_amount 안의 세금. NULL = 모른다 · 해당 없음
    refunded_amount     bigint       not null default 0,  -- 성공한 환불의 합 — payment_refunds 의 비정규화. 같은 트랜잭션에서만 바뀐다
    item_type           varchar(40)  not null,            -- 앱이 정하는 상품 종류 코드
    item_ref            varchar(128) not null,            -- 앱이 정하는 상품 참조
    quantity            int          not null default 1,
    order_name          varchar(100) not null,            -- 결제창 · 내역에 보이는 이름 (앱 카탈로그가 정한다, 사용자 입력이 아니다)
    origin_type         varchar(24)  not null,            -- CHECKOUT | SUBSCRIPTION — 이 주문을 만든 것
    origin_ref          varchar(64),                      -- 만든 것의 id (구독 id). CHECKOUT 은 NULL
    dedupe_key          varchar(100),                     -- 같은 주문을 두 번 만들지 않는 키 (NULL 은 서로 겹치지 않는다) — 유니크는 아래 uq_payment_orders_dedupe
    method              varchar(40),                      -- 결제 수단 종류 코드 (CARD · EASY_PAY …)
    card_issuer         varchar(40),                      -- 카드사 코드 (결제사 중립 대문자 영문). 카드가 아니면 NULL. 지워지는 개인정보 — 계정이 지워지면 NULL
    card_last4          varchar(4),                       -- 가려서 받은 카드 번호의 마지막 네 글자 — 숫자 또는 `*` 만. 지워지는 개인정보 — 계정이 지워지면 NULL
    failure_code        varchar(64),                      -- 결제사의 거절 코드 또는 우리 코드. 문장은 저장하지 않는다
    review_reason       varchar(64),                      -- NULL 이 아니면 사람이 봐야 한다 (돈이 어긋난 이유: AMOUNT_MISMATCH · ORDER_MISMATCH · REFUND_MISMATCH · MODE_MISMATCH · CONFIRM_UNRESOLVED / 지급 쪽 이유: FULFILMENT_UNRESOLVED · REFUND_APPLY_UNRESOLVED — 앞쪽이 뒤쪽을 덮어쓴다)
    checkout_expires_at timestamptz  not null,                    -- 결제창이 열려 있는 마지막 시각. 2단계의 구독 갱신 주문(결제창 없음)은 만든 시각을 채운다
    confirm_started_at  timestamptz,
    paid_at             timestamptz,
    fulfilled_at        timestamptz,                      -- 앱의 onPaid 가 끝난 시각. NULL 이면 아직
    closed_at           timestamptz,                      -- FAILED · EXPIRED · CANCELED 가 된 시각
    account_erased_at   timestamptz,                      -- 이 계정이 지워진 시각 — 탈퇴자 기록의 구분 · 보존 기간의 기준
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    constraint uq_payment_orders_provider_payment unique (provider, provider_payment_id),   -- 한 결제가 두 주문에 붙지 못한다. NULL 은 서로 겹치지 않는다
    constraint uq_payment_orders_dedupe unique (dedupe_key),
    constraint ck_payment_orders_amounts check (amount >= 0 and refunded_amount >= 0 and refunded_amount <= coalesce(paid_amount, amount))
);
create index if not exists idx_payment_orders_account on payment_orders (account_id, created_at, id);          -- 내 결제 내역 (거꾸로 훑는다)
create index if not exists idx_payment_orders_status  on payment_orders (status, created_at, id);              -- 운영자 목록
create index if not exists idx_payment_orders_origin  on payment_orders (origin_type, origin_ref, created_at); -- 한 구독의 결제 이력
create index if not exists idx_payment_orders_review  on payment_orders (review_reason, created_at, id);       -- 확인 필요 목록 (커서 = created_at, id)
create index if not exists idx_payment_orders_created on payment_orders (created_at, id);                      -- 운영자 기본 목록 (필터 없음, 최신순 커서)
create index if not exists idx_payment_orders_unfulfilled on payment_orders (fulfilled_at, status, created_at, id);   -- 확인 필요: 돈은 받았는데 지급이 안 끝난 줄 (fulfilled_at is null and status = PAID — 소수. 열 순서: status 로 시작하면 옵티마이저가 상태별 목록 질의에도 이 인덱스를 골라 정렬을 따로 한다)

-- 환불 한 건 = 한 줄
create table if not exists payment_refunds (
    id                 varchar(40)  primary key,          -- rfd_ + 32자 16진수. 결제사에 보내는 멱등 키이기도 하다
    order_id           varchar(40)  not null references payment_orders (id),
    status             varchar(16)  not null,             -- REQUESTED | PENDING | SUCCEEDED | FAILED | REJECTED | WITHDRAWN
    origin             varchar(16)  not null,             -- USER | OPERATOR | PROVIDER (결제사 쪽에서 일어난 취소를 맞춰 넣은 줄)
    amount             bigint       not null,
    currency           char(3)      not null,
    reason_code        varchar(40)  not null,             -- CUSTOMER_REQUEST | DUPLICATE | NOT_DELIVERED | OPERATOR | PROVIDER_CONSOLE. 자유 문장은 받지 않는다
    open_key           varchar(40),                       -- 열려 있는 동안(REQUESTED · PENDING) = order_id, 닫히면 NULL → 주문당 열린 환불은 하나 (유니크는 아래 uq_payment_refunds_open)
    dedupe_key         varchar(100),                      -- sha256(호출자 + "\n" + Idempotency-Key) — 같은 요청이 늦게 다시 와도 한 줄 (유니크는 아래 uq_payment_refunds_dedupe)
    provider_refund_id varchar(200),                      -- 결제사의 취소 거래 번호 (토스 cancels[].transactionKey)
    expected_remaining bigint,                            -- 보내기 직전에 계산한 "성공하면 결제사에 남을 금액"
    requested_by       varchar(128),                      -- 요청한 계정 (본인 또는 운영자; `TOMBSTONE` 이면 그 계정이 산 주문의 줄에서만 바뀐다). PROVIDER 는 NULL
    decided_by         varchar(128),                      -- 승인 · 거절한 운영자 (운영자로서 남긴 값은 탈퇴로 바뀌지 않는다)
    failure_code       varchar(64),
    requested_at       timestamptz  not null,
    sent_at            timestamptz,
    completed_at       timestamptz,
    applied_at         timestamptz,                       -- 앱의 onRefunded 가 끝난 시각
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    constraint uq_payment_refunds_provider unique (order_id, provider_refund_id),   -- 결제사의 취소 하나를 두 번 맞춰 넣지 못한다
    constraint uq_payment_refunds_open unique (open_key),
    constraint uq_payment_refunds_dedupe unique (dedupe_key),
    constraint ck_payment_refunds_amount check (amount > 0)
);
create index if not exists idx_payment_refunds_order  on payment_refunds (order_id, created_at);
create index if not exists idx_payment_refunds_status on payment_refunds (status, requested_at, id);   -- 운영자의 환불 요청 목록 (커서)
create index if not exists idx_payment_refunds_unapplied on payment_refunds (applied_at, status, requested_at, id);   -- 확인 필요: 환불은 됐는데 회수가 안 끝난 줄 (applied_at is null and status = SUCCEEDED — 소수. 열 순서는 위와 같은 이유)

-- 이력 — 추가만 한다. "이 주문이 왜 이 상태인가"를 로그 없이 답한다. 자유 문장 열이 없다.
create table if not exists payment_events (
    id                bigint generated always as identity primary key,
    order_id          varchar(40)  not null,
    refund_id         varchar(40),
    type              varchar(40)  not null,              -- ORDER_CREATED · CONFIRM_STARTED · … · REVIEW_FLAGGED
    from_status       varchar(24),
    to_status         varchar(24),
    actor             varchar(16)  not null,              -- USER | OPERATOR | SYSTEM | PROVIDER
    actor_id          varchar(128),                       -- 계정 id (`TOMBSTONE` 이면 그 계정이 산 주문의 이력에서만 바뀐다)
    provider_code     varchar(64),                        -- 결제사의 오류 · 상태 코드
    provider_trace_id varchar(128),                       -- 결제사에 문의할 때 대는 추적 번호
    trace_id          varchar(64),                        -- 우리 traceId — 서버 로그와 잇는다
    created_at        timestamptz  not null
);
create index if not exists idx_payment_events_order on payment_events (order_id, id);

-- 웹훅 수신함 — 받은 사실과 처리 결과. 자유 문장 열이 없다.
create table if not exists payment_webhook_events (
    id            bigint generated always as identity primary key,
    provider      varchar(40)  not null,
    event_key     varchar(200) not null,                  -- 결제사의 전달 id. 없으면 본문의 sha256. 상한 표식 줄은 limited:<주문 id>:<창 번호>
    event_type    varchar(64)  not null,
    resource_type varchar(24)  not null,                  -- PAYMENT | BILLING_METHOD | SUBSCRIPTION | LIMIT_MARKER (주문 단위 창 상한에 걸렸다는 우리 표식 줄)
    resource_id   varchar(200) not null,
    order_id      varchar(40),
    payload       text,                                   -- 기본 NULL — 서명된 본문이 곧 사실인 결제사만 필요한 만큼 남긴다
    payload_hash  char(64)     not null,
    status        varchar(16)  not null,                  -- RECEIVED | PROCESSED | IGNORED | FAILED | DEAD
    deliveries    int          not null default 0,        -- 같은 event_key 가 도착한 횟수
    last_error    varchar(64),                            -- 우리 코드 (MODE_MISMATCH · LOOKUP_UNAVAILABLE …). 예외 메시지를 넣지 않는다
    received_at   timestamptz  not null,
    processed_at  timestamptz,
    constraint uq_payment_webhook_events unique (provider, event_key)
);
create index if not exists idx_payment_webhook_events_status   on payment_webhook_events (status, received_at, id);
create index if not exists idx_payment_webhook_events_resource on payment_webhook_events (provider, resource_type, resource_id);
create index if not exists idx_payment_webhook_events_order    on payment_webhook_events (order_id, received_at);   -- 주문 단위 창 상한을 센다 (주문 줄 락 안에서)
