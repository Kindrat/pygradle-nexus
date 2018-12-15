@file:Suppress("UnstableApiUsage")

package com.github.kindrat.nexus.pygradle

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.AuthenticationContainer
import org.gradle.api.artifacts.repositories.IvyPatternRepositoryLayout
import org.gradle.api.internal.FeaturePreviews
import org.gradle.api.internal.InstantiatorFactory
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory
import org.gradle.api.internal.artifacts.dsl.DefaultRepositoryHandler
import org.gradle.api.internal.artifacts.ivyservice.IvyContextManager
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.ModuleMetadataParser
import org.gradle.api.internal.artifacts.repositories.DefaultBaseRepositoryFactory
import org.gradle.api.internal.artifacts.repositories.metadata.IvyMutableModuleMetadataFactory
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransportFactory
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.filestore.ivy.ArtifactIdentifierFileStore
import org.gradle.api.internal.model.NamedObjectInstantiator.INSTANCE
import org.gradle.authentication.Authentication
import org.gradle.internal.authentication.AuthenticationSchemeRegistry
import org.gradle.internal.authentication.DefaultAuthenticationContainer
import org.gradle.internal.authentication.DefaultAuthenticationSchemeRegistry
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.reflect.Instantiator
import org.gradle.internal.resource.cached.ExternalResourceFileStore
import org.gradle.internal.resource.local.FileResourceRepository
import org.gradle.internal.resource.local.LocallyAvailableResourceFinder
import javax.inject.Inject


class NexusPyPiPlugin @Inject constructor(
        private val instantiator: Instantiator,
        private val fileResolver: FileResolver,
        private val immutableAttributesFactory: ImmutableAttributesFactory,
        private val featurePreviews: FeaturePreviews,
        private val fileRepository: FileResourceRepository,
        private val instantiatorFactory: InstantiatorFactory,
        private val locallyAvailableResourceFinder: LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata>,
        private val moduleIdentifierFactory: ImmutableModuleIdentifierFactory,
        private val ivyContextManager: IvyContextManager,
        private val ivyMutableModuleMetadataFactory: IvyMutableModuleMetadataFactory,
        private val artifactIdentifierFileStore: ArtifactIdentifierFileStore,
        private val externalResourceFileStore: ExternalResourceFileStore,
        private val authenticationSchemeRegistry: AuthenticationSchemeRegistry,
        private val repositoryTransportFactory: RepositoryTransportFactory
) : Plugin<Project> {


    override fun apply(project: Project) {
        val handler = project.repositories as DefaultRepositoryHandler
        val moduleMetadataParser = ModuleMetadataParser(immutableAttributesFactory, moduleIdentifierFactory, INSTANCE)

        @Suppress("ObjectLiteralToLambda", "unused")
        fun DefaultBaseRepositoryFactory.nexusPypi() {
            val artifactRepository = instantiator.newInstance(
                    NexusPyPiRepository::class.java,
                    fileResolver,
                    repositoryTransportFactory,
                    locallyAvailableResourceFinder,
                    artifactIdentifierFileStore,
                    externalResourceFileStore,
                    createAuthenticationContainer(),
                    ivyContextManager,
                    moduleIdentifierFactory,
                    instantiatorFactory,
                    fileRepository,
                    moduleMetadataParser,
                    featurePreviews,
                    ivyMutableModuleMetadataFactory
            ) as NexusPyPiRepository

            artifactRepository.layout("pattern", object : Action<IvyPatternRepositoryLayout> {
                override fun execute(repositoryLayout: IvyPatternRepositoryLayout) {
                    repositoryLayout.artifact(NexusPyPiRepository.PYPI_PULL_PATTERN)
                    repositoryLayout.ivy(NexusPyPiRepository.PYPI_PULL_PATTERN)
                    repositoryLayout.setM2compatible(true)
                }
            })

            handler.addRepository(artifactRepository, "pypi")
        }
    }

    private fun createAuthenticationContainer(): AuthenticationContainer {
        val container = instantiator.newInstance(DefaultAuthenticationContainer::class.java, instantiator)
        val defaultAuthenticationSchemeRegistry = authenticationSchemeRegistry as DefaultAuthenticationSchemeRegistry

        for (e in defaultAuthenticationSchemeRegistry.getRegisteredSchemes().entries) {
            container.registerBinding<Authentication>(e.key as Class<Authentication>, e.value)
        }

        return container
    }

}
