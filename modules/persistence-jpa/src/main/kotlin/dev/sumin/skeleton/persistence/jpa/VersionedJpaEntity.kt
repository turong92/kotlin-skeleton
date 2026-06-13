package dev.sumin.skeleton.persistence.jpa

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Version

@MappedSuperclass
abstract class VersionedJpaEntity : BaseJpaEntity() {
    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
}
