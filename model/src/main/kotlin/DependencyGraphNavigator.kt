/*
 * Copyright (C) 2021 Bosch.IO GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.model

/**
 * A [DependencyNavigator] implementation based on the dependency graph format. It obtains the information about a
 * project's dependencies from the shared [DependencyGraph] stored in the [OrtResult].
 */
class DependencyGraphNavigator(ortResult: OrtResult) : DependencyNavigator {
    /** The map with shared dependency graphs from the associated result. */
    private val graphs: Map<String, DependencyGraph> =
        requireNotNull(ortResult.analyzer?.result?.dependencyGraphs?.takeIf { it.isNotEmpty() }) {
            "No dependency graph available to initialize DependencyGraphNavigator."
        }

    /**
     * A data structure allowing fast access to a specific [DependencyReference] based on its index and fragment.
     */
    private val graphDependencyRefMappings: Map<String, Array<MutableList<DependencyReference>>> by lazy {
        graphs.mapValues { it.value.dependencyRefMapping() }
    }

    override fun scopeNames(project: Project): Set<String> = project.scopeNames.orEmpty()

    override fun directDependencies(project: Project, scopeName: String): Sequence<DependencyNode> {
        val manager = project.managerName
        val graph = graphForManager(manager)
        val rootDependencies = graph.scopes[DependencyGraph.qualifyScope(project, scopeName)].orEmpty().map { root ->
            referenceFor(manager, root)
        }

        return dependenciesSequence(graph, rootDependencies)
    }

    override fun scopeDependencies(
        project: Project,
        maxDepth: Int,
        matcher: DependencyMatcher
    ): Map<String, Set<Identifier>> {
        val dependenciesMap = mutableMapOf<String, Set<Identifier>>()

        scopeNames(project).forEach { scope ->
            val dependencyIds = mutableSetOf<Identifier>()
            collectDependencies(directDependencies(project, scope), maxDepth, matcher, dependencyIds)
            dependenciesMap[scope] = dependencyIds
        }

        return dependenciesMap
    }

    override fun packageDependencies(
        project: Project,
        packageId: Identifier,
        maxDepth: Int,
        matcher: DependencyMatcher
    ): Set<Identifier> {
        val dependencies = mutableSetOf<Identifier>()

        fun traverse(node: DependencyNode) {
            if (node.id == packageId) {
                node.visitDependencies { collectDependencies(it, maxDepth, matcher, dependencies) }
            }

            node.visitDependencies { dependencies ->
                dependencies.forEach(::traverse)
            }
        }

        scopeNames(project).forEach { scope ->
            directDependencies(project, scope).forEach(::traverse)
        }

        return dependencies
    }

    /**
     * Return the [DependencyGraph] for the given [package manager][manager] or throw an exception if no such graph
     * exists.
     */
    private fun graphForManager(manager: String): DependencyGraph =
        requireNotNull(graphs[manager]) { "No DependencyGraph for package manager '$manager' available." }

    /**
     * Resolve a [DependencyReference] in the [DependencyGraph] for the specified [package manager][manager] with the
     * given [pkgIndex] and [fragment]. Throw an exception if no such reference can be found.
     */
    private fun referenceFor(manager: String, pkgIndex: Int, fragment: Int): DependencyReference =
        requireNotNull(graphDependencyRefMappings[manager])[pkgIndex].find { it.fragment == fragment }
            ?: throw IllegalArgumentException(
                "Could not resolve a DependencyReference for index = $pkgIndex and fragment $fragment."
            )

    /**
     * Resolve a [DependencyReference] in the [DependencyGraph] for the specified [package manager][manager] that
     * corresponds to the given [rootIndex]. Throw an exception if no such reference can be found.
     */
    private fun referenceFor(manager: String, rootIndex: RootDependencyIndex): DependencyReference =
        referenceFor(manager, rootIndex.root, rootIndex.fragment)
}

/**
 * An internal class supporting the efficient traversal of a structure of [DependencyReference]s.
 *
 * The idea behind this class is that only a single instance is created for traversing a collection of
 * [DependencyReference]s. Like a database cursor, this instance moves on to the next reference in the collection. It
 * implements the [DependencyNode] interface by delegating to the properties of the current [DependencyReference].
 * That way it is not necessary to wrap all the references in the collection to iterate over into adapter objects.
 */
private class DependencyRefCursor(
    /** The [DependencyGraph] that owns the references to traverse. */
    val graph: DependencyGraph,

    /** The [DependencyReference]s to traverse. */
    val references: Collection<DependencyReference> = emptyList(),

    /**
     * An optional initial value for the current reference. This is mainly used it this instance acts as an adapter
     * for a single [DependencyReference].
     */
    val initCurrent: DependencyReference? = null
) : DependencyNode {
    /** An [Iterator] for doing the actual traversal. */
    private val referencesIterator = references.iterator()

    /** Points to the current element of the traversal. */
    private var current: DependencyReference = initCurrent ?: referencesIterator.next()

    override val id: Identifier
        get() = graph.packages[current.pkg]

    override val linkage: PackageLinkage
        get() = current.linkage

    override val issues: List<OrtIssue>
        get() = current.issues

    override fun <T> visitDependencies(block: (Sequence<DependencyNode>) -> T): T =
        block(dependenciesSequence(graph, current.dependencies))

    override fun getStableReference(): DependencyNode = DependencyRefCursor(graph, initCurrent = current)

    /**
     * Return a sequence of [DependencyNode]s that is implemented based on this instance. This sequence allows access
     * to the properties of the [DependencyReference]s passed to this instance, although each sequence element is a
     * reference to *this*.
     */
    fun asSequence(): Sequence<DependencyNode> =
        generateSequence(this) {
            if (referencesIterator.hasNext()) {
                current = referencesIterator.next()
                this
            } else {
                null
            }
        }
}

/**
 * Return the name of the package manager that constructed this [Project]. This is required to find the
 * [DependencyGraph] for this project.
 */
private val Project.managerName: String
    get() = id.type

/**
 * Construct a data structure that allows fast access to all [DependencyReference]s contained in this
 * [DependencyGraph] based on its index and fragment. This structure is used to lookup specific references, typically
 * the entry points in the graph (i.e. the roots of the dependency trees referenced by the single scopes). The
 * structure is an array whose index corresponds to the index of the [DependencyReference]. Under an index multiple
 * references can be listed for the different fragments.
 */
private fun DependencyGraph.dependencyRefMapping(): Array<MutableList<DependencyReference>> {
    val refArray = Array<MutableList<DependencyReference>>(packages.size) { mutableListOf() }

    fun addReference(ref: DependencyReference) {
        refArray[ref.pkg] += ref
        ref.dependencies.forEach(::addReference)
    }

    scopeRoots.forEach(::addReference)
    return refArray
}

/**
 * Generate a sequence that allows accessing the given [dependencies] from the provided [graph] via the
 * [DependencyNode] interface.
 */
private fun dependenciesSequence(
    graph: DependencyGraph,
    dependencies: Collection<DependencyReference>
): Sequence<DependencyNode> =
    dependencies.takeIf { it.isNotEmpty() }?.let {
        DependencyRefCursor(graph, it).asSequence()
    } ?: emptySequence()

/**
 * Traverse the given [nodes] recursively up to the given [maxDepth], filter using [matcher] and adds all identifiers
 * found to [ids].
 */
private fun collectDependencies(
    nodes: Sequence<DependencyNode>,
    maxDepth: Int,
    matcher: DependencyMatcher,
    ids: MutableSet<Identifier>
) {
    if (maxDepth != 0) {
        nodes.forEach { node ->
            if (matcher(node)) {
                ids += node.id
            }

            node.visitDependencies { collectDependencies(it, maxDepth - 1, matcher, ids) }
        }
    }
}
