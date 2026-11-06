package dev.sumin.skeleton.common.upload

/**
 * 이 계정이 이 업로드 키의 주인인가 - 업로드 키를 자기 표에 저장하는 모듈(예: `account` 의 프로필 사진)이 받을 키를 검사하려고 쓴다.
 * `storage` 의 `StorageKeyOwnership` 이 이것을 확장하므로(`key-owner` 방식에 맞는 구현이 빈으로 선다) 소유자 쪽 모듈이 `storage` 를 컴파일에 끌어오지 않는다 -
 * `GuestUploadKeys` 와 같은 방식이다. 이 빈이 없으면 그 기능은 꺼진 채다.
 */
fun interface UploadKeyOwnership {
    fun owns(accountId: String, key: String): Boolean
}
