/*
 * Copyright (C) 2020 Bosch.IO GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.ossreviewtoolkit.reporter.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

import okhttp3.OkHttpClient

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * A project's ACL is a map of group names to roles, see
 * https://github.com/amzn/oss-attribution-builder/issues/31#issuecomment-568004235
 * and
 * https://github.com/amzn/oss-attribution-builder/blob/8eda70c/docs/openapi.yaml#L89-L96.
 */
typealias ProjectAcl = Map<String, AmazonOssAttributionBuilderService.Role>

/**
 * Interface for the Amazon OSS Attribution Builder REST API, based on code generated by https://app.quicktype.io/
 * from https://github.com/amzn/oss-attribution-builder/blob/master/docs/openapi.yaml.
 */
interface AmazonOssAttributionBuilderService {
    companion object {
        /**
         * Create an OSS Attribution Builder service instance for communicating with the given [server], optionally
         * using a pre-built OkHttp [client].
         */
        fun create(server: Server, client: OkHttpClient? = null): AmazonOssAttributionBuilderService {
            val retrofit = Retrofit.Builder()
                .apply { if (client != null) client(client) }
                .baseUrl(server.url)
                .addConverterFactory(JacksonConverterFactory.create(JsonMapper().registerKotlinModule()))
                .build()

            return retrofit.create(AmazonOssAttributionBuilderService::class.java)
        }
    }

    /**
     * See https://github.com/amzn/oss-attribution-builder/blob/8eda70c/docs/openapi.yaml#L7-L9.
     */
    enum class Server(val url: String) {
        DEFAULT("http://localhost:8000/api/v1/"),
        DEV("http://localhost:2424/api/v1/")
    }

    /**
     * See https://github.com/amzn/oss-attribution-builder/blob/8eda70c/docs/openapi.yaml#L217-L233.
     */
    data class NewProject(
        val title: String,
        val version: String,
        val description: String,
        val plannedRelease: String,
        val contacts: ProjectContacts,
        val acl: ProjectAcl,
        val metadata: ObjectNode
    )

    /**
     * https://github.com/amzn/oss-attribution-builder/blob/8eda70c/docs/openapi.yaml#L98-L105.
     */
    data class ProjectContacts(
        val legal: List<String>
    )

    /**
     * See https://github.com/amzn/oss-attribution-builder/blob/8eda70c/docs/openapi.yaml#L93-L96.
     */
    enum class Role(private val value: String) {
        OWNER("owner"),
        EDITOR("editor"),
        VIEWER("viewer");

        @JsonValue
        override fun toString() = value
    }

    /**
     * See https://github.com/amzn/oss-attribution-builder/blob/8eda70c/docs/openapi.yaml#L250-L254.
     */
    data class NewProjectResponse(
        val projectId: String
    )

    /**
     * See https://github.com/amzn/oss-attribution-builder/blob/8eda70c/docs/openapi.yaml#L325-L343.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class AttachPackage(
        val name: String,
        val version: String,
        val website: String, // This has to be a functioning URL.
        val copyright: String,
        val license: String?,
        val licenseText: String?,
        val usage: Usage,
        val packageId: String? = null // Either use the above *or* the ID of an existing package.
    )

    /**
     * See https://github.com/amzn/oss-attribution-builder/blob/59ae4f8/server/api/v1/projects/index.spec.ts#L381-L385.
     */
    data class Usage(
        val notes: String,
        val link: String,
        val modified: Boolean
    )

    /**
     * See https://github.com/amzn/oss-attribution-builder/blob/1248a95/server/api/v1/projects/index.ts#L250.
     */
    data class AttachPackageResponse(
        val packageId: String
    )

    /**
     * See https://github.com/amzn/oss-attribution-builder/blob/8eda70c/docs/openapi.yaml#L399-L405.
     */
    data class DocumentBuildResponse(
        val text: String,
        val documentId: Int
    )

    /**
     * See https://github.com/amzn/oss-attribution-builder/blob/8eda70c/docs/openapi.yaml#L128-L142.
     */
    data class AttributionDocument(
        val id: Int,
        val projectId: String,
        val projectVersion: String,
        val createdOn: String,
        val createdBy: String,
        val content: String
    )

    data class ErrorResponse(
        val error: String
    )

    /**
     * Create a new project in a running instance of the OSS Attribution builder.
     */
    @POST("projects/new")
    fun createNewProject(
        @Body newProject: NewProject,
        @Header("Authorization") credentials: String
    ): Call<NewProjectResponse>

    /**
     * Attach a new package to an existing project.
     */
    @POST("projects/{projectId}/attach")
    fun attachPackage(
        @Path("projectId") projectId: String,
        @Body newPackage: AttachPackage,
        @Header("Authorization") credentials: String
    ): Call<AttachPackageResponse>

    /**
     * Generate an attribution document and store it on the server.
     */
    @POST("projects/{projectId}/build")
    fun generateAttributionDoc(
        @Path("projectId") projectId: String,
        @Header("Authorization") credentials: String
    ): Call<DocumentBuildResponse>

    /**
     * Fetch a previously generated attribution document.
     */
    @GET("projects/{projectId}/docs/{documentId}")
    fun fetchAttributionDoc(
        @Path("projectId") projectId: String,
        @Path("documentId") documentId: String,
        @Header("Authorization") credentials: String
    ): Call<AttributionDocument>
}
