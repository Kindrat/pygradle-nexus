package com.github.kindrat.nexus.pygradle

import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.internal.FeaturePreviews
import org.gradle.api.internal.FeaturePreviews.Feature.GRADLE_METADATA

class NexusMetadataSources : IvyArtifactRepository.MetadataSources {
    var gradleMetadata: Boolean = false
    var ivyDescriptor: Boolean = false
    var artifact: Boolean = false

    internal fun setDefaults(featurePreviews: FeaturePreviews) {
        ivyDescriptor()
        if (featurePreviews.isFeatureEnabled(GRADLE_METADATA)) {
            gradleMetadata()
        } else {
            artifact()
        }
    }

    internal fun reset() {
        gradleMetadata = false
        ivyDescriptor = false
        artifact = false
    }

    override fun artifact() {
        artifact = true
    }

    override fun gradleMetadata() {
        gradleMetadata = true
    }

    override fun ivyDescriptor() {
        ivyDescriptor = true
    }
}
