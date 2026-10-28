package dev.sumin.skeleton.common.authz

/**
 * 역할 위계(`includes`)의 펼침과 순환 찾기. 위가 아래의 권한을 모두 물려받는다(Spring Security `RoleHierarchy` 의 뜻) — 전이적이고, 순환은 허용하지 않는다.
 */
object RoleClosure {
    /** 펼침의 깊이 상한(`includes` 를 따라가는 걸음 수). 순환 검사를 통과한 표라면 닿지 않는다 — 손으로 만진 표가 요청을 붙잡지 않게 하는 안전망 */
    const val MAX_DEPTH = 8

    /** [roles] 와, 거기서 `includes` 를 최대 [maxDepth] 걸음 따라 닿는 모든 역할(자기 포함). 방문 집합이 있어 순환이 있어도 끝난다 */
    fun expand(roles: Set<String>, includesOf: (String) -> Set<String>, maxDepth: Int = MAX_DEPTH): Set<String> {
        val seen = LinkedHashSet(roles)
        var frontier: Collection<String> = roles
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth) {
            val next = LinkedHashSet<String>()
            frontier.forEach { role -> includesOf(role).forEach { if (seen.add(it)) next.add(it) } }
            frontier = next
            depth++
        }
        return seen
    }

    /** 위계 그래프(역할 → 물려받는 역할들)에서 순환 하나를 `[A, B, A]` 꼴로 돌려준다. 없으면 null. 이름 순으로 훑어 결과가 늘 같다 */
    fun cycleOf(graph: Map<String, Set<String>>): List<String>? {
        val done = HashSet<String>()
        val path = ArrayList<String>()
        val onPath = HashSet<String>()

        fun visit(role: String): List<String>? {
            if (role in onPath) return path.subList(path.indexOf(role), path.size) + role
            if (!done.add(role)) return null
            path.add(role)
            onPath.add(role)
            graph[role].orEmpty().sorted().forEach { next -> visit(next)?.let { return it } }
            path.removeAt(path.size - 1)
            onPath.remove(role)
            return null
        }
        graph.keys.sorted().forEach { visit(it)?.let { cycle -> return cycle } }
        return null
    }
}
