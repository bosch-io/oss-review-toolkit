/*
 * Copyright (c) 2017-2018 HERE Europe B.V.
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

package com.here.ort.reporter.reporters

import com.here.ort.model.OrtResult
import com.here.ort.model.readValue
import com.here.ort.reporter.DefaultResolutionProvider
import com.here.ort.utils.safeDeleteRecursively

import io.kotlintest.Description
import io.kotlintest.TestResult

import io.kotlintest.shouldBe
import io.kotlintest.specs.WordSpec

import java.io.File

class NoticeReporterTest : WordSpec() {
    companion object {
        private fun readOrtResult(file: String) = File(file).readValue<OrtResult>()
    }

    private lateinit var tempDir: File

    override fun beforeTest(description: Description) {
        super.beforeTest(description)
        tempDir = createTempDir()
    }

    override fun afterTest(description: Description, result: TestResult) {
        tempDir.safeDeleteRecursively()
        super.afterTest(description, result)
    }

    init {
        "NoticeReporter" should {
            "generate the correct license notes" {
                val expectedResultFile = File("src/test/assets/NPM-is-windows-1.0.2-expected-NOTICE")
                val expectedText = expectedResultFile.readText()
                val ortResult = readOrtResult("src/test/assets/NPM-is-windows-1.0.2-scan-result.json")

                NoticeReporter().generateReport(ortResult, DefaultResolutionProvider(), tempDir)

                val resultFile = File(tempDir, "NOTICE")
                val actualText = resultFile.readText()

                actualText shouldBe expectedText
            }

            "contain all licenses without excludes" {
                val expectedResultFile = File("src/test/assets/npm-test-without-exclude-expected-NOTICE")
                val ortResult = readOrtResult("src/test/assets/npm-test-without-exclude-scan-results.yml")

                NoticeReporter().generateReport(ortResult, DefaultResolutionProvider(), tempDir)

                val resultFile = File(tempDir, "NOTICE")

                resultFile.readText() shouldBe expectedResultFile.readText()
            }

            "not contain licenses of excluded packages" {
                val expectedResultFile = File("src/test/assets/npm-test-with-exclude-expected-NOTICE")
                val ortResult = readOrtResult("src/test/assets/npm-test-with-exclude-scan-results.yml")

                NoticeReporter().generateReport(ortResult, DefaultResolutionProvider(), tempDir)

                val resultFile = File(tempDir, "NOTICE")

                resultFile.readText() shouldBe expectedResultFile.readText()
            }

            "evaluate the provided post-processing script" {
                val expectedResultFile = File("src/test/assets/post-processed-expected-NOTICE")
                val expectedText = expectedResultFile.readText()
                val ortResult = readOrtResult("src/test/assets/NPM-is-windows-1.0.2-scan-result.json")

                val postProcessingScript = """
                    headers = listOf("Header 1\n", "Header 2\n")
                    findings = noticeReport.findings.filter { (_, copyrights) -> copyrights.isEmpty() }
                    footers = listOf("Footer 1\n", "Footer 2\n")
                """.trimIndent()

                NoticeReporter().generateReport(ortResult, DefaultResolutionProvider(), tempDir, postProcessingScript)

                val resultFile = File(tempDir, "NOTICE")
                val actualText = resultFile.readText()

                actualText shouldBe expectedText
            }

            "return the input as-is for an empty post-processing script" {
                val expectedResultFile = File("src/test/assets/NPM-is-windows-1.0.2-expected-NOTICE")
                val expectedText = expectedResultFile.readText()
                val ortResult = readOrtResult("src/test/assets/NPM-is-windows-1.0.2-scan-result.json")

                NoticeReporter().generateReport(ortResult, DefaultResolutionProvider(), tempDir, "")

                val resultFile = File(tempDir, "NOTICE")
                val actualText = resultFile.readText()

                actualText shouldBe expectedText
            }
        }
    }
}
