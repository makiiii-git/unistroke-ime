package com.unistroke.ime

import android.os.Bundle
import android.widget.TextView

/**
 * 同梱している第三者データのライセンス表示。
 *
 * 端末内変換の辞書（assets/ondevice.dic）は Google の Mozc プロジェクトの
 * OSS 辞書（BSD-3-Clause）から作っている。BSD-3-Clause は
 * 「バイナリ形式で再配布する場合、著作権表示・条件・免責事項を
 * ドキュメント等に含めること」を求めるので、この画面がその義務を果たす。
 *
 * 英語版の単語辞書（assets/english.dic）は AOSP の LatinIME（Android 標準の
 * キーボード）が配っている単語リストから作っている。こちらは Apache License 2.0 で、
 * 著作権表示とライセンスの写しを添えることが条件なので、同じ画面に並べて出す。
 *
 * ライセンス本文は原文のまま出す必要があるため、翻訳せず英語のまま表示する。
 */
class LicenseActivity : LocalizedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_license)
        findViewById<TextView>(R.id.text_license_body).text = MOZC_LICENSE
        findViewById<TextView>(R.id.text_license_english).text =
            AOSP_NOTICE + "\n\n" + apacheLicense()
    }

    /**
     * Apache License 2.0 の全文。長いのでソースには埋めず、assets に置いた写しを読む。
     * 読めなかったときは、せめて原文の場所だけは示す。
     */
    private fun apacheLicense(): String = runCatching {
        assets.open(APACHE_LICENSE_ASSET).bufferedReader().use { it.readText() }
    }.getOrDefault(APACHE_LICENSE_URL)

    private companion object {
        /** Apache License 2.0 の全文（リポジトリ直下の LICENSE と同じもの）。 */
        const val APACHE_LICENSE_ASSET = "licenses/apache-2.0.txt"
        const val APACHE_LICENSE_URL = "http://www.apache.org/licenses/LICENSE-2.0"

        /**
         * AOSP LatinIME の NOTICE にある著作権表示（原文）。
         * 単語リスト（dictionaries/en_wordlist.combined.gz）の出どころ。
         */
        val AOSP_NOTICE = """
            Copyright (c) 2008, The Android Open Source Project

            Licensed under the Apache License, Version 2.0 (the "License");
            you may not use this file except in compliance with the License.

            Unless required by applicable law or agreed to in writing, software
            distributed under the License is distributed on an "AS IS" BASIS,
            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            See the License for the specific language governing permissions and
            limitations under the License.
        """.trimIndent()

        /**
         * google/mozc の LICENSE（BSD-3-Clause）の原文。
         * 辞書データ（dictionary_oss/dictionary0[0-9].txt, id.def,
         * connection_single_column.txt）の出どころ。
         */
        val MOZC_LICENSE = """
            Copyright 2010-2018, Google Inc.
            All rights reserved.

            Redistribution and use in source and binary forms, with or without
            modification, are permitted provided that the following conditions are
            met:

              * Redistributions of source code must retain the above copyright
                notice, this list of conditions and the following disclaimer.
              * Redistributions in binary form must reproduce the above
                copyright notice, this list of conditions and the following disclaimer
                in the documentation and/or other materials provided with the
                distribution.
              * Neither the name of Google Inc. nor the names of its
                contributors may be used to endorse or promote products derived from
                this software without specific prior written permission.

            THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
            "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
            LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
            A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
            OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
            SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
            LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
            DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
            THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
            (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
            OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
        """.trimIndent()
    }
}
