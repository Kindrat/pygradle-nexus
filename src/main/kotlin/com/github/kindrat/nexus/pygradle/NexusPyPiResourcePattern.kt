package com.github.kindrat.nexus.pygradle

import org.gradle.api.internal.artifacts.repositories.resolver.M2ResourcePattern
import java.net.URI

class NexusPyPiResourcePattern(baseUri: URI, pattern: String) : M2ResourcePattern(baseUri, pattern) {

    override fun substituteTokens(pattern: String, attributes: MutableMap<String, String>): String {
        val module = attributes["module"]
        if (module != null) {
            attributes["nexus_module"] = module.replace(".", "-").toLowerCase()
        }

        return super.substituteTokens(pattern, attributes)
    }

}
