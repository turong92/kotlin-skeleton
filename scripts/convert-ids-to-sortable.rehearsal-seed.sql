-- 리허설용 시드: convert-ids-to-sortable.sql 을 시험하려고 **옛 형식 id** 로 계정 · 결제 · 구독 · 환불 · 노트 · 게시글 · 세션 · 알림 · 약관 동의를 채운다.
-- 빈 시험 DB(별도 컨테이너)에 마이그레이션을 다 적용한 뒤 한 번 넣는다. 실제 DB 에는 넣지 않는다.
--   docker exec -i <시험 컨테이너> psql -U app -d app -v ON_ERROR_STOP=1 < scripts/convert-ids-to-sortable.rehearsal-seed.sql
-- 일부러 넣은 것: 같은 ms 에 만든 행 · 시드 계정(acc_user — 고정 이름이라 바뀌지 않는다) · 가리키는 행이 없는 참조 · 문장 안에 박힌 id · 해시가 든 dedupe_key · 보존 칸(저장소 키 · 웹훅 본문).
begin;

create function pg_temp.h32() returns text language sql volatile as $$ select replace(gen_random_uuid()::text, '-', '') $$;
create temp table s (k text primary key, v text);

-- 계정 : 시드 3 + 무작위 6 (a5 · a6 는 같은 ms)
insert into s values ('t0', '2026-10-01 09:00:00.100+00');
do $$
declare
    i int;
    acc text;
    t timestamptz;
    seeds text[] := array['acc_user', 'acc_admin', 'acc_moderator'];
begin
    for i in 1..9 loop
        acc := case when i <= 3 then seeds[i] else 'acc_' || pg_temp.h32() end;
        t := '2026-10-01 09:00:00+00'::timestamptz + (case when i = 9 then 8 else i end) * interval '1 hour' + (case when i = 9 then interval '0.0004 second' else interval '0' end);
        insert into accounts (id, email, email_verified, status, display_name, display_name_key, display_tag, created_at, updated_at, login_id)
        values (acc, 'u' || i || '@example.com', true, 'ACTIVE', 'User' || i, 'user' || i, '0000', t, t, 'login-' || i);
        insert into s values ('acc' || i, acc);
        insert into account_roles values (acc, 'USER');
        insert into account_identities (id, account_id, method, subject, verified, secret, created_at)
        values ('idn_' || pg_temp.h32(), acc, 'password', 'u' || i || '@example.com', true, '{bcrypt}x', t + interval '1 second');
        insert into account_audit (at, type, account_id, ip, detail) values (t, 'SIGN_UP', acc, '203.0.113.7', 'account ' || acc || ' created');
    end loop;
end $$;
insert into accounts (id, email, status, created_at, updated_at, erased_at) select 'acc_' || pg_temp.h32(), null, 'ERASED', '2026-10-02 00:00:00+00', '2026-10-02 00:00:00+00', '2026-10-03 00:00:00+00';
insert into account_blocks (kind, hash, created_at, created_by, account_id) select 'email', pg_temp.h32() || pg_temp.h32(), now(), v, (select v from s where k = 'acc4') from s where k = 'acc1';
insert into account_tokens (token_hash, purpose, subject, account_id, payload, created_at, expires_at) select pg_temp.h32() || pg_temp.h32(), 'password_reset', v, v, 'for ' || v, now(), now() + interval '1 hour' from s where k = 'acc5';

-- 세션 + 리프레시 토큰(FK) + 챌린지(세션 id 를 가리킴)
do $$
declare acc text; ses text; i int;
begin
    for i in 4..7 loop
        select v into acc from s where k = 'acc' || i;
        ses := 'ses_' || pg_temp.h32();
        insert into auth_sessions (id, account_id, created_at, last_used_at, expires_at) values (ses, acc, '2026-10-05 10:00:00+00'::timestamptz + i * interval '1 minute', now(), now() + interval '30 days');
        insert into auth_refresh_tokens (token_hash, session_id, created_at) values (pg_temp.h32() || pg_temp.h32(), ses, now());
        if i = 5 then
            insert into account_challenges (id, purpose, subject, account_id, session_id, code_hash, attempts_left, created_at, expires_at, last_sent_at)
            values (pg_temp.h32(), 'reauth', acc, acc, ses, pg_temp.h32() || pg_temp.h32(), 5, now(), now() + interval '10 minutes', now());
        end if;
    end loop;
end $$;

-- 작성자 프로필 + 게시글 · 댓글 · 반응 · 첨부
insert into author_profiles (id, account_id, primary_of, name, name_key, tag, status, created_at, updated_at)
select 'prf_' || pg_temp.h32(), v, v, 'Pen' || k, 'pen' || k, '0001', 'ACTIVE', '2026-10-06 08:00:00+00', '2026-10-06 08:00:00+00' from s where k in ('acc4', 'acc5');
insert into author_profiles (id, account_id, name, name_key, tag, status, created_at, updated_at)
select 'prf_' || pg_temp.h32(), v, 'Second', 'second', '0002', 'ACTIVE', '2026-10-06 08:00:00.0004+00', '2026-10-06 08:00:00+00' from s where k = 'acc4';
insert into boards (code, name, created_at) values ('general', 'General', now());
do $$
declare p1 text; p2 bigint; c1 bigint; accv text;
begin
    select id into p1 from author_profiles order by created_at limit 1;
    select v into accv from s where k = 'acc4';
    insert into board_posts (board_code, author_id, author_ref, title, body, status, created_at, updated_at) values ('general', p1, accv, 'hello', 'b', 'PUBLISHED', now(), now()) returning id into p2;
    insert into board_posts (board_code, author_id, title, body, status, created_at, updated_at) values ('general', 'pending:p1', 'old', 'b', 'PUBLISHED', now(), now());
    insert into board_post_attachments values (p2, 0, 'uploads/' || accv || '/aaaa/cat.png');
    insert into board_comments (post_id, depth, author_id, body, status, created_at, updated_at) values (p2, 0, p1, 'c', 'PUBLISHED', now(), now()) returning id into c1;
    insert into board_reactions values ('POST', p2, accv, 'LIKE', now());
end $$;

-- 결제 : 일회성 주문 3(PAID 2 · 환불 1) · 구독 1(주문 2) · 환불 3 · 이력 · 웹훅 · 응원
do $$
declare
    acc text; acc2 text;
    o1 text := 'ord_' || pg_temp.h32(); o2 text := 'ord_' || pg_temp.h32(); o3 text := 'ord_' || pg_temp.h32();
    o4 text := 'ord_' || pg_temp.h32(); o5 text := 'ord_' || pg_temp.h32();
    sub text := 'sub_' || pg_temp.h32();
    r1 text := 'rfd_' || pg_temp.h32(); r2 text := 'rfd_' || pg_temp.h32(); r3 text := 'rfd_' || pg_temp.h32();
    t timestamptz := '2026-10-08 10:00:00+00';
begin
    select v into acc from s where k = 'acc5'; select v into acc2 from s where k = 'acc6';
    insert into payment_orders (id, account_id, kind, status, provider, provider_mode, provider_payment_id, currency, amount, paid_amount, refunded_amount, item_type, item_ref, order_name, origin_type, dedupe_key, checkout_expires_at, paid_at, created_at, updated_at)
    values
      (o1, acc, 'ONE_TIME', 'PAID', 'toss', 'TEST', 'pay_1', 'KRW', 3000, 3000, 0, 'cheer', 'c', 'n', 'CHECKOUT', encode(sha256(convert_to(acc || E'\n' || 'key-1', 'UTF8')), 'hex'), t, t, t, t),
      (o2, acc, 'ONE_TIME', 'PARTIALLY_REFUNDED', 'toss', 'TEST', 'pay_2', 'KRW', 5000, 5000, 1000, 'cheer', 'c', 'n', 'CHECKOUT', encode(sha256(convert_to(acc || E'\n' || 'key-2', 'UTF8')), 'hex'), t, t, t, t),
      (o3, acc2, 'ONE_TIME', 'PENDING', 'toss', 'TEST', null, 'KRW', 1000, null, 0, 'cheer', 'c', 'n', 'CHECKOUT', encode(sha256(convert_to(acc2 || E'\n' || 'key-3', 'UTF8')), 'hex'), t, null, t, t);
    insert into payment_subscriptions (id, account_id, provider, provider_mode, status, plan_code, currency, amount, period, anchor_at, period_seq, charge_order_id, attempt, customer_key, active_key, created_at, updated_at, country)
    values (sub, acc2, 'toss', 'TEST', 'ACTIVE', 'basic', 'KRW', 3900, 'P1M', t, 2, o5, 1, 'cus_' || pg_temp.h32(), acc2, t, t, 'KR');
    insert into payment_orders (id, account_id, kind, status, provider, provider_mode, provider_payment_id, currency, amount, paid_amount, refunded_amount, item_type, item_ref, order_name, origin_type, origin_ref, dedupe_key, checkout_expires_at, paid_at, created_at, updated_at)
    values
      (o4, acc2, 'SUBSCRIPTION_INITIAL', 'PAID', 'toss', 'TEST', 'pay_4', 'KRW', 3900, 3900, 3900, 'subscription', 'basic', 'n', 'SUBSCRIPTION', sub, 'sub:' || sub || ':' || (extract(epoch from t) * 1000)::bigint || ':1:1', t, t, t, t),
      (o5, acc2, 'SUBSCRIPTION_RENEWAL', 'PAID', 'toss', 'TEST', 'pay_5', 'KRW', 3900, 3900, 0, 'subscription', 'basic', 'n', 'SUBSCRIPTION', sub, 'sub:' || sub || ':' || (extract(epoch from t) * 1000)::bigint || ':2:1', t + interval '30 days', t + interval '30 days', t + interval '30 days', t + interval '30 days');
    insert into payment_refunds (id, order_id, status, origin, amount, currency, reason_code, open_key, dedupe_key, requested_by, requested_at, created_at, updated_at)
    values
      (r1, o2, 'SUCCEEDED', 'USER', 1000, 'KRW', 'CUSTOMER_REQUEST', null, encode(sha256(convert_to(acc || E'\n' || 'refund-key', 'UTF8')), 'hex'), acc, t, t, t),
      (r2, o3, 'REQUESTED', 'USER', 1000, 'KRW', 'CUSTOMER_REQUEST', o3, null, acc2, t, t, t),
      (r3, o4, 'SUCCEEDED', 'USER', 3900, 'KRW', 'SUBSCRIPTION_CANCEL', null, encode(sha256(convert_to(acc2 || E'\n' || 'subcancel:' || sub || ':' || o4, 'UTF8')), 'hex'), acc2, t, t, t);
    insert into payment_events (order_id, refund_id, type, actor, actor_id, created_at) values (o2, r1, 'REFUND_SUCCEEDED', 'USER', acc, t), (o1, null, 'ORDER_CREATED', 'USER', acc, t);
    insert into payment_webhook_events (provider, event_key, event_type, resource_type, resource_id, order_id, payload, payload_hash, status, received_at)
    values ('toss', 'evt-1', 'PAYMENT_STATUS_CHANGED', 'PAYMENT', 'pay_1', o1, '{"orderId":"' || o1 || '"}', repeat('a', 64), 'PROCESSED', t),
           ('toss', 'limited:' || o1 || ':3', 'LIMIT', 'LIMIT_MARKER', o1, o1, null, repeat('b', 64), 'IGNORED', t);
    insert into cheers (order_id, account_id, amount, currency, status, created_at) values (o1, acc, 3000, 'KRW', 'PAID', t);
    insert into legal_consents (subject_type, subject_id, document_type, version, content_sha256, locale, action, source, reference_id, seq, created_at)
    values ('account', acc, 'terms', 'v1', repeat('c', 64), 'ko', 'AGREED', 'checkout', o1, 1, t), ('account', 'deleted:abc', 'terms', 'v1', repeat('c', 64), 'ko', 'AGREED', 'sign-up', '', 1, t);
    insert into alerts (kind, alert_key, severity, title, first_at, occurred_at, sent_at) values ('PAYMENT_REVIEW', o2, 'WARN', 'order ' || o2 || ' needs a look', t, t, t);
    insert into jobs (job_type, payload_json, status, max_attempts, next_run_at, created_at, updated_at, log_context)
    values ('payment-refund-apply', '{"targetId":"' || r1 || '"}', 'DONE', 5, t, t, t, 'order=' || o2),
           ('payment-order-reconcile', '{"targetId":"' || o3 || '","note":"dangling ' || 'ord_' || pg_temp.h32() || '"}', 'PENDING', 5, t, t, t, null);
end $$;

-- 노트(uuid) + 알림 받은편지함(UUID v4 이벤트 id · 문장 안의 id)
do $$
declare n1 uuid := gen_random_uuid(); n2 uuid := gen_random_uuid(); acc text; ev1 text := gen_random_uuid()::text; ev2 text := gen_random_uuid()::text;
begin
    select v into acc from s where k = 'acc5';
    insert into notes (id, owner_id, title, status, attachment_key, created_at, updated_at) values (n1, acc, 'first', 'ACTIVE', 'uploads/' || acc || '/bbbb/a.png', '2026-10-09 09:00:00+00', '2026-10-09 09:00:00+00');
    insert into notes (id, owner_id, title, status, created_at, updated_at) values (n2, acc, 'second', 'DRAFT', '2026-10-09 09:00:00.0004+00', '2026-10-09 09:00:00+00');
    insert into notification_inbox (recipient_id, event_id, topic, type, severity, payload_json, recipient_ids_json, event_created_at, created_at, updated_at)
    values (acc, ev1, 'notes', 'note.created', 'INFO', '{"noteId":"' || n1 || '"}', '["' || acc || '"]', '2026-10-09 09:00:01+00', now(), now()),
           (acc, ev2, 'notes', 'note.created', 'INFO', '{"noteId":"' || n2 || '"}', '["' || acc || '"]', '2026-10-09 09:00:02+00', now(), now()),
           ('acc_user', ev2, 'notes', 'note.created', 'INFO', '{"noteId":"' || n2 || '"}', '["acc_user","' || acc || '"]', '2026-10-09 09:00:02+00', now(), now()),
           (acc, 'note-export-7', 'notes', 'note.exported', 'INFO', '{}', '[]', '2026-10-09 09:05:00+00', now(), now());
    insert into jobs (job_type, payload_json, status, max_attempts, next_run_at, created_at, updated_at) values ('note-export', '{"noteId":"' || n1 || '","ownerId":"' || acc || '"}', 'DONE', 3, now(), now(), now());
end $$;

-- 시드 계정(변환 제외)의 구독 해지 환불: 구독 · 주문 · 환불 id 는 바뀌고 계정 id 는 그대로 — dedupe_key 를 새 id 로 다시 계산해야 한다
do $$
declare sub text := 'sub_' || pg_temp.h32(); o text := 'ord_' || pg_temp.h32(); t timestamptz := '2026-10-08 12:00:00+00';
begin
    insert into payment_subscriptions (id, account_id, provider, provider_mode, status, plan_code, currency, amount, period, charge_order_id, attempt, customer_key, active_key, end_reason, created_at, updated_at)
    values (sub, 'acc_user', 'toss', 'TEST', 'ENDED', 'basic', 'KRW', 3900, 'P1M', o, 1, 'cus_' || pg_temp.h32(), null, 'USER_CANCELED', t, t);
    insert into payment_orders (id, account_id, kind, status, provider, provider_mode, provider_payment_id, currency, amount, paid_amount, refunded_amount, item_type, item_ref, order_name, origin_type, origin_ref, dedupe_key, checkout_expires_at, paid_at, created_at, updated_at)
    values (o, 'acc_user', 'SUBSCRIPTION_INITIAL', 'REFUNDED', 'toss', 'TEST', 'pay_seed', 'KRW', 3900, 3900, 3900, 'subscription', 'basic', 'n', 'SUBSCRIPTION', sub, 'sub:' || sub || ':0:1:1', t, t, t, t);
    insert into payment_refunds (id, order_id, status, origin, amount, currency, reason_code, dedupe_key, requested_by, requested_at, created_at, updated_at)
    values ('rfd_' || pg_temp.h32(), o, 'SUCCEEDED', 'USER', 3900, 'KRW', 'SUBSCRIPTION_CANCEL', encode(sha256(convert_to('acc_user' || E'\n' || 'subcancel:' || sub || ':' || o, 'UTF8')), 'hex'), 'acc_user', t, t, t);
end $$;

commit;
