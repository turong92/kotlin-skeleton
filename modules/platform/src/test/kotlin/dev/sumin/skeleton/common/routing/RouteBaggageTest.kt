package dev.sumin.skeleton.common.routing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class RouteBaggageTest {
    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    private val alice = Route(listOf(RouteEntry("orders", "alice")))

    @Test
    fun readsRouteAmongOtherMembers() {
        val parsed = RouteBaggage.parseHeader(listOf("userId=1, route=orders:alice;p=1", "other=x"))

        assertThat(parsed).isEqualTo(RouteParse.Valid(alice))
    }

    @Test
    fun absentWhenNoRouteMember() {
        assertThat(RouteBaggage.parseHeader(listOf("userId=1"))).isEqualTo(RouteParse.Absent)
        assertThat(RouteBaggage.parseHeader(emptyList())).isEqualTo(RouteParse.Absent)
    }

    @Test
    fun rejectsUrlLikeTarget() {
        assertThat(RouteBaggage.parseValue("orders:https://evil.example.com")).isNull()
        assertThat(RouteBaggage.parseValue("orders:../x")).isNull()
        assertThat(RouteBaggage.parseValue("Orders:a")).isNull()
    }

    @Test
    fun malformedHeaderValueIsMalformedNotAbsent() {
        assertThat(RouteBaggage.parseHeader(listOf("route=orders:https://evil.example.com")))
            .isEqualTo(RouteParse.Malformed)
    }

    @Test
    fun rejectsTooManyOrTooLong() {
        val nine = (1..9).joinToString("|") { "s$it:t" }
        val eight = (1..8).joinToString("|") { "s$it:t" }
        val long = "orders:" + "a".repeat(250)

        assertThat(RouteBaggage.parseValue(nine)).isNull()
        assertThat(RouteBaggage.parseValue(eight)).isNotNull()
        assertThat(long.length).isGreaterThan(256)
        assertThat(RouteBaggage.parseValue(long)).isNull()
        assertThat(RouteBaggage.parseValue("orders:a|orders:b")).isNull()
    }

    @Test
    fun percentDecodes() {
        assertThat(RouteBaggage.parseHeader(listOf("route=orders%3Aalice"))).isEqualTo(RouteParse.Valid(alice))
        assertThat(RouteBaggage.parseHeader(listOf("route=orders%zz"))).isEqualTo(RouteParse.Malformed)
    }

    @Test
    fun twoRouteMembersAreAmbiguous() {
        assertThat(RouteBaggage.parseHeader(listOf("route=orders:alice", "route=orders:bob")))
            .isEqualTo(RouteParse.Malformed)
    }

    @Test
    fun formatsAndLooksUpTargets() {
        val route = Route(listOf(RouteEntry("orders", "alice"), RouteEntry("api", "candidate")))

        assertThat(route.format()).isEqualTo("orders:alice|api:candidate")
        assertThat(route.targetOf("api")).isEqualTo("candidate")
        assertThat(route.targetOf("payments")).isNull()
    }

    @Test
    fun withRouteReplacesOnlyRouteMember() {
        assertThat(RouteBaggage.withRoute("a=1,route=x:y", alice)).isEqualTo("a=1,route=orders:alice")
        assertThat(RouteBaggage.withRoute(null, alice)).isEqualTo("route=orders:alice")
    }

    @Test
    fun withoutRouteDropsOnlyRouteMember() {
        assertThat(RouteBaggage.withoutRoute("a=1,route=orders:candidate")).isEqualTo("a=1")
        assertThat(RouteBaggage.withoutRoute("route=orders:candidate")).isNull()
        assertThat(RouteBaggage.withoutRoute("a=1")).isEqualTo("a=1")
        assertThat(RouteBaggage.withoutRoute(null)).isNull()
    }

    @Test
    fun restoreSetsAndRestoresMdc() {
        MDC.put("route", "a:b")

        RouteBaggage.restore("orders:alice").use {
            assertThat(RouteBaggage.current()).isEqualTo(alice)
        }

        assertThat(MDC.get("route")).isEqualTo("a:b")
    }

    @Test
    fun restoreClearsWhenNothingBefore() {
        RouteBaggage.restore("orders:alice").use { }

        assertThat(MDC.get("route")).isNull()
    }

    @Test
    fun restoreLeavesMdcAloneForMalformedValue() {
        MDC.put("route", "a:b")

        RouteBaggage.restore("orders:https://evil.example.com").use {
            assertThat(MDC.get("route")).isEqualTo("a:b")
        }

        assertThat(MDC.get("route")).isEqualTo("a:b")
    }
}
