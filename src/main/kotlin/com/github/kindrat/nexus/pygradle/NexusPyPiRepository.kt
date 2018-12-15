@file:Suppress("UnstableApiUsage")

package com.github.kindrat.nexus.pygradle

import org.gradle.api.Action
import org.gradle.api.ActionConfiguration
import org.gradle.api.InvalidUserDataException
import org.gradle.api.artifacts.ComponentMetadataSupplier
import org.gradle.api.artifacts.repositories.AuthenticationContainer
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor
import org.gradle.api.internal.DefaultActionConfiguration
import org.gradle.api.internal.FeaturePreviews
import org.gradle.api.internal.InstantiatorFactory
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory
import org.gradle.api.internal.artifacts.ivyservice.IvyContextManager
import org.gradle.api.internal.artifacts.ivyservice.IvyContextualMetaDataParser
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.IvyModuleDescriptorConverter
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.IvyXmlModuleDescriptorParser
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.MetaDataParser
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.ModuleMetadataParser
import org.gradle.api.internal.artifacts.repositories.DefaultIvyArtifactRepository
import org.gradle.api.internal.artifacts.repositories.layout.AbstractRepositoryLayout
import org.gradle.api.internal.artifacts.repositories.metadata.*
import org.gradle.api.internal.artifacts.repositories.resolver.ExternalRepositoryResourceAccessor
import org.gradle.api.internal.artifacts.repositories.resolver.IvyResolver
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransport
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransportFactory
import org.gradle.api.internal.file.FileResolver
import org.gradle.internal.Factory
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.MutableIvyModuleResolveMetadata
import org.gradle.internal.reflect.Instantiator
import org.gradle.internal.resource.local.FileResourceRepository
import org.gradle.internal.resource.local.FileStore
import org.gradle.internal.resource.local.LocallyAvailableResourceFinder
import org.gradle.internal.service.DefaultServiceRegistry
import java.net.URI
import java.util.*
import java.util.Collections.unmodifiableList

class NexusPyPiRepository(
        fileResolver: FileResolver,
        private val repositoryTransportFactory: RepositoryTransportFactory,
        private val resourceFinder: LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata>,
        private val artifactFileStore: FileStore<ModuleComponentArtifactIdentifier>,
        private val fileStore: FileStore<String>,
        authenticationContainer: AuthenticationContainer,
        private val ivyContextManager: IvyContextManager,
        private val moduleIdentifierFactory: ImmutableModuleIdentifierFactory,
        private val instantiatorFactory: InstantiatorFactory,
        private val fileResourceRepository: FileResourceRepository,
        private val metadataParser: ModuleMetadataParser,
        featurePreviews: FeaturePreviews,
        private val metadataFactory: IvyMutableModuleMetadataFactory
) : DefaultIvyArtifactRepository(
        fileResolver, repositoryTransportFactory, resourceFinder, artifactFileStore, fileStore,
        authenticationContainer, ivyContextManager, moduleIdentifierFactory, instantiatorFactory,
        fileResourceRepository, metadataParser, featurePreviews, metadataFactory
) {
    private val metadataSources = NexusMetadataSources()
    private var componentMetadataSupplierClass: Class<out ComponentMetadataSupplier>? = null
    private var componentMetadataSupplierParams: Array<Any>? = null

    init {
        metadataSources.setDefaults(featurePreviews)
    }

    override fun setMetadataSupplier(ruleClass: Class<out ComponentMetadataSupplier>) {
        componentMetadataSupplierClass = ruleClass
        componentMetadataSupplierParams = NO_PARAMS
    }


    override fun metadataSources(configureAction: Action<in IvyArtifactRepository.MetadataSources>) {
        metadataSources.reset()
        configureAction.execute(metadataSources)
    }

    override fun setMetadataSupplier(rule: Class<out ComponentMetadataSupplier>,
                                     configureAction: Action<in ActionConfiguration>) {
        val configuration = DefaultActionConfiguration()
        configureAction.execute(configuration)
        componentMetadataSupplierClass = rule
        componentMetadataSupplierParams = configuration.params
    }

    override fun createRealResolver(): IvyResolver {
        val uri: URI? = url

        val layoutField = DefaultIvyArtifactRepository::class.java.getDeclaredField("layout")
        layoutField.isAccessible = true
        val layout = layoutField.get(this) as AbstractRepositoryLayout

        val schemes = LinkedHashSet<String>()

        val patterns = DefaultIvyArtifactRepository::class.java.getDeclaredField("additionalPatternsLayout")
        patterns.isAccessible = true
        val additionalLayout = patterns.get(this) as AbstractRepositoryLayout

        layout.addSchemes(uri, schemes)
        additionalLayout.addSchemes(uri, schemes)

        val resolver = createResolver(schemes)

        if (uri != null) {
            resolver.addArtifactLocation(uri, "")
            resolver.addDescriptorLocation(uri, "")
            resolver.isM2compatible = true
        }

        layout.apply(uri, resolver)
        additionalLayout.apply(uri, resolver)

        return resolver
    }

    private fun createResolver(schemes: Set<String>): IvyResolver {
        if (schemes.isEmpty()) {
            throw InvalidUserDataException("You must specify a base url or at least one artifact pattern")
        }

        return createResolver(repositoryTransportFactory.createTransport(schemes, name, configuredAuthentication))
    }

    private fun createResolver(transport: RepositoryTransport): IvyResolver {
        val instantiator = createDependencyInjectingInstantiator(transport)
        return NexusPyPiResolver(name, transport, resourceFinder, resolve.isDynamicMode,
                artifactFileStore, moduleIdentifierFactory, createComponentMetadataSupplierFactory(instantiator),
                createMetadataSources())
    }

    /**
     * Creates a service registry giving access to the services we want to expose to rules and returns an
     * instantiator that
     * uses this service registry.
     *
     * @param transport the transport used to create the repository accessor
     * @return a dependency injecting instantiator, aware of services we want to expose
     */
    private fun createDependencyInjectingInstantiator(transport: RepositoryTransport): Instantiator {
        val registry = DefaultServiceRegistry()

        registry.addProvider(object : Any() {
            @Suppress("unused")
            fun createResourceAccessor(): RepositoryResourceAccessor {
                return createRepositoryAccessor(transport)
            }
        })
        return instantiatorFactory.inject(registry)
    }

    private fun createRepositoryAccessor(transport: RepositoryTransport): RepositoryResourceAccessor {
        return ExternalRepositoryResourceAccessor(url, transport.resourceAccessor, fileStore)
    }

    private fun createComponentMetadataSupplierFactory(instantiator: Instantiator): Factory<ComponentMetadataSupplier> {
        componentMetadataSupplierClass ?: return NO_METADATA_SUPPLIER
        return Factory { instantiator.newInstance(componentMetadataSupplierClass, componentMetadataSupplierParams) }
    }

    private fun createMetadataSources(): ImmutableMetadataSources {
        val sources = ArrayList<MetadataSource<*>>()
        if (metadataSources.gradleMetadata) {
            sources.add(DefaultGradleModuleMetadataSource(metadataParser, metadataFactory, true))
        }

        //FIXME
        if (metadataSources.ivyDescriptor) {
            sources.add(DefaultIvyDescriptorMetadataSource(IvyMetadataArtifactProvider.INSTANCE,
                    createIvyDescriptorParser(), fileResourceRepository, moduleIdentifierFactory))
        }

        if (metadataSources.artifact) {
            sources.add(DefaultArtifactMetadataSource(metadataFactory))
        }

        return DefaultImmutableMetadataSources(unmodifiableList<MetadataSource<*>>(sources))
    }

    private fun createIvyDescriptorParser(): MetaDataParser<MutableIvyModuleResolveMetadata> {
        val converter = IvyModuleDescriptorConverter(moduleIdentifierFactory)
        val parser = IvyXmlModuleDescriptorParser(
                converter, moduleIdentifierFactory, fileResourceRepository, metadataFactory)
        return IvyContextualMetaDataParser(ivyContextManager, parser)
    }

    companion object {
        val NO_METADATA_SUPPLIER: Factory<ComponentMetadataSupplier> = Factory { null }
        val NO_PARAMS: Array<Any>? = emptyArray()
        val PYPI_PULL_PATTERN = "/packages/[nexus_module]/[revision]/[module]-[revision].[ext]"
    }
}
