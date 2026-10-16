# 작성자 프로필 — 진행 문서 (최종)

- 정본: 스펙 `2026-10-07-author-profiles-design.md`(같은 폴더) + 지휘 세션이 준 넘겨받기 프롬프트(주인 결정이 스펙과 다르면 프롬프트가 이긴다).
- 앞 리드: 없음 (최초)
- 워크트리: kotlin `projects/kotlin-skeleton-wt-profiles` (feat/author-profiles, 시작 e76e8e7) · react `projects/react-skeleton-wt-profiles` (feat/author-profiles, 시작 49a5ae3)
- 커밋은 로컬만. push · main 병합은 하지 않았다(주인 허락 필요).

## 상태: 작업 끝, 주인 확인 대기

## 작업 표
| 키 | 내용 | 커밋 (kotlin) | 리뷰 |
|---|---|---|---|
| B0 | 스펙 「정해야 할 것」 결정 반영 | e3bd273 | 문서만 |
| B1 | platform SPI | a72b380, 96ba887 | 1회차 Minor 반영 |
| B2 | board-jdbc `author_ref` | 8cd3b54, ef17643 | 1회차 Important 2 반영 → 확인 리뷰 통과 |
| B3 | board 공개 id · 쓰기 규칙 | acd8269, f258752, 5aa2ac7 | Important 4 + 확인 Important 1 반영 |
| B4 | storage OPAQUE 키 · `StorageKeyOwnership` | c4bef2c, a756dfc, 9c39c44 | Important 4 + 확인 Important 1 반영 |
| B5 | `author-profile` + `-jdbc` (a 도메인 · b 어댑터 · c HTTP) | 3379292, d2fa550, 16db8c4, 6031c86, cd3468b, 7f8b492 | 각 조각 1회차 Important 반영 |
| B6 | 관리자 연결 조회 + `AUTHOR_LINK_VIEWED` | a3a28dc, d9b3bc5 | Important 4 반영 |
| B7 | 누출 스캔 · 계약 문서 · CHANGELOG | 10406df, 9c585d4, dbf2bed, 833c0b1, 79e88cf, c6d712b, 3bbc651 | B7 Important 8 + 최종 독립 리뷰(Critical 1, Important 2+5) 반영, 확인 리뷰 Important 0 |
| B8 | `apps/sample` 에 켜기 | 8e7d8ad, f385341, 89efa87 | Important 1 + 끝검사 실패 2 반영 |
| F1 | `@skeleton/board` | acb9bdb … (react) | 아래 |
| F2 | `@skeleton/author-profile` | 79ee18b … (react) | 아래 |
| F3 | `apps/sample` 연결 + e2e | b63341e … c4ff3b0 (react) | 아래 |
| F4 | 관리자 화면 "프로필로 계정 찾기" | **만들지 않음**(스펙상 선택) | |

react 커밋: acb9bdb, 79ee18b, 11d6215, 3163f32, dff425c, b63341e, 68f55b5, 34725fb, d55d6e5, c4ff3b0, 2f838b1, 56be27c, 9557612, 867d3d1, 011aa61 (HEAD).

## 검증 (깨끗한 복사본, 증거)
- kotlin 89efa87 이전 HEAD 3bbc651: `./gradlew build --offline` exit 0 (시험 2448, 실패 0), `scripts/test-deploy-contract.sh` exit 0 (94 ✓), `scripts/test-new-project.sh --full` 4건 실패 → 89efa87 로 수정 → 재실행 exit 0 (✓ 200 / ✗ 0).
- react 9557612: lint · typecheck · format:check · capabilities:check · build · test:stories(685) 통과, `pnpm e2e:sample` 71 passed | 10 skipped(설정에 따른 skip). `test-new-project.sh --full` 2건 실패 → 867d3d1 로 수정 → 재실행 exit 0 (191 ✓ / 0). 011aa61 에서 e2e 재실행 71 passed.
- qa 화면 확인: 별도 포트의 실제 스택 — 데스크톱 6/8 항목 통과 + 키보드 통과, 좁은 화면 실패 2건 → 011aa61 로 수정 → 375/320px 재확인 통과. 스크린샷 · `captions.md`: `…/scratchpad/profiles/` .
- 실패 없이 남은 예외: kotlin 단독 gradle 한 번이 Testcontainers Ryuk 접속 실패(환경) — `TESTCONTAINERS_RYUK_DISABLED=true` 로 통과.

## 스펙에서 벗어난 결정 / 실측
- storage OPAQUE 키는 클라이언트 파일 이름을 버리고 내장 허용 확장자만 남긴다(파일명이 두 프로필을 잇는 값이므로). 스펙 §7.8 에 기록.
- AuthorIdentities 가 있고 `author_ref` 가 NULL 인 행은 톰스톤이어도 공개 id 가 `pending:p<id>`/`pending:c<id>` (스펙 §7.2 보강).
- `pendingAccounts(limit, after)` 커서 추가. 첫 프로필은 GET 과 POST 가 먼저 보장하고(옛 글 넘겨받기 포함), 비ACTIVE 계정에는 새로 만들지 않는다. 만들기 한도는 표 기준으로 앵커 잠금 안에서 센다. 쓰기 트랜잭션은 READ COMMITTED.
- POST 는 `Idempotency-Key` 재생을 지원(`@IdempotentOperation`).
- 마이그레이션: 병합 전 내 새 V 파일(board_author_ref)을 한 번 재작성(`--rewrite`)했다 — 로컬에서 8cd3b54 판을 적용한 DB 는 체크섬 불일치 가능(`down -v`). 병합 뒤에는 해당 없음.
- §15 실측 전부 스펙에 기록(Cache-Control 안전, PATCH 모르는 칸 무시, canAttach 실제 위치, 인덱스 실행 계획, AccountAutoConfiguration 보다 먼저 등록되는 순서 변이 확인).

## 수정 회송 한도
- 프론트 F1/F2/F3 와 백엔드 B7 은 단계별 3회차를 넘겼다. (1) 각 작업의 단계별 리뷰 3회 → 소진 뒤 최종 독립 리뷰가 새 지적을 냈고 최종 단계로 따로 3회까지 반영, (2) qa 의 좁은 화면 실패 2건(확인 리뷰 3회차 Important 와 같은 것)을 **예외로 1회 더** 고쳤다(011aa61). 이 예외를 보고에 명시한다.

## 남은 Minor (반영하지 않음)
- 프론트: Safari 에서 대화상자 opener 포커스 보정이 절반만(`open(event)` 없는 `ensure()` 경로), 스토리 파일 200줄 초과 몇 곳, 낭독기 실측 없음, 기기 기억이 "고른 것"을 저장(스펙 문구는 "쓴 것"), 키보드로 메뉴를 열면 선택된 항목이 아니라 첫 항목에 포커스.
- 백엔드: MySQL 에서 만들기와 지우기가 겹칠 때 교착 가능성(추측, 앵커를 먼저 잠그는 `markDeleted` 로 줄일 수 있음), `insertWithinCap` 의 바깥 트랜잭션 합류 주석이 PostgreSQL REPEATABLE READ 에서는 틀림, 5초 알람이 자식 프로세스를 못 끊음, 관리자 erase 경로 스캔의 V1/V2 프로브 단정 보강, SPI KDoc 한 줄의 0행 의미, 어댑터 문서의 `RATE_LIMITED`·앵커 분리 설명, 같은 첨부 키를 두 프로필 글에 싣는 API 직접 호출 잔여 위험(스펙 §14 에 기록), 커밋 형식(작업공간 Git 규칙의 Co-Authored-By 금지와 하네스 지시의 충돌 — 주인이 정할 일).

## 정해야 할 것 (주인)
1. **`CLAUDE.md` 「Backend Module Layout」** 에 `author-profile`/`author-profile-jdbc` 두 줄과 opt-in HTTP 목록(`/api/v1/author-profiles`) 추가 — developer 는 CLAUDE.md 수정을 주인 승인 사항으로 보류함. (1) 허락하면 지휘 세션이 두 줄 추가 (추천) (2) 카탈로그(`capabilities.json`/`llms.txt`)만으로 충분하다고 보고 생략.
2. **연결 조회 감사 기록 실패 시 정책**: 지금은 감사 저장 실패(또는 `account.audit.enabled=false`)여도 조회 응답이 나간다(기동 경고 + 문서). (1) 현상 유지 (추천 — 감사 저장소 장애가 관리자 업무를 막지 않음) (2) fail-closed.
3. **대상 계정 삭제 시 감사 행의 `by` scrub**: 기존 account 이벤트와 같은 동작이라 "누가 봤는지"가 사라진다. (1) 문서화한 한계로 둠 (추천) (2) 이 종류만 scrub 에서 보존(account-jdbc 변경).
4. **설정 화면의 「문의용 정보(계정 id + 복사)」 구역 제거**를 알고 있는지(화면 원칙에 맞춰 제거함). 필요하면 다른 문의 수단을 정해야 한다.
5. **커밋 형식**: 작업공간 `AGENTS.md` 는 `Co-Authored-By` 금지·header 50자 권장인데 이 브랜치(와 main)는 하네스 지시대로 트레일러를 단다. 어느 쪽을 정본으로 할지.
6. **병합 순서**: kotlin·react 두 브랜치는 서로 짝이다(e2e 와 카탈로그 형제 대조가 서로를 본다). 같이 병합해야 `CapabilitiesCatalogTest` 의 형제 검사와 react `tests/capabilities.skeleton.test.ts` 가 초록이 된다. 실사용 프로젝트 권고(첫 배포부터 켠다)는 CHANGELOG 에 적어 두었다.
