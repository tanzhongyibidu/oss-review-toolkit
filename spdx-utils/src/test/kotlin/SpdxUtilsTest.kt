/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
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

package com.here.ort.spdx

import io.kotlintest.matchers.endWith
import io.kotlintest.matchers.startWith
import io.kotlintest.matchers.string.beBlank
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.shouldThrow
import io.kotlintest.specs.WordSpec

import java.io.IOException

class SpdxUtilsTest : WordSpec({
    "calculatePackageVerificationCode" should {
        "work for given SHA1s" {
            val sha1sums = listOf(
                    "0811bcab4e7a186f4d0d08d44cc5f06d721e7f6d",
                    "f7a535db519cf832c1119fecdf1ea0514f583886",
                    "0db752599b67b64dd1bdeff77ed9f5aa5437d027",
                    "9706f99c85a781c016a22fd23313e55257e7b3e8",
                    "1d96c52b533a38492ce290bc6831f8702f690e8e",
                    "e77075d2fb2cdeb4406538d9b33d00fd823a527a",
                    "b2f60873fd2c0feaf21eaccaf7ba052ceb12b146",
                    "1c2d4960f5d156e444f9819cec0c44c09f98970f",
                    "ca5fa85664a953084ca9a1de1e393698257495c0",
                    "0d172bac2b6712ecdc7b1e639c3cf8104f2a6a2a",
                    "14b6a02753d05f72ac57144f1ea0da52d97a0ce3",
                    "25306873d3e2434aade745e8a1a6914c215165f6",
                    "afd495c14035961a55d725ba0127e166349f28b9",
                    "1b8f69fa87f1abedd02f6c4766f07f0ceeea7a02",
                    "a4410f034f97b67eccbb8c9596d58c97ad3de988",
                    "a1663801985f4361395831ae17b3256e38810dc2",
                    "2a06f5906e5afb1b283b6f4fd6a21e7906cdde4f",
                    "1a409fc2dcd3dd10549c47793a80c42c3a06c9f0",
                    "2cc787ebd4d29f2e24646f76f9c525336949783e",
                    "3ea2f82d7ce6f638e9466365a328a201f2caa579",
                    "9257afd2d46c3a189ec0d40a45722701d47e9ca5",
                    "4ff8a82b52e1e1c5f8bf0abb25c20859d3f06c62",
                    "0e8faebf9505c9b1d5462adcf34e01e83d110cc8",
                    "824cadd41d399f98d17ae281737c6816846ac75d",
                    "f54dd0df3ab62f1d5687d98076dffdbf690840f6",
                    "91742d83b0feadb4595afeb4e7f4bab2e85f4a98",
                    "64dd561478479f12deda240ae9fe569952328bff",
                    "310fc965173381a02fbe83a889f7c858c4499862"
            )

            calculatePackageVerificationCode(sha1sums) shouldBe "1a74d8321c452522ec516a46893e6a42f36b5953"
        }
    }

    "getLicenseText" should {
        "return the full license text for a valid SPDX license id" {
            val text = getLicenseText("Apache-2.0").trim()

            text should startWith("Apache License")
            text should endWith("limitations under the License.")
        }

        "throw an exception for an invalid SPDX license id" {
            shouldThrow<IOException> { getLicenseText("FooBar-1.0") }
        }

        "return the exception text for an SPDX exception id if handling exceptions is enabled" {
            val text = getLicenseText("Autoconf-exception-3.0", true).trim()

            text should startWith("AUTOCONF CONFIGURE SCRIPT EXCEPTION")
            text should endWith("the copyleft requirements of the license of Autoconf.")
        }

        "throw an exception for an SPDX exception id if handling exceptions is disabled" {
            shouldThrow<IOException> { getLicenseText("Autoconf-exception-3.0", false) }
        }

        "return a non-blank string for all SPDX ids" {
            enumValues<SpdxLicense>().forEach {
                getLicenseText(it.id) shouldNot beBlank()
            }
        }

        "return the full license text for the HERE proprietary license" {
            val text = getLicenseText("LicenseRef-scancode-here-proprietary").trim()

            text should startWith("This software and other materials contain proprietary information")
            text should endWith("allowed.")
        }

        "return the full license text for a known SPDX LicenseRef" {
            val text = getLicenseText("LicenseRef-scancode-indiana-extreme").trim()

            text should startWith("Indiana University Extreme! Lab Software License Version 1.1.1")
            text should endWith("EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.")
        }

        "throw an exception for an unknown SPDX LicenseRef" {
            shouldThrow<IOException> { getLicenseText("LicenseRef-foo-bar") }
        }
    }
})
