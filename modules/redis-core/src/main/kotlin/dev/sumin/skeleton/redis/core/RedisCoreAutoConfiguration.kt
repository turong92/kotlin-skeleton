package dev.sumin.skeleton.redis.core

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties

@AutoConfiguration
@EnableConfigurationProperties(RedisCoreProperties::class)
class RedisCoreAutoConfiguration
