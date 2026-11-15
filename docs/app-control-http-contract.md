# app-control — 클라이언트 계약

`@skeleton/app-control`(웹) · `skeleton_app_control`(모바일)이 겨냥하는 모듈 수준 계약. 서버 쪽: [modules/app-control](modules/app-control.md).

## `GET /api/v1/app/config`

`skeleton.app-control.config.enabled=true` 일 때만 있다(꺼져 있으면 404). **익명**이고 **점검 중에도 열려 있다**. 응답은 표준 envelope `{ value, meta }`:

```json
{
  "value": {
    "serverTime": "2026-11-16T09:00:00Z",
    "maintenance": { "enabled": true, "message": "DB 이전 작업 중이에요", "endsAt": "2026-11-16T10:00:00Z" },
    "flags": { "new-checkout": true, "beta-badge": false },
    "platforms": {
      "android": { "minVersion": "2.0", "recommendedVersion": "2.3.1", "storeUrl": "https://play.google.com/store/apps/details?id=com.example.app" },
      "ios": { "minVersion": "2.0", "recommendedVersion": null, "storeUrl": "https://apps.apple.com/app/id0000000000" },
      "web": { "minVersion": null, "recommendedVersion": "2026.11.16", "storeUrl": null }
    }
  }
}
```

- `maintenance.message` · `endsAt` 는 점검 중일 때만 채워진다(아니면 null). `serverTime` 은 `endsAt` 를 기기 시계와 맞춰 볼 때 쓴다.
- `flags` 에는 운영자가 **클라이언트에 보이기로 한** 스위치만 키 → 켜짐으로 실린다. 키가 없으면 「운영자가 만든 적 없다」 — 클라이언트는 꺼짐으로 취급한다.
- `platforms.<이름>` 은 그 플랫폼의 규칙이 있을 때만 있다. 칸이 null 이면 그 규칙이 없다.

## 클라이언트가 하는 일

| 상태 | 판정 | 화면 |
|---|---|---|
| 점검 | `maintenance.enabled` | 점검 화면(문구 · 끝 예정). 계속 열려 있는 동안 주기적으로 다시 읽어 풀리면 돌아간다 |
| 강제 업데이트 | 내 버전 `<` `minVersion` | 닫을 수 없는 화면 + 스토어 버튼(`storeUrl`). 웹은 새로고침 |
| 권장 업데이트 | `minVersion` ≤ 내 버전 `<` `recommendedVersion` | 닫을 수 있는 안내(닫으면 이 권장 버전은 다시 묻지 않는다) |

- 읽는 때: 시작할 때 · 앞으로 돌아올 때(모바일) / 창에 다시 올 때(웹).
- **버전은 숫자 칸으로 비교한다** — `1.10` 은 `1.9` 보다 크고 `1.2` 는 `1.2.0` 과 같다. 글자 비교 금지. 내 버전이 `1`~`4` 칸 숫자가 아니면(예: `-beta` 접미사) 접미사를 떼고 비교하고, 그래도 읽을 수 없으면 업데이트를 강요하지 않는다.
- 설정을 읽지 못하면(네트워크 · 5xx) **막지 않는다** — 직전에 읽은 값이 있으면 그것을, 없으면 정상으로 본다. 점검 · 강제 업데이트는 서버가 「그렇다」고 말했을 때만 화면을 바꾼다.
- 어느 요청이든 `503 APP_CONTROL.MAINTENANCE`(+ `Retry-After`)를 받으면 점검으로 본다. 설정 경로만 통과시키므로 다른 요청은 점검 중에 실패한다.
- 꺼진 기능의 경로는 `404 APP_CONTROL.FEATURE_DISABLED` — 클라이언트는 `flags` 로 먼저 화면을 감춘다.

## 운영자 경로

`/api/v1/admin/app-control` — 권한 · 요청 · 응답 모양은 [modules/app-control](modules/app-control.md) 의 표.
