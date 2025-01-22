package org.abimon.eternalJukebox.objects

import java.io.Reader

object BasicStringSubstitutor {
    private val SUBSTITUTION_REGEX: Regex = "\\$\\{((?<source>[\\w_]+)(?::-(?<default>[^:]+))?:)?(?<value>[^}]+)}".toRegex()

    public fun preprocess(result: MatchResult): String {
        val source = result.groups["source"]
        val default = result.groups["default"]?.value
        val value = result.groups["value"]
            ?: throw IllegalArgumentException("Invalid string substitution (${result.value}) - no value provided")

        if (source == null) {
            throw IllegalArgumentException("Invalid string substitution (${result.value}) - no source provided")
        }

        when (source.value) {
            "env" -> return requireNotNull(
                System.getenv(value.value) ?: default
            ) { "Invalid string substitution (${result.value}) - environment variable ${value.value} is not set" }

            "prop" -> return requireNotNull(
                System.getProperty(value.value) ?: default
            ) { "Invalid string substitution (${result.value}) - system property ${value.value} is not set" }

            else -> throw IllegalArgumentException("Invalid string substitution (${result.value}) - invalid source ${source.value}")
        }
    }

    public fun preprocess(reader: Reader): String {
        val builder = StringBuilder()

        reader.useLines { seq ->
            seq.forEach { line ->
                builder.appendLine(SUBSTITUTION_REGEX.replace(line, this::preprocess))
            }
        }

        return builder.toString()
    }
}