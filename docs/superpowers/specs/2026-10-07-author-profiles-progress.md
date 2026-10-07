# 작성자 프로필 — 진행 문서

- 정본: 스펙 `2026-10-07-author-profiles-design.md`(같은 폴더) + 지휘 세션이 준 넘겨받기 프롬프트(주인 결정이 스펙과 다르면 프롬프트가 이긴다).
- 앞 리드: 없음 (최초)
- 워크트리: kotlin `projects/kotlin-skeleton-wt-profiles` (feat/author-profiles, 시작 e76e8e7) · react `projects/react-skeleton-wt-profiles` (feat/author-profiles, 시작 49a5ae3)
- 커밋 허용(로컬만). push · merge 금지.

## 지금 하는 일
최종 독립 리뷰 지적 반영 중: 백엔드(af641 — Critical 1: test-deploy-contract secret_env 무한 재귀 포함), 프론트(a4e3e — Important 5). 이후 확인 리뷰 → 깨끗한 복사본 끝검사 → qa 화면 확인.

## 작업 표
| 키 | 내용 | 상태 | 커밋 | 리뷰 |
|---|---|---|---|---|
| B0 | 스펙 「정해야 할 것」 결정 반영 + 스펙 커밋 | 끝 | e3bd273 | 문서만, 리뷰 생략 |
| B1 | platform SPI | 수정중 | a72b380 | 1회차 Minor 6 (문서·KDoc 은 B3 작업0 에서 수정) |
| B2 | board-jdbc author_ref | 확인리뷰중 | 8cd3b54,ef17643 | 1회차 I2·M5 (멱등 DDL, 인덱스 (author_ref,author_id)) |
| B3 | board 공개 id · 쓰기 규칙 | 확인리뷰중 | 96ba887,acd8269,f258752 | 1회차 Important 4 (I1 기본값 제거, I2 수정시 옛 첨부키, I3·I4 시험) |
| B4 | storage OPAQUE | 수정2끝 | c4bef2c,a756dfc | 1회차 Important 4 (owns 경로탈출, 빈/컨트롤러 문서, 샘플 OwnedKeys→B8, 파일명 제거) |
| B5 | author-profile(+jdbc) | B5a 구현중 | | (B5a 도메인·서비스 → B5b jdbc → B5c HTTP·문서·조립) |
| B6 | 관리자 연결 조회 | 수정끝 | a3a28dc,d9b3bc5 | 1회차 Important 4 (감사 전제 경고·문서, scrub 한계 문서, 로그 traceId 문구, toString) |
| B7 | 누출 스캔 · 계약 문서 · CHANGELOG | 수정중(B7 Important 8 + 최종리뷰 Critical 1·Important 2) | 10406df,9c585d4 | |
| B8 | apps/sample 켜기 | 수정끝 | 8e7d8ad,f385341 | 1회차 Important 1 (test-deploy-contract A2 비밀 env) | | 포함할 것: OwnedKeys.isOwnedBy→StorageKeyOwnership.owns, docs/sample.md:37, NoteService.kt:66 attachmentName 폴백(OPAQUE 면 file.ext), 업그레이드 시험 author_ref null 단정 갱신 |
| F1 | @skeleton/board | 3회차 소진 | acb9bdb,11d6215,3163f32,dff425c | 3회차 확인리뷰 Important 1 남음(I-3) | acb9bdb,11d6215 | 확인리뷰 Important 3 | acb9bdb(react) | 1회차 Important 6 (I1 deleted/pending 제외, I2 threadAuthorId 로딩 구분, I3 가짜서버 403, I4 aria-describedby, I5 시험 보강, I6 CHANGELOG) |
| F2 | @skeleton/author-profile | 3회차 소진 | 79ee18b | 1회차 C1+I8 (name/tag nullable 계약 — B5 가 name:string|null, tag:string|null 을 줘야 함) | 79ee18b(react) | |
| F3 | apps/sample 연결 + e2e | 리뷰끝(Important 0), e2e 71 passed | ...,c4ff3b0 | b63341e,68f55b5,34725fb,d55d6e5(react) | 1회차 Important 7 (첫프로필 자동생성 계약 반영·게이트·스캔 강화 등); e2e 는 B8 뒤 실행 |
| F4 | 관리자 화면 (선택) | 대기 | | |
| 끝검사 | 깨끗한 복사본 검증 · 독립 리뷰 · qa | 대기 | | |

## 스펙에서 벗어난 결정 / 실측 결과
- B3 실측(Cache-Control 안전, PATCH 모르는 칸 무시, canAttach 접두사 비교 없음)은 스펙 §15 에 기록됨.
- 행 종류 표기 pending:p<id>/pending:c<id>. authorId 64자 초과는 403.
- M1 결정: AuthorIdentities 있고 author_ref NULL 이면 톰스톤 author_id 도 공개 id 는 pending:(스펙 §7.2 식 보강).
- 로컬 전제: react `tests/capabilities.skeleton.test.ts` 의 kotlin main 대조 2건은 백엔드 병합 전까지 빨강(예상).

- 환경 사실: react `tests/capabilities.skeleton.test.ts` 와 kotlin `CapabilitiesCatalogTest` 의 형제 레포 대조는 react main 체크아웃을 보므로 병합 전에는 빨강(양쪽 새 항목 때문). 임시 복사본+심볼릭 링크로 통과 확인됨(B5c 보고).
- B5b: 쓰기 트랜잭션(insertWithinCap, deleteByAccount)을 READ COMMITTED 로 연다(MySQL 교착 회피). 서비스가 비ACTIVE 계정에는 첫 프로필/새 프로필을 만들지 않음(탈퇴 순서).
- 서버 계약 확정: 첫 프로필 자동 생성(GET /author-profiles, POST 가 먼저 보장), name/tag null 가능, Idempotency-Key 재생 지원(B5c 수정).

## 정해야 할 것
- [B6] 연결 조회 감사 기록이 실패(DB 감사 꺼짐/insert 실패)해도 조회 응답이 나간다(기본 audit.enabled=false 면 INFO 로그 한 줄뿐). 선택지: (1) 현상 유지 + 기동 경고·문서 (추천 — 이번 구현) (2) 감사 기록 실패 시 조회도 실패(fail-closed, account-jdbc 감사 활성 필수) — 더 엄격하나 감사 저장소 장애가 관리자 업무를 막음.
- [B6] 대상 계정이 지워지면 감사 행의 detail(by)이 scrub 되어 '누가 봤는지'가 사라짐(기존 account 이벤트와 같은 동작). 선택지: (1) 문서화한 한계로 둠 (추천) (2) 이 이벤트 종류만 scrub 에서 보존(account-jdbc 변경).
- [F1/F2 수정 한도 소진] 3회차 확인리뷰에 Important 1건 남음 — 댓글 첫 로드 실패 상태에서 선택기가 기기 기억으로 자동 채운 뒤 재시도로 스레드 프로필이 뒤늦게 오면 확인 없이 다른 프로필로 쓰게 됨(packages/board/src/authorship.ts:68-74, packages/author-profile/src/AuthorPickerView.tsx:87-101). 추천 수정: 실패 상태는 잠그지 않되 자동 채움만 건너뜀(`threadFailed` 인자 추가). 선택지: (1) 한 바퀴 더 허용 (추천) — 범위 작고 실제 연결 누출 경로 (2) 알려진 한계로 문서화하고 둠. Minor(Esc preventDefault, defaultPrevented 분기 스토리, rename.reset 부작용, 낡은 주석 4곳, 스토리 200줄 초과)도 같이 남음.
(없음)
