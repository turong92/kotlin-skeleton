# 친구 초대 · 추천 보상 — 설계 (초안)

> 2026-10-30. 요청: 초대 · 추천 보상 기능 — 코드와 링크, 가입 귀속, 보상 시점, 양쪽 보상, 상한, 어뷰징 막기, 운영자 화면(내역 · 취소 · 회수), 사용자 화면(내 코드 · 초대 현황), 견본 조립과 e2e 한 여정.
> 형 원칙: 공개 스켈레톤 · 캡슐화된 조립식 모듈 / 모듈 기본값은 중립, 앱 yml · 설정이 명시적으로 덮어쓴다 / 국룰 / 배지 · 포인트 · 쿠폰은 각자 독립, 보상 목록에서 조립 / 개인 도메인 · 이메일 흔적 없음.
> 대상: kotlin-skeleton(기존 `campaign` · `campaign-jdbc` · `apps/sample`) + react-skeleton(`@skeleton/rewards` 의 `/campaign` · `/admin`, `apps/sample`).
> **출발점 — 친구초대는 이미 있다**(2026-10-28 이벤트 · 보상 설계 §4.2, `campaign` 의 종류 `referral`): 계정당 코드 하나(헷갈리는 글자 뺀 8자) · `?ref=` 30일 보관 → 로그인 뒤 claim 한 번 · 가입 후 `claimWithinDays` 안 · 자기 코드 · 맞초대 · 평생 한 번 거절 · 양쪽 보상(`invitee` · `inviter` 보상 목록) · 초대자 보상 시점 `qualifyOn: CLAIM|SIGNAL`(기본값 없음) · 이벤트당 초대자 상한 `inviterCap` · 탈퇴 처리. 화면은 `InviteCard`(코드 · 복사 · 수 둘) · `useReferralCapture`.
> 이 문서는 **빠진 것만** 더한다: 보류 기간 · 운영자 확인 · 취소, 이름 붙은 신호, 같은 네트워크 표시, 초대 현황(사용자 · 운영자), 코드 직접 입력, e2e 여정. 이번에 읽은 코드: `campaign/ReferralService.kt` · `CampaignConfigs.kt` · `CampaignModels.kt` · `web/CampaignControllers.kt` · `campaign-jdbc` 마이그레이션 · `reward/RewardGrantService.kt` · `common/account/*` · `account/AccountBlocks.kt` · `docs/client-ip.md` · react `campaign/*` · `admin/AdminCampaigns.tsx` · 견본 `RewardsSeed` · `CheerOrderListener`.

## 0. 국룰 조사 → 권장안

| 물음 | 업계 | 권장 (지금 → 이번) |
|---|---|---|
| 코드 · 링크 | 계정당 고정 코드 + 그 코드를 담은 링크(Uber · Airbnb · Toss). 고른 코드(vanity)는 일부만 | 그대로(8자, 처음 볼 때 생성, 링크 `/?ref=CODE` 는 앱이 짓는다). 재발급 · 고른 코드는 다음 판 |
| 귀속 | 링크 클릭을 30~90일 보관, **마지막 클릭 우선**. 국내는 가입 폼 「추천인 코드(선택)」 칸도 흔하다 | 그대로(브라우저 30일 보관 · 마지막 링크가 덮어씀 · 로그인 뒤 claim). **가입 뒤 직접 입력 칸**을 더한다 — 소셜 가입처럼 폼이 없는 길도 덮는다 |
| 보상 시점 | 초대받은 사람이 **의미 있는 행동**을 했을 때 초대자 보상(Uber 첫 탑승 · Airbnb 첫 숙박 완료 · Dropbox 설치 · 핀테크 첫 입금). 초대받은 사람 쪽은 가입 즉시 환영 혜택이 흔하다 | 그대로 + **신호에 이름**(`qualifyEvent: "first-payment"`) — 앱이 여러 신호를 내도 이벤트가 고른 것만 먹는다 |
| 보류 · 확인 | 보상을 바로 주지 않고 환불 · 취소 기간만큼 **보류** 뒤 확정(Airbnb 숙박 완료 뒤 · 커머스 반품 기간 뒤). 의심 건은 사람이 본다 | **보류 일수**(`holdDays`) + **운영자 확인 대기**(`REVIEW`) + 보류 중 취소 |
| 상한 | 인당 상한(Dropbox 32명) · 기간 한정 이벤트 | 그대로 — 이벤트당 `inviterCap`, 기간 상한은 이벤트 기간(`startsAt`~`endsAt`)으로. 월 단위 · 전체 예산 상한은 다음 판 |
| 자기 초대 · 다계정 | 자기 코드 · 맞초대 거절, 같은 기기 · IP 는 **거절보다 검토**(가족 · 회사 · 학교가 IP 를 나눈다), 기기 지문 · 본인인증은 큰 서비스 몫 | 같은 네트워크면 표시(`SAME_NETWORK`) + 이벤트 설정이 켜면 초대자 보상을 운영자 확인으로. 기기 지문 · 본인인증은 비목표 |
| 지급 뒤 회수 | 드묾 — 보류로 막고, 사기면 약관상 회수(포인트 차감 · 쿠폰 회수) | 보류 중 취소까지. 지급 뒤는 각 모듈의 기존 도구(쿠폰 회수 · 포인트 조정 · 배지 회수) — 정해야 할 것 2 |

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 코드 · 귀속 | 기존 그대로 + 가입 뒤 직접 입력(`claimWithinDays` 안, 받을 수 있는 사람에게만 보인다) | 운영자 코드 재발급(유출 코드) · 고른 코드 · 가입 폼 코드 칸(`SignUpScreen` 슬롯) · 공유 단추(카카오톡 · QR) |
| 보상 시점 | `CLAIM` / `SIGNAL` + 이름 붙은 신호 `qualifyEvent` · 보류 `holdDays` · 확인 대기 `REVIEW` | 스켈레톤 모듈 신호 자동 연결(첫 결제 · 이메일 확인) · 「n명 초대」 단계 보상(업적 `referrals.qualified` 보고로 조립) |
| 어뷰징 | 같은 네트워크 표시 · `reviewSameNetwork` · 앱의 보류 중 취소(환불 등) | 탈퇴 후 재가입 표시(정해야 할 것 1) · 기간 · 예산 상한 · 기기 신호 |
| 운영 | 초대 내역(필터 · 표시 · 계정 이름) · 승인 · 취소(사유 필수) · 이벤트 폼의 새 칸 | 지급 뒤 회수 공통 계약(정해야 할 것 2) · 초대 대시보드 카드 |
| 사용자 | 내 코드 · 초대 현황 목록(가린 이름 · 상태 · 예정일) · 직접 입력 | 「친구가 가입했어요」 알림 |

**비목표**: 제휴(어필리에이트) 정산 · 현금 지급, 다단계(초대의 초대), 기기 지문 · 본인인증, 초대 메일 · 문자 보내기, 규칙 엔진.
**반목표**: 같은 초대가 두 번 보상 / 상한을 넘는 보상(동시 claim · 동시 신호 · 보류 해제 겹침) / 사용자 화면에 「사기 의심」이 드러남 / 초대자에게 초대받은 사람의 이메일 원문이 보임 / 설정을 안 바꾼 기존 초대 이벤트의 동작이 바뀜.

## 2. 모듈 경계 · 확장 지점

**캠페인 종류로 둘까, 따로 모듈로 뺄까 — 내가 정한 것: `campaign` 안에 둔다.**

| | `campaign` 종류(지금 자리) | 새 `referral`(+`-jdbc`) 모듈 |
|---|---|---|
| 보상 목록 · 기간 · 켜고 끄기 · 운영 폼 | 그대로 쓴다 | 같은 것을 다시 만들거나 `campaign` 에 의존 → 결국 같은 짝 |
| 데이터 | 표 둘이 이미 있고 마이그레이션은 얼어 있다 — 열만 더한다 | 표 옮기기(데이터 이관 V 파일) · `Referrals` 패키지 이동 = 공개 계약 깨짐(CHANGELOG 파괴 변경) |
| 조립 | 출석 · 업적과 한 줄 | 초대만 쓰는 앱이 출석을 안 끌고 온다 — 그러나 `campaign` 은 가볍고 출석은 운영자가 만들어야 생긴다 |
| 업계 | 프로모션 엔진들은 초대를 「프로그램 종류 하나」로 둔다. 따로 떼는 건 제휴 정산까지 가는 전문 제품 | |

이유 한 줄: 초대 관계는 이벤트의 보상 목록 · 기간 없이는 뜻이 없고, 떼는 비용(이관 · 계약 깨짐)에 비해 얻는 것이 없다. 제휴 정산처럼 성격이 다른 두 번째 쓰임이 생기면 그때 뗀다. 대신 `ReferralService` 가 커지므로 파일을 `campaign/referral/` 하위 패키지로 모은다(공개 타입 `Referrals` 는 같은 이름 유지 — 패키지 이동은 하지 않는다).

```
campaign(-jdbc) ──api──▶ reward ──▶ platform{AccountFacts, AccountLabels, ClientIps, OperatorAccess, DeployGuard}
campaign ··compileOnly··▶ scheduler   (보류 해제 주기 작업 — point 의 소멸 작업과 같은 선택 연동)
```

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `Referrals.qualify(inviteeId, event)` (기존 `qualify(inviteeId)` 는 `event = null` 로 남김) | `campaign` | 보상 조건은 앱 코드가 가장 잘 안다(첫 결제 · n일 활동). 이름이 있어야 앱이 여러 신호를 내도 이벤트 설정이 고른다 |
| `Referrals.cancel(inviteeId, reason)` | `campaign` | 보류의 존재 이유 — 앱이 환불 · 사기를 알면 보류 중 보상을 끊는다 |
| `Referrals.releaseDue()` | `campaign` | `scheduler` 가 없는 앱이 직접 부른다(`PointWallet.expireDue` 선례) |
| 설정 칸 `qualifyEvent` · `holdDays` · `reviewSameNetwork` | `ReferralConfig` | 운영자가 이벤트마다 고른다. 모두 비우면 지금과 같다(중립) |
| 네트워크 키 = platform `ClientIps` 의 `limitKey` | `campaign` web | 이미 있는 「누가 부르나」 규칙. IPv6 는 /64 로 묶인다 |

새 포트 · 새 플랫폼 계약은 없다(정해야 할 것 1 이 「넣는다」면 `AccountFacts` 에 하나 생긴다).

## 3. 데이터 (새 V 파일 하나, 두 방언 — 열 더하기만)

- `campaign_referrals` +: `qualify_event varchar(64) null`(먹은 신호 이름) · `release_at null`(보류 끝) · `settled_at null`(QUALIFIED · CAPPED · CANCELED 된 때) · `settled_by varchar(64) null`(운영자 id · 앱이면 `app` · 자동이면 null) · `cancel_reason varchar(200) null` · `flags varchar(64) null`(쉼표 목록, 1차는 `SAME_NETWORK` 하나) · `claim_network varchar(64) null` · 색인 `(status, release_at)`.
- `campaign_referral_codes` +: `last_network varchar(64) null` · `last_seen_at null` — 초대자가 `GET /referrals/me` 를 부른 네트워크.
- 네트워크 값은 원문 키(IP 또는 /64)다 — `auth_sessions.ip` 와 같은 수준. **30일 뒤 해제 작업이 지운다**(두 열 모두), 판정에 쓴 결과는 `flags` 로만 남는다. 탈퇴 처리는 기존 그대로(초대받은 쪽 줄 · 코드 줄 삭제, 초대자 쪽은 id 만 익명화).
- 상태(`status varchar(16)`): 기존 `CLAIMED · QUALIFIED · CAPPED` + 새 `REVIEW · HELD · CANCELED`.

## 4. 흐름

**claim**(기존 판정 그대로) — 새로 하는 일: 판정 전에 네트워크 키를 받아, 초대자의 `last_network`(30일 안) 또는 같은 초대자의 다른 초대받은 사람의 `claim_network`(30일 안)와 같으면 `flags = SAME_NETWORK`. **초대받은 사람 보상은 지금처럼 claim 때 준다**(표시가 있어도) — 가짜 계정에 쌓인 환영 혜택은 옮길 수 없어 이득이 작고, 농사의 목표는 초대자 보상이다. `qualifyOn: CLAIM` 이면 같은 트랜잭션에서 아래 「초대자 판정」.

**신호** `Referrals.qualify(inviteeId, event)` — `CLAIMED` 이고, 이벤트 설정에 `qualifyEvent` 가 있으면 이름이 같을 때만(없으면 아무 신호나 — 지금 동작). 몇 번 불러도 한 번.

**초대자 판정**(초대자 코드 줄 잠금 — 기존 직렬화 그대로), 위에서부터 처음 맞는 것:
1. 상한: `QUALIFIED + HELD + REVIEW` 수 ≥ `inviterCap` → `CAPPED`(보상 없음). 보류 · 확인 중도 자리를 차지한다 — 해제 때 다시 세지 않아 겹쳐도 넘지 않는다.
2. `reviewSameNetwork` 이고 `SAME_NETWORK` → `REVIEW`(자동으로 풀리지 않는다).
3. `holdDays > 0` → `HELD`, `release_at = now + holdDays`.
4. 그 밖 → `QUALIFIED` + 초대자 보상 `RewardGrants.record`(지급 건 키 `<이벤트>:<초대받은 사람>:inviter:<순번>` 그대로 — 두 번 와도 한 건).

**해제** 주기 작업(`scheduler` 가 있으면 10분마다 `campaign.referral.release`, 없으면 앱이 `releaseDue()`): `HELD ∧ release_at ≤ now` → `QUALIFIED` + 보상, 조건부 갱신(`where status='HELD'`)이라 겹쳐 돌아도 한 번. 같은 작업이 30일 지난 네트워크 값을 지운다.
**운영자 승인** `REVIEW|HELD → QUALIFIED` + 보상(남은 보류를 건너뛴다). **취소**(운영자 · 앱 `cancel`) `CLAIMED|REVIEW|HELD → CANCELED` + 사유. 끝 상태(`QUALIFIED · CAPPED · CANCELED`)는 바뀌지 않는다 — 지급 건이 아직 `PENDING` 이면 기존 「지급 건 취소」(`reward`)로, 지급 뒤는 각 모듈의 회수 도구로.

```
CLAIMED ──신호/CLAIM──▶ CAPPED │ REVIEW │ HELD │ QUALIFIED
REVIEW ──승인──▶ QUALIFIED     HELD ──기한 · 승인──▶ QUALIFIED
CLAIMED · REVIEW · HELD ──취소──▶ CANCELED
```

## 5. API

| 경로 | 하는 일 | 권한 |
|---|---|---|
| `GET /api/v1/referrals/me` | 기존 `{code, invited, rewarded}` + `canClaim`(진행 중 초대 이벤트가 있고 아직 초대받지 않았고 기간 안) · `claimUntil`(`AccountFacts` 가 있으면 시각, 없으면 null). 부를 때 `last_network` 를 적는다 | 로그인 |
| `GET /api/v1/referrals/me/invitees?cursor&size` | 내가 초대한 사람들, 새것부터 커서 `{label, status, claimedAt, rewardAt}`. `label` 은 가린 이름(아래), `status` 는 사용자용 넷: `WAITING`(CLAIMED) · `PENDING`(REVIEW · HELD — `rewardAt` 은 HELD 만) · `REWARDED` · `NOT_REWARDED`(CAPPED · CANCELED). 확인 대기 · 표시 · 사유는 내보내지 않는다 | 로그인 |
| `POST /api/v1/referrals/claim {code}` | 기존 그대로(직접 입력도 이 경로) | 로그인 |
| `GET /api/v1/admin/referrals?status&inviterId&inviteeId&campaignId&flag&cursor&size` | 초대 내역 `{inviteeId, inviterId, inviteeLabel, inviterLabel, campaignId, status, flags, qualifyEvent, claimedAt, qualifiedAt, releaseAt, settledAt, settledBy, cancelReason}`. 이름은 `AccountLabels` — `account.read` 도 있는 운영자에게만(쿠폰 · 배지 선례) | `campaign.manage` |
| `POST /api/v1/admin/referrals/{inviteeId}/approve` | `REVIEW|HELD` → 지급(그 밖 409 `CAMPAIGN.REFERRAL_NOT_PENDING`) | `campaign.manage` |
| `POST /api/v1/admin/referrals/{inviteeId}/cancel {reason}` | `CLAIMED|REVIEW|HELD` → 취소, 사유 빈 값 400 | `campaign.manage` |

- 운영 경로는 기존 `skeleton.campaign.admin.enabled`(기본 꺼짐 → 404)를 따른다. 새 권한은 만들지 않는다. 운영 기록 `campaign.referral.approved` · `campaign.referral.canceled`(대상은 초대받은 사람 id 만).
- 가린 이름: `AccountLabels` 값의 앞 두 글자(두 글자 이하면 한 글자) + `***`, `@` 뒤는 버린다. 구현이 없으면 `null`(화면은 「친구 n」).
- 설정 검증(`CONFIG_INVALID`): `qualifyEvent` `[a-z0-9._-]{1,64}` 이고 `qualifyOn: SIGNAL` 일 때만 · `holdDays` 0~90(없으면 0) · `reviewSameNetwork` 불리언(없으면 false). 이미 저장된 설정은 그대로 읽힌다.
- `DeployGuard` 경고: 진행 중 초대 이벤트가 `reviewSameNetwork` 를 켰는데 `skeleton.web.client-ip.mode` 가 비어 있으면(헤더로 네트워크를 속일 수 있다 — `docs/client-ip.md`). 경고만, 막지 않는다.

## 6. 화면 (react `@skeleton/rewards`)

- `/campaign`: `InviteCard` 는 그대로 두고 옆에 둘 — `InviteList`(초대 현황 표: 가린 이름 · 상태 배지 · 날짜, 빈 상태 · 더 보기 커서, 상태 문구는 labels), `ReferralCodeForm`(`canClaim` 일 때만 그린다 — 코드 입력 · 제출 중 · 거절 이유 `data.reason` 별 문구 · 성공). `createCampaignApi` 에 `myInvitees` 추가, `ReferralMe` 에 새 칸.
- `/admin`: `AdminReferrals`(목록 Pattern 복사 — 상태 · 표시 필터, 「같은 네트워크」 표시 배지, 행 메뉴 승인 · 취소, 취소 대화상자 사유 필수), `AdminCampaigns` 의 초대 폼에 칸 셋(신호 이름 — `SIGNAL` 일 때만 · 보류 일수 · 같은 네트워크 확인 스위치). `REWARDS_PERMISSIONS` 는 기존 `campaign.manage`.
- 문구 ko/en 라벨, 부품마다 스토리 + `play`, `ssrFixtures` 한 줄씩, 눌리는 것은 눌리게.

## 7. 견본 앱

- kotlin `apps/sample`: 씨앗의 「친구 초대」에 `qualifyEvent: "first-payment"` · `holdDays: 7`(청약철회 7일) · `reviewSameNetwork: true` — **이미 만들어진 로컬 DB 는 이름으로 찾아 없을 때만 만들므로 바뀌지 않는다**(CHANGELOG · 견본 문서에 `docker compose down -v` 안내). `CheerOrderListener.onPaid` 는 `qualify(accountId, "first-payment")`, `onRefunded` 는 `cancel(accountId, "refunded")`(보류 · 확인 중일 때만 먹는다). `scheduler` 는 이미 조립돼 있어 해제 작업이 그대로 돈다.
- react `apps/sample`: 보상 화면에 `InviteList` · `ReferralCodeForm`, 운영 메뉴 「친구 초대」 → `AdminReferrals`.
- e2e 한 여정(`referrals.e2e.ts`): ① A 가 보상 화면에서 링크 복사 ② 새 브라우저 문맥으로 링크 → 가입(메일 코드) → B 쿠폰함에 「응원 30% 할인」, A 현황에 `WAITING` ③ B 가 첫 응원 결제(가짜 결제사) → 같은 기계라 `SAME_NETWORK` → A 현황 `PENDING` ④ admin 이 초대 내역에서 표시를 보고 승인 → A 에게 500포인트 알림 · 현황 `REWARDED`. 보류 기한 해제 · 환불 취소 · 상한은 kotlin 통합 시험(시계를 옮긴다)이 맡는다.

## 8. 검증 (깨지면 사고 나는 것만)

**kotlin**: 신호 이름이 다르면 무시 · 같은 신호 두 번 → 판정 한 번 · `qualifyEvent` 없는 이벤트는 기존과 같음(기존 시험 그대로 통과). 상한이 `HELD`/`REVIEW` 를 세어 동시 신호 · 해제 겹침에도 안 넘음. 해제 작업 두 번 · 승인과 해제 동시 → 지급 건 한 벌. 취소된 건 해제 · 승인 409, 끝 상태 취소 409. `SAME_NETWORK`: 초대자 `last_network` 일치 · 형제 일치 · 30일 지난 값 불일치 · 값 지움. 사용자 현황 응답에 `REVIEW` · 표시 · 사유 · 이메일 원문 문자열 없음. 설정 검증 경계값. 탈퇴(새 열 포함). 두 방언 `dbTest`, `MigrationLockTest` · `ModuleDocumentationTest` · `CapabilitiesCatalogTest`, `noOptionalTest`(scheduler 없이).
**react**: `InviteList` 상태 넷 · 빈 · 더 보기, `ReferralCodeForm` 거절 이유별 문구, `AdminReferrals` 취소 사유 필수 · 승인, 초대 폼 `SIGNAL` 일 때만 신호 칸, api 경로 기록 시험, `capabilities` 검사.
**e2e**: §7 ①~④.

## 내가 정한 것 (국룰 · 레포 관례로)

1. 초대는 `campaign` 안에 둔다(§2 비교). 하위 패키지로 모으되 공개 타입 위치는 그대로.
2. 귀속은 그대로 — 브라우저 30일 · 마지막 링크 우선 · 로그인 뒤 claim · 가입 후 `claimWithinDays`. 가입 폼 칸 대신 **가입 뒤 직접 입력**(소셜 가입도 덮고 `account` 를 건드리지 않는다).
3. 보류 · 확인 · 취소는 초대 관계의 상태로 둔다(지급 건을 늦추는 `reward` 쪽 일반 기능이 아니라) — 사용자 화면의 「보상 예정일」과 앱의 `cancel` 이 관계를 봐야 하기 때문.
4. 같은 네트워크는 거절이 아니라 표시 + (설정 시) 운영자 확인, 초대자 쪽만. 기본은 꺼짐.
5. 상한은 보류 · 확인 중을 자리로 센다. 새 기간 상한은 만들지 않는다(이벤트 기간이 그 역할).
6. 초대자에게는 가린 이름만, 운영자에게는 `account.read` 가 있을 때만 이름.
7. 운영 권한은 기존 `campaign.manage` 하나. 네트워크 원문은 30일만.
8. 코드 형식 · 재발급은 그대로(재발급은 다음 판 — 유출 피해는 상한 · 확인이 묶는다).

## 작업 계획

| 조각 | 레포 | 내용 | 선행 | 크기 | 예상 |
|---|---|---|---|---|---|
| K1 | kotlin | `campaign`/`campaign-jdbc`: 설정 칸 셋 · 상태 셋 · 초대자 판정 순서 · `qualify(event)` · `cancel` · `approve` · `releaseDue` + 주기 작업(`scheduler` compileOnly) · 네트워크 표시 · V 파일 두 방언 + `migrations.lock` · `dbTest` · `noOptionalTest` · DeployGuard 경고 | — | 대 | 3~4시간 |
| K2 | kotlin | HTTP: `me` 새 칸 · `me/invitees` · `/admin/referrals` 목록 · 승인 · 취소 · 운영 기록, 웹 시험, `docs/modules/campaign*.md` · `docs/config/modules/campaign.yml` · `capabilities.json` · CHANGELOG | K1 | 중 | 2시간 |
| K3 | kotlin | 견본: 씨앗 · `CheerOrderListener`(이름 신호 · 환불 취소) · `RewardsFlowIntegrationTest` 보강(보류 해제 · 환불 취소 · 상한 — 시계 이동) · `docs/sample.md` | K2 | 소 | 1~1.5시간 |
| R1 | react | 사용자: 타입 · `myInvitees` · `InviteList` · `ReferralCodeForm` · labels ko/en · 스토리 + play · `ssrFixtures` · README · `ui-catalog` | §5 계약만 | 중 | 2~3시간 |
| R2 | react | 운영: `AdminReferrals` · 초대 폼 새 칸 · adminApi · 스토리 + play · labels | §5 계약만 | 중 | 2~3시간 |
| R3 | react | 견본 조립(보상 화면 · 운영 메뉴) + `referrals.e2e.ts` 여정 + `capabilities` | K3 · R1 · R2 | 중 | 1.5~2시간 |

K1 → K2 → K3 은 차례로, R1 · R2 는 K1 과 나란히(계약은 §5). 전체 스위트는 K3 · R3 끝에서 지휘 세션이 한 번.

## 정해야 할 것

1. **탈퇴 후 재가입한 사람을 「새 사람」으로 볼까** — 지금은 탈퇴하면 초대받은 쪽 줄이 지워져, 같은 이메일로 다시 가입한 계정이 다시 초대받고 환영 혜택을 또 받는다.
   - (추천) **1차에서는 막지 않고 다음 판으로** — 탈퇴 유예 기간 · 가입 후 N일 · 초대자 보상의 첫 결제 조건 · 상한이 이미 농사 비용을 높인다. 막으려면 `account` 가 이메일 · 소셜 주체의 HMAC 을 내주는 플랫폼 계약과 개인정보 처리방침 문구가 함께 필요해 조각이 하나 는다.
   - 1차에 넣는다: `AccountFacts` 에 사람 키(HMAC, `account.blocks.secret` 재사용) → `campaign` 이 초대받은 사람의 키를 기한(앱 설정, 기본 꺼짐) 동안 보관, 다시 오면 `RETURNING` 거절. 처리방침에 한 줄 더.
   - 막지 않는다(문서에 한계만 적는다).
2. **운영자 「회수」를 어디까지** — 보상이 이미 지급된 뒤 되돌리기.
   - (추천) **1차는 보류 중 취소까지** — 보류 기간이 회수를 대신하는 게 업계 관행이고, 지급 뒤는 쿠폰 회수 · 포인트 조정 · 배지 회수 등 각 모듈의 기존 운영 도구로 한다.
   - 공통 회수 계약을 1차에 넣는다: `RewardIssuer` 에 선택 `reclaim`(쿠폰은 안 쓴 것만 회수, 포인트는 잔액까지 차감, 배지는 회수) + 지급 건 「회수」 단추 — `coupon` · `point` · `badge` · `reward` 를 모두 건드려 K 조각 하나 · R 조각 하나가 는다.

## 결정됨 (2026-10-30)

형이 따로 고르지 않아 지휘 세션이 추천안으로 정했다. 이 절이 본문보다 우선한다.

1. 탈퇴 후 재가입 — 1차에서 막지 않고 다음 판으로 미룬다(한계는 문서에 적는다).
2. 운영자 회수 — 1차는 보류 중 취소까지. 지급 뒤에는 각 모듈의 기존 도구를 쓴다.
