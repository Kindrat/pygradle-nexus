package com.github.kindrat.nexus.pygradle

import org.gradle.api.artifacts.ComponentMetadataSupplier
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory
import org.gradle.api.internal.artifacts.repositories.metadata.ImmutableMetadataSources
import org.gradle.api.internal.artifacts.repositories.metadata.IvyMetadataArtifactProvider
import org.gradle.api.internal.artifacts.repositories.resolver.IvyResolver
import org.gradle.api.internal.artifacts.repositories.resolver.ResourcePattern
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransport
import org.gradle.internal.Factory
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.resource.local.FileStore
import org.gradle.internal.resource.local.LocallyAvailableResourceFinder
import java.net.URI

class NexusPyPiResolver(
        name: String,
        transport: RepositoryTransport,
        resourceFinder: LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata>,
        dynamicResolve: Boolean,
        artifactFileStore: FileStore<ModuleComponentArtifactIdentifier>,
        moduleIdentifierFactory: ImmutableModuleIdentifierFactory,
        componentMetadataSupplierFactory: Factory<ComponentMetadataSupplier>,
        metadataSources: ImmutableMetadataSources
) : IvyResolver(
        name, transport, resourceFinder, dynamicResolve, artifactFileStore, moduleIdentifierFactory,
        componentMetadataSupplierFactory, metadataSources, IvyMetadataArtifactProvider.INSTANCE
) {

    override fun addArtifactLocation(baseUri: URI, pattern: String) {
        addArtifactPattern(toResourcePattern(baseUri, pattern))
    }

    override fun addDescriptorLocation(baseUri: URI, pattern: String) {
        addIvyPattern(toResourcePattern(baseUri, pattern))
    }

    private fun toResourcePattern(baseUri: URI, pattern: String) : ResourcePattern {
        return NexusPyPiResourcePattern(baseUri, pattern)
    }
}
