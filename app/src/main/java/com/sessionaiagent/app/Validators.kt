package com.sessionaiagent.app

object Validators {
    fun hostError(s: String): String? = when {
        s.isBlank() -> "Enter your server IP or hostname"
        s.contains(' ') -> "Hostname cannot contain spaces"
        else -> null
    }

    fun portError(s: String): String? {
        val p = s.toIntOrNull() ?: return "Enter a port number"
        return if (p in 1..65535) null else "Port must be 1-65535"
    }

    fun userError(s: String): String? =
        if (s.isBlank()) "Enter the SSH username (usually root)" else null

    fun passwordError(s: String): String? =
        if (s.isEmpty()) "Enter the SSH password" else null

    fun mnemonicError(s: String): String? {
        val words = s.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "Enter your Session recovery password"
        return if (words.size == 13) null else "Recovery password must be 13 words (you entered ${words.size})"
    }

    fun ownerIdError(s: String): String? {
        val v = s.trim()
        if (v.isEmpty()) return "Enter your Session ID"
        return if (v.matches(Regex("[0-9a-fA-F]{66}"))) null else "Session ID must be 66 hex characters"
    }

    fun apiKeyError(s: String): String? {
        val v = s.trim()
        if (v.isEmpty()) return "Enter your OpenCode Go API key"
        return if (v.length >= 20) null else "This API key looks too short"
    }
}
