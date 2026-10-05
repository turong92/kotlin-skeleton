package dev.sumin.skeleton.common.deploy

import org.springframework.boot.context.properties.ConfigurationProperties

/** 배포 스위치 옵션. 스위치 값 자체는 `skeleton.env` ([DeployContext.PROPERTY]) — 이 접두사 밖의 한 칸짜리 키다. */
@ConfigurationProperties("skeleton.deploy")
data class DeployGuardProperties(
    /** true 면 `skeleton.env` 가 비었을 때 기동 실패. 기본은 꺼져 있다(옵트인) */
    val requireEnv: Boolean = false,
)
