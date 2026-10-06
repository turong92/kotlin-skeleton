package dev.sumin.skeleton.legal

import java.util.concurrent.ConcurrentHashMap

class FakeLedger : LegalLedger {
    val rows = ConcurrentHashMap<Triple<String, String, String>, LedgerEntry>()

    override fun find(type: String, version: String, locale: String) = rows[Triple(type, version, locale)]

    override fun all() = rows.values.toList()

    override fun append(entry: LedgerEntry): Boolean = rows.putIfAbsent(Triple(entry.type, entry.version, entry.locale), entry) == null
}
