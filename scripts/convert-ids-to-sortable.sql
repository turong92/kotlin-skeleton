-- 일회용: 옛 행 id(접두사 + 32자 16진수 · UUID v4)를 시간순 id(접두사 + ULID 26자 · UUIDv7)로 바꾼다. PostgreSQL 전용 — MySQL 은 로컬에 데이터가 없어 만들지 않았다.
-- 직접 실행하지 말고 scripts/convert-ids-to-sortable.sh 로 (백업 · 연결 · 검사 출력을 해 준다). 사용법은 그 파일 머리말.
--
-- 한 트랜잭션이다: 어디서든 실패하면(검사 포함) 아무것도 바뀌지 않는다. -v dry=1 이면 끝에서 되돌린다(리허설).
--
-- 하는 일
--   1. 대상 id 의 새 값을 정한다. 시간 부분 = 그 행의 created_at(ms) → 옛 순서 = 새 id 순서(같은 ms 안에서는 난수부를 1씩 올려 지킨다. 어긋나면 중단).
--   2. 이 id 를 가리키는 모든 칸을 같이 바꾼다. 칸 목록은 information_schema 에서 뽑는다: public 의 모든 문자열 칸(varchar · text · char)을 훑어
--      「접두사_16진수 32자」 / UUID 꼴이면서 1 의 표에 있는 값만 바꾼다 — 칸 이름에 기대지 않으므로 account_id · subject_id · recipient_id · author_id · origin_ref ·
--      charge_order_id · active_key · order_id · refund_id · session_id · actor_id · requested_by … 가 빠짐없이 걸리고, 문장 안에 박힌 id(jobs.payload_json ·
--      notification_inbox.payload_json / recipient_ids_json · account_audit.detail · account_challenges.payload · alerts.alert_key / detail · 주문 dedupe_key 의
--      `sub:<구독 id>:…` · `subchg:<구독 id>:…` · webhook limited:<주문 id>:… )도 같이 바뀐다. uuid 형 칸(notes.id)은 따로.
--   3. 구독 dedupe_key 를 옛 id 뒤 32자(옛 요청 해시의 앞)로 채운다(V20261012153000 이 만든 열 — 먼저 적용돼 있어야 한다). 유니크 칸을 채우는 것일 뿐 멱등을 이어 주지는 않는다: 새 코드는 64자 키를 쓰고 계정 id 도 바뀌어 옛 요청 키의 재시도는 새 구독을 만든다. 구독 해지 환불의 dedupe_key 는 새 id 로 다시 계산한다.
--   4. 검사: 표마다 행 수 같음 · 옛 형식 id 0건 · 외래 키 다시 검증 · (1) 의 순서 보존.
--
-- 바꾸지 않는 것(일부러)
--   · 저장소 키 칸 attachment_key · board_post_attachments.storage_key — S3 의 객체는 그대로 있다. 키에 든 옛 계정 id 는 그대로 둔다(ACCOUNT_ID 소유 확인은 새 계정 id 와 안 맞는다 — 첨부를 다시 올려야 한다).
--   · payment_webhook_events.payload — 서명된 본문이고 payload_hash 와 짝이다.
--   · 모양이 옛 형식이 아닌 id(시드 계정 acc_user · acc_admin … 같은 고정 이름, TOMBSTONE, deleted:<해시>).
--   · 해시가 된 값: 주문 · 환불 요청의 dedupe_key (sha256(계정 id + 요청 키)) — 요청 키를 몰라 다시 계산할 수 없다. 변환 이전에 보낸 같은 Idempotency-Key 의 재시도는 중복으로 알아보지 못한다.
--   · cus_ (결제사에 묶이는 고객 키).
--
-- 다시 실행해도 안전하다: 대상은 옛 형식(접두사_16진수 32 · UUID v4)뿐이라 이미 바뀐 값(ULID · UUIDv7)은 건드리지 않는다.
--
-- 알아 둘 것: 주문 id 는 결제사에 orderId 로 간다. 이미 결제된 주문은 결제사 쪽에 옛 orderId 로 남아 있어, 변환 뒤 그 주문을 결제사에 조회하면(환불 전 확인 · 맞추기)
-- ORDER_MISMATCH 로 걸린다. 테스트 모드 데이터 / 더는 결제사와 오갈 일이 없는 주문일 때만 쓴다.

\set ON_ERROR_STOP on
\if :{?dry}
\else
\set dry 0
\endif

begin;

-- 0. 먼저 있어야 하는 것 ------------------------------------------------------------------------------------------------------------------------
do $$
begin
    if to_regclass('public.payment_subscriptions') is not null and not exists (
        select 1 from information_schema.columns where table_schema = 'public' and table_name = 'payment_subscriptions' and column_name = 'dedupe_key') then
        raise exception 'payment_subscriptions.dedupe_key 가 없다 — 새 빌드를 한 번 띄워 Flyway 가 V20261012153000 을 적용한 뒤(앱을 내리고) 다시 실행한다';
    end if;
end $$;

-- 1. 함수 · 대상 -------------------------------------------------------------------------------------------------------------------------------
create function pg_temp.ulid(ms bigint, r numeric) returns text language plpgsql immutable as $$
declare
    v numeric := ms::numeric * 1208925819614629174706176::numeric + r;   -- 2^80
    alphabet constant text := '0123456789abcdefghjkmnpqrstvwxyz';
    out text := '';
begin
    for i in 1..26 loop
        out := substr(alphabet, mod(v, 32)::int + 1, 1) || out;
        v := div(v, 32);
    end loop;
    return out;
end $$;

-- 암호학적 난수(gen_random_uuid) 78비트
create function pg_temp.rand78() returns numeric language sql volatile as $$
    select (('x' || substr(h, 1, 10))::bit(40)::bigint)::numeric * 274877906944::numeric
         + (('x' || substr(h, 11, 10))::bit(40)::bigint % 274877906944)::numeric
    from (select replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '') as h) s
$$;

create function pg_temp.uuidv7(ms bigint, r numeric) returns uuid language sql immutable as $$
    select (lpad(to_hex(ms), 12, '0') || '7'
            || lpad(to_hex(div(r, 4611686018427387904::numeric)::bigint), 3, '0')
            || lpad(to_hex(mod(r, 4611686018427387904::numeric)::bigint | (-9223372036854775808)::bigint), 16, '0'))::uuid
$$;

create temp table id_map (old text primary key, new text not null, kind text not null);
create temp table report (step text, target text, n bigint);
create temp table counts_before (tbl text primary key, n bigint not null);

insert into counts_before
select table_name::text, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from public.%I', table_name), false, true, '')))[1]::text::bigint
from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE';

-- 2. 새 id 정하기 (표 · 접두사 · 시각 칸) --------------------------------------------------------------------------------------------------------
do $$
declare
    t record;
    src text;
    bad bigint;
    n bigint;
begin
    for t in
        select * from (values
            ('accounts',             'id',       'acc_', 'created_at',       '^acc_[0-9a-f]{32}$', 'ulid'),
            ('account_identities',   'id',       'idn_', 'created_at',       '^idn_[0-9a-f]{32}$', 'ulid'),
            ('auth_sessions',        'id',       'ses_', 'created_at',       '^ses_[0-9a-f]{32}$', 'ulid'),
            ('author_profiles',      'id',       'prf_', 'created_at',       '^prf_[0-9a-f]{32}$', 'ulid'),
            ('payment_orders',       'id',       'ord_', 'created_at',       '^ord_[0-9a-f]{32}$', 'ulid'),
            ('payment_refunds',      'id',       'rfd_', 'created_at',       '^rfd_[0-9a-f]{32}$', 'ulid'),
            ('payment_subscriptions','id',       'sub_', 'created_at',       '^sub_[0-9a-f]{32}$', 'ulid'),
            ('notes',                'id',       '',     'created_at',       '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$', 'uuidv7')
        ) v(tbl, col, prefix, ts, pat, fmt)
    loop
        if to_regclass('public.' || t.tbl) is null then continue; end if;
        src := format('select %I::text as id, %I as ts from public.%I where %I::text ~ %L', t.col, t.ts, t.tbl, t.col, t.pat);
        execute format($q$
            insert into id_map(old, new, kind)
            select id, %L || case when %L = 'uuidv7' then pg_temp.uuidv7(ms, mod(base, 9444732965739290427392::numeric) + rn - 1)::text
                                  else pg_temp.ulid(ms, base + rn - 1) end, %L
            from (select id, ms, first_value(b) over w as base, row_number() over w as rn
                  from (select id, ts, floor(extract(epoch from ts) * 1000)::bigint as ms, pg_temp.rand78() as b from (%s) q) s
                  window w as (partition by ms order by ts, id)) x
        $q$, t.prefix, t.fmt, t.tbl, src);
        get diagnostics n = row_count;
        insert into report values ('새 id', t.tbl || '.' || t.col, n);
        -- 옛 순서 = 새 id 순서
        execute format($q$
            select count(*) from (select m.new, lag(m.new) over (order by s.ts, s.id) as prev from (%s) s join id_map m on m.old = s.id) z
            where prev is not null and new <= prev
        $q$, src) into bad;
        if bad > 0 then raise exception '% 의 새 id 순서가 옛 순서와 다르다(% 건)', t.tbl, bad; end if;
    end loop;

    -- 알림 이벤트 id (UUID v4 → evt_) : 같은 이벤트는 받는 사람마다 한 줄씩이라 이벤트 단위로 한 번
    if to_regclass('public.notification_inbox') is not null then
        src := $s$select event_id as id, min(event_created_at) as ts from public.notification_inbox
                  where event_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' group by event_id$s$;
        execute format($q$
            insert into id_map(old, new, kind)
            select id, 'evt_' || pg_temp.ulid(ms, base + rn - 1), 'evt'
            from (select id, ms, first_value(b) over w as base, row_number() over w as rn
                  from (select id, ts, floor(extract(epoch from ts) * 1000)::bigint as ms, pg_temp.rand78() as b from (%s) q) s
                  window w as (partition by ms order by ts, id)) x
        $q$, src);
        get diagnostics n = row_count;
        insert into report values ('새 id', 'notification_inbox.event_id', n);
        execute format($q$
            select count(*) from (select m.new, lag(m.new) over (order by s.ts, s.id) as prev from (%s) s join id_map m on m.old = s.id) z
            where prev is not null and new <= prev
        $q$, src) into bad;
        if bad > 0 then raise exception 'notification_inbox 의 새 이벤트 id 순서가 옛 순서와 다르다(% 건)', bad; end if;
    end if;
end $$;

-- 3. 구독 dedupe_key (옛 id 가 곧 옛 요청 해시의 앞 32자) · 구독 해지 환불의 dedupe_key (옛 id 로 계산된 해시 → 새 id 로 다시) ------------------------------
do $$
declare n bigint;
begin
    if to_regclass('public.payment_subscriptions') is not null then
        update public.payment_subscriptions set dedupe_key = substr(id, 5) where dedupe_key is null and id ~ '^sub_[0-9a-f]{32}$';
        get diagnostics n = row_count;
        insert into report values ('구독 dedupe_key 채움', 'payment_subscriptions.dedupe_key', n);
    end if;
    if to_regclass('public.payment_refunds') is not null and to_regclass('public.payment_orders') is not null then
        update public.payment_refunds r
        set dedupe_key = encode(sha256(convert_to(coalesce(ma.new, o.account_id) || E'\n' || 'subcancel:' || coalesce(ms.new, o.origin_ref) || ':' || coalesce(mo.new, o.id), 'UTF8')), 'hex')
        from public.payment_orders o
        left join id_map ma on ma.old = o.account_id   -- 시드 계정(변환 제외)은 id 가 그대로라 맵에 없다
        left join id_map ms on ms.old = o.origin_ref
        left join id_map mo on mo.old = o.id
        where o.id = r.order_id and o.origin_type = 'SUBSCRIPTION'
          and r.dedupe_key = encode(sha256(convert_to(o.account_id || E'\n' || 'subcancel:' || o.origin_ref || ':' || o.id, 'UTF8')), 'hex')
          and r.dedupe_key <> encode(sha256(convert_to(coalesce(ma.new, o.account_id) || E'\n' || 'subcancel:' || coalesce(ms.new, o.origin_ref) || ':' || coalesce(mo.new, o.id), 'UTF8')), 'hex');
        get diagnostics n = row_count;
        insert into report values ('해지 환불 dedupe_key 다시 계산', 'payment_refunds.dedupe_key', n);
    end if;
end $$;

-- 4. 외래 키 · 사용자 트리거를 잠시 뗀다 (끝에서 다시 걸고 검증한다) -------------------------------------------------------------------------------------
create temp table fk_saved as
select conrelid::regclass::text as tbl, conname::text as name, pg_get_constraintdef(oid) as def
from pg_constraint where contype = 'f' and connamespace = 'public'::regnamespace;

create temp table trigger_tables as
select distinct tgrelid::regclass::text as tbl from pg_trigger
where not tgisinternal and tgrelid in (select oid from pg_class where relnamespace = 'public'::regnamespace);

do $$
declare r record;
begin
    for r in select * from fk_saved loop execute format('alter table %s drop constraint %I', r.tbl, r.name); end loop;
    for r in select * from trigger_tables loop execute format('alter table %s disable trigger user', r.tbl); end loop;
end $$;

-- 5. 모든 문자열 칸의 id 바꾸기 ----------------------------------------------------------------------------------------------------------------
create function pg_temp.remap(t text) returns text language plpgsql stable as $$
declare
    tok text;
    mapped text;
    result text := t;
begin
    for tok in select distinct (regexp_matches(t,
        '(?:(?:acc|idn|ses|prf|ord|rfd|sub)_[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})', 'g'))[1]
    loop
        select new into mapped from id_map where old = tok;
        if found then result := replace(result, tok, mapped); end if;
    end loop;
    return result;
end $$;

do $$
declare
    c record;
    n bigint;
    pat constant text := '(?:(?:acc|idn|ses|prf|ord|rfd|sub)_[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})';
begin
    for c in
        select col.table_name::text as tbl, col.column_name::text as col
        from information_schema.columns col
        join information_schema.tables tb on tb.table_schema = col.table_schema and tb.table_name = col.table_name and tb.table_type = 'BASE TABLE'
        where col.table_schema = 'public' and col.data_type in ('character varying', 'text', 'character')
          and col.table_name <> 'flyway_schema_history'
          and (col.table_name, col.column_name) not in (('payment_webhook_events', 'payload'), ('board_post_attachments', 'storage_key'))
          and col.column_name <> 'attachment_key'
        order by 1, 2
    loop
        execute format('update public.%I set %I = pg_temp.remap(%I) where %I ~ %L and pg_temp.remap(%I) is distinct from %I', c.tbl, c.col, c.col, c.col, pat, c.col, c.col);
        get diagnostics n = row_count;
        if n > 0 then insert into report values ('바꿈', c.tbl || '.' || c.col, n); end if;
    end loop;

    -- uuid 형 칸: notes.id 만 안다. 다른 uuid 칸이 있으면 멈춘다(모르는 칸을 남기지 않는다)
    for c in select table_name::text as tbl, column_name::text as col from information_schema.columns
             where table_schema = 'public' and data_type = 'uuid' order by 1, 2
    loop
        if c.tbl = 'notes' and c.col = 'id' then
            update public.notes set id = m.new::uuid from id_map m where m.old = notes.id::text;
            get diagnostics n = row_count;
            if n > 0 then insert into report values ('바꿈', 'notes.id (uuid)', n); end if;
        else
            raise exception 'uuid 형 칸 %.% 을 모른다 — 스크립트에 처리를 더한 뒤 다시 실행한다', c.tbl, c.col;
        end if;
    end loop;
    for c in select table_name::text as tbl, column_name::text as col from information_schema.columns
             where table_schema = 'public' and data_type in ('json', 'jsonb') order by 1, 2
    loop
        raise exception 'json 칸 %.% 을 모른다 — 스크립트에 처리를 더한 뒤 다시 실행한다', c.tbl, c.col;
    end loop;
end $$;

-- 6. 외래 키 · 트리거 되살리기 (키는 다시 검증된다 = 참조 무결성) ------------------------------------------------------------------------------------
do $$
declare r record;
begin
    for r in select * from fk_saved loop execute format('alter table %s add constraint %I %s', r.tbl, r.name, r.def); end loop;
    for r in select * from trigger_tables loop execute format('alter table %s enable trigger user', r.tbl); end loop;
end $$;

-- 7. 검사 ---------------------------------------------------------------------------------------------------------------------------------------
create temp table check_rows (tbl text, col text, rows_total bigint, old_format bigint);

do $$
declare
    t record;
    total bigint;
    old_n bigint;
begin
    -- 행 수
    for t in select b.tbl, b.n as before_n from counts_before b order by 1 loop
        execute format('select count(*) from public.%I', t.tbl) into total;
        if total <> t.before_n then raise exception '% 의 행 수가 달라졌다 (% -> %)', t.tbl, t.before_n, total; end if;
    end loop;

    -- 대상 표의 id 칸: 옛 형식 0건
    for t in
        select * from (values
            ('accounts', 'id', '^acc_[0-9a-f]{32}$'), ('account_identities', 'id', '^idn_[0-9a-f]{32}$'), ('auth_sessions', 'id', '^ses_[0-9a-f]{32}$'),
            ('author_profiles', 'id', '^prf_[0-9a-f]{32}$'), ('payment_orders', 'id', '^ord_[0-9a-f]{32}$'), ('payment_refunds', 'id', '^rfd_[0-9a-f]{32}$'),
            ('payment_subscriptions', 'id', '^sub_[0-9a-f]{32}$'),
            ('notes', 'id', '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
            ('notification_inbox', 'event_id', '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
        ) v(tbl, col, pat)
    loop
        if to_regclass('public.' || t.tbl) is null then continue; end if;
        execute format('select count(*) from public.%I', t.tbl) into total;
        if t.tbl = 'notes' then
            -- uuid 형 칸은 UUIDv7 인지(버전 7)로 본다
            execute 'select count(*) from public.notes where substr(id::text, 15, 1) <> ''7''' into old_n;
        else
            execute format('select count(*) from public.%I where %I ~ %L', t.tbl, t.col, t.pat) into old_n;
        end if;
        insert into check_rows values (t.tbl, t.col, total, old_n);
        if old_n > 0 then raise exception '%.% 에 옛 형식 id 가 % 건 남았다', t.tbl, t.col, old_n; end if;
    end loop;
end $$;

-- 다른 칸에 남은 옛 형식 값(가리키는 행이 없는 참조 — 지워진 행 · 이전부터 끊긴 것). 실패는 아니고 알려 준다.
create temp table dangling (tbl text, col text, n bigint);
do $$
declare c record; n bigint;
begin
    for c in
        select col.table_name::text as tbl, col.column_name::text as col
        from information_schema.columns col
        join information_schema.tables tb on tb.table_schema = col.table_schema and tb.table_name = col.table_name and tb.table_type = 'BASE TABLE'
        where col.table_schema = 'public' and col.data_type in ('character varying', 'text', 'character')
          and col.table_name <> 'flyway_schema_history'
          and (col.table_name, col.column_name) not in (('payment_webhook_events', 'payload'), ('board_post_attachments', 'storage_key'))
          and col.column_name <> 'attachment_key'
    loop
        execute format($q$select count(*) from public.%I where %I ~ '(acc|idn|ses|prf|ord|rfd|sub)_[0-9a-f]{32}'$q$, c.tbl, c.col) into n;
        if n > 0 then insert into dangling values (c.tbl, c.col, n); end if;
    end loop;
end $$;

\echo
\echo '== 바뀐 것 (단계 / 칸 / 행 수)'
select step, target, n from report order by step desc, target;
\echo
\echo '== 검사: 대상 id 칸의 행 수와 옛 형식 개수 (옛 형식은 0 이어야 하고 아니면 위에서 이미 중단됐다)'
select tbl, col, rows_total, old_format from check_rows order by tbl;
\echo
\echo '== 검사: 표마다 변환 전후 행 수 (같아야 하고 아니면 위에서 이미 중단됐다)'
select b.tbl, b.n as rows_before,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from public.%I', b.tbl), false, true, '')))[1]::text::bigint as rows_after
from counts_before b order by 1;
\echo
\echo '== 알림: 가리키는 행이 없어 옛 형식 그대로 남은 참조 (없으면 비어 있다)'
select tbl, col, n from dangling order by 1, 2;
\echo
\echo '== 알림: 모양이 달라 바꾸지 않은 id (시드 계정 같은 고정 이름) — 계정 표'
select count(*) filter (where id !~ '^acc_[0-9a-z]{26}$') as accounts_not_sortable from accounts;

\if :dry
\echo
\echo '== --dry-run : 되돌린다 (아무것도 바뀌지 않았다)'
rollback;
\else
commit;
\echo
\echo '== 커밋했다'
\endif
