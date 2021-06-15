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

import java.util.LinkedList
import java.util.SortedSet

/**
 * Alias for a function that is used while navigating through the dependency graph to determine which dependencies
 * should be followed or skipped. The function is passed the current dependency node; it returns a flag whether this
 * dependency is matched or not.
 */
typealias DependencyMatcher = (DependencyNode) -> Boolean

/**
 * An interface allowing the navigation through the dependency information contained in an [OrtResult], independent of
 * the concrete storage representation.
 *
 * The dependencies detected by the analyzer are represented in an [OrtResult] in an optimized form which is not
 * accessible easily. To simplify dealing with dependencies, this interface offers functionality tailored towards the
 * typical use cases required by the single ORT components.
 */
@Suppress("TooManyFunctions")
interface DependencyNavigator {
    companion object {
        /**
         * A pre-defined [DependencyMatcher] that matches all dependencies. It can be used to traverse the whole
         * dependency graph.
         */
        val MATCH_ALL: DependencyMatcher = { true }

        /**
         * A pre-defined [DependencyMatcher] that matches only dependencies with a linkage indicating sub projects.
         */
        private val MATCH_SUB_PROJECTS: DependencyMatcher = { node ->
            node.linkage in PackageLinkage.PROJECT_LINKAGE
        }

        /**
         * Return a sorted set with all the [Identifier]s contained in the given map of scope dependencies. This is
         * useful if all dependencies are needed independent on the scope they belong to.
         */
        fun Map<String, Set<Identifier>>.collectDependencies(): SortedSet<Identifier> =
            flatMapTo(sortedSetOf()) { it.value }
    }

    /**
     * Return a set with the names of all the scopes defined for the given [project].
     */
    fun scopeNames(project: Project): Set<String>

    /**
     * Return a sequence with [DependencyNode]s for the direct dependencies for the given [project] and [scopeName].
     * From this sequence, the whole dependency tree of that scope can be traversed.
     */
    fun directDependencies(project: Project, scopeName: String): Sequence<DependencyNode>

    /**
     * Return a map with information of the dependencies of a [project] grouped by scopes. The resulting map has the
     * names of scopes as keys and a set with the identifiers of the dependencies found in this scope as values. With
     * [maxDepth] the depth of the dependency tree to be traversed can be restricted; negative values mean that there
     * is no restriction. Use the specified [matcher] to filter for specific dependencies.
     */
    fun scopeDependencies(
        project: Project,
        maxDepth: Int = -1,
        matcher: DependencyMatcher = MATCH_ALL
    ): Map<String, Set<Identifier>>

    /**
     * Return a set with the [Identifier]s of packages that are dependencies of package with the given [packageId] in
     * the given [project]. Starting from [project], the dependency graph is searched for the package in question;
     * then its dependencies are collected. As usual, it is possible to restrict the dependencies to be fetched with
     * [maxDepth] and [matcher].
     */
    fun packageDependencies(
        project: Project,
        packageId: Identifier,
        maxDepth: Int = -1,
        matcher: DependencyMatcher = MATCH_ALL
    ): Set<Identifier>

    /**
     * Return a map with the shortest paths to each dependency in all scopes of the given [project]. The path to a
     * dependency is defined by the nodes of the dependency tree that need to be passed to get to the dependency. For
     * direct dependencies the shortest path is empty. The resulting map has scope names as keys; the values are maps
     * with the shorted paths to all the dependencies contained in that scope.
     */
    fun getShortestPaths(project: Project): Map<String, Map<Identifier, List<Identifier>>> =
        getShortestPathForProject(scopeDependencies(project)) { directDependencies(project, it) }

    /**
     * Return the set of [Identifier]s that refer to sub-projects of the given [project].
     */
    fun collectSubProjects(project: Project): SortedSet<Identifier> =
        scopeDependencies(project, matcher = MATCH_SUB_PROJECTS).collectDependencies()

    /**
     * Determine the map of shortest paths for all the dependencies of a project, given its map of [scopeDependencies]
     * and a [scopeResolver] function that retrieves the direct dependency nodes of a scope.
     */
    private fun getShortestPathForProject(
        scopeDependencies: Map<String, Set<Identifier>>,
        scopeResolver: (String) -> Sequence<DependencyNode>
    ): Map<String, Map<Identifier, List<Identifier>>> =
        scopeDependencies.mapValues { (scope, dependencies) ->
            getShortestPathsForScope(scopeResolver(scope), dependencies)
        }

    /**
     * Determine the map of shortest paths for a specific scope given its direct dependency [nodes] and a set with
     * [allDependencies].
     */
    private fun getShortestPathsForScope(
        nodes: Sequence<DependencyNode>,
        allDependencies: Set<Identifier>
    ): Map<Identifier, List<Identifier>> {
        data class QueueItem(
            val pkgRef: DependencyNode,
            val parents: List<Identifier>
        )

        val remainingIds = allDependencies.toMutableSet()
        val queue = LinkedList<QueueItem>()
        val result = sortedMapOf<Identifier, List<Identifier>>()

        nodes.forEach { queue.offer(QueueItem(it, emptyList())) }

        while (queue.isNotEmpty()) {
            val item = queue.poll()
            if (item.pkgRef.id in remainingIds) {
                result[item.pkgRef.id] = item.parents
                remainingIds -= item.pkgRef.id
            }

            val newParents = item.parents + item.pkgRef.id
            item.pkgRef.visitDependencies { dependencyNodes ->
                dependencyNodes.forEach { node -> queue.offer(QueueItem(node, newParents)) }
            }
        }

        require(remainingIds.isEmpty()) {
            "Could not find the shortest path for these dependencies: ${remainingIds.joinToString()}"
        }

        return result
    }
}
