package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.LoginOutcome
import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.protocol.BinaryRequestBudget
import io.github.thatsfguy.meshcore.protocol.CliExchange
import io.github.thatsfguy.meshcore.protocol.CliResend
import io.github.thatsfguy.meshcore.protocol.RepeaterStatus
import io.github.thatsfguy.meshcore.protocol.SendRetry
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * Repeater admin for a platform that cannot see Kotlin default
 * arguments. Swift would otherwise have to restate every default the
 * engine chose — retry counts, budgets, which CLI reads may be resent —
 * and a restated default is a default that drifts. This only forwards,
 * with the engine's own values.
 */
class RepeaterAdmin(private val engine: MeshCoreEngine) {

    /** Sign in with the login retry ladder (a refusal stops it at once). */
    suspend fun signIn(keyHex: String, password: String): LoginOutcome {
        val key = hexToBytesOrNull(keyHex) ?: return LoginOutcome.NoAnswer
        return engine.sendLoginWithRetry(key, password, SendRetry.DEFAULT_MAX_ATTEMPTS, true)
    }

    suspend fun status(keyHex: String): RepeaterStatus? {
        val key = hexToBytesOrNull(keyHex) ?: return null
        return engine.repeaterStatus(key, BinaryRequestBudget.MAX_BUDGET_MS, null)
    }

    /** One CLI command; the reply text, or null on silence. Admin only — see CLAUDE.md. */
    suspend fun cli(keyHex: String, command: String): String? {
        val key = hexToBytesOrNull(keyHex) ?: return null
        val sends = if (CliExchange.isRead(command)) CliResend.MAX_SENDS else 1
        return engine.sendCliAndAwaitReply(key, command, 15_000, sends, CliResend.RESEND_AFTER_MS)
    }

    companion object {
        private val PRV_KEY = Regex("""(set\s+prv\.key\s+)\S+""", RegexOption.IGNORE_CASE)
        private val PASSWORD = Regex("""(password\s+)\S+""", RegexOption.IGNORE_CASE)
        private val LONG_HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")

        /**
         * A console line as it is SHOWN. The console is in memory only,
         * but a screen is screenshotted: a private key or password typed
         * into `set …` must not sit in the scrollback. Same patterns as
         * Android's diagnostics redaction.
         */
        fun displayed(line: String): String = line
            .replace(PRV_KEY) { "${it.groupValues[1]}[REDACTED]" }
            .replace(PASSWORD) { "${it.groupValues[1]}[REDACTED]" }
            .replace(LONG_HEX, "[HEX-REDACTED]")
    }
}
