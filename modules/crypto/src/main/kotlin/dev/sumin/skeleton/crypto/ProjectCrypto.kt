package dev.sumin.skeleton.crypto

import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectScoped
import java.util.Optional

/**
 * 프로젝트 모드의 [TextEncryptor]: 부를 때 지금 프로젝트(`ProjectContext`)의 키로 넘긴다.
 * 변환기 · [OpaqueUrlTokenCodec] 이 이 빈 하나를 지나므로 따로 고칠 곳이 없다. 프로젝트 문맥이 없으면 `NoProjectContextException`,
 * 키가 없는 프로젝트는 그 프로젝트 이름이 담긴 [CryptoException] 이다.
 */
class ProjectRoutedTextEncryptor(
    private val parts: ProjectScoped<Optional<TextEncryptor>>,
) : TextEncryptor {
    override fun encrypt(plainText: String): String = current().encrypt(plainText)

    override fun decrypt(cipherText: String): String = current().decrypt(cipherText)

    private fun current(): TextEncryptor {
        val project = ProjectContext.current()
        return parts.of(project).orElseThrow { CryptoException("project '${project.id}' has no crypto keys (skeleton.projects.${project.id}.config.crypto.keys.<id>).") }
    }

    companion object {
        /**
         * 서비스 중인 프로젝트마다 자기 `crypto.*` (공통 위에 덮어)로 암호기를 만들고, **프로젝트끼리 같은 키 바이트를 쓰면 기동 실패**로 막는다.
         * 키가 하나도 없는 프로젝트는 암호기가 없다(쓰는 순간 그 프로젝트 이름의 예외).
         */
        fun create(projects: ProjectRegistry): ProjectRoutedTextEncryptor {
            val parts = projects.scopedFrom("skeleton.crypto", CryptoProperties::class.java) { project, p -> encryptorOf(project, p) }
            val keyOwners = mutableMapOf<List<Byte>, MutableList<Pair<String, String>>>()
            projects.active.forEach { project ->
                val p = projects.bind(project, "skeleton.crypto", CryptoProperties::class.java)
                p.keys.filterValues { it.isNotBlank() }.forEach { (id, value) ->
                    val bytes = try {
                        AesGcmKey.fromBase64(value).encoded.toList()
                    } catch (ex: RuntimeException) {
                        throw IllegalStateException("project '${project.id}': skeleton.crypto.keys.$id is not usable: ${ex.message}", ex)
                    }
                    keyOwners.getOrPut(bytes) { mutableListOf() } += project.id to id
                }
                parts.of(project)
            }
            keyOwners.values.firstOrNull { owners -> owners.map { it.first }.distinct().size > 1 }?.let { owners ->
                error(
                    "Projects ${owners.map { it.first }.distinct().joinToString { "'$it'" }} use the same key bytes (" +
                        owners.joinToString { "'${it.first}' keys.${it.second}" } + ") — every project needs its own skeleton.projects.<id>.config.crypto.keys.<id>",
                )
            }
            return ProjectRoutedTextEncryptor(parts)
        }

        private fun encryptorOf(project: Project, p: CryptoProperties): Optional<TextEncryptor> {
            val keys = p.keys.filterValues { it.isNotBlank() }
            if (keys.isEmpty()) return Optional.empty()
            return try {
                Optional.of(AesGcmTextEncryptor(p.primaryKeyId, keys.mapValues { (_, v) -> AesGcmKey.fromBase64(v) }))
            } catch (ex: RuntimeException) {
                throw IllegalStateException("project '${project.id}': crypto keys cannot be used: ${ex.message}", ex)
            }
        }
    }
}
