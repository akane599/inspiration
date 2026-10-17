package com.akane.inspiration.diagnostics.dictionary

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.diagnostics.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticDictionaryTest {
    private val realJson: String by lazy {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.openRawResource(R.raw.diagnostic_dictionary)
            .bufferedReader().use { it.readText() }
    }
    private val real: DiagnosticDictionary by lazy {
        DiagnosticDictionary(DictionaryLoader.parse(realJson))
    }

    private fun entryJson(
        id: String,
        type: String,
        value: String,
        kinds: String = "\"partial\"",
        tr: String? = "tr-$id",
    ): String {
        val trField = tr?.let { ",\"tr\":\"$it\"" }.orEmpty()
        return """{"id":"$id","kinds":[$kinds],"match":{"type":"$type","value":"$value"},"category":"test",
            "title":{"en":"en-$id"$trField},"body":{"en":"body-$id","tr":"body-tr-$id"},"action":null}"""
    }

    private fun dictionaryOf(vararg entries: String) =
        DiagnosticDictionary(DictionaryLoader.parse("""{"version":1,"entries":[${entries.joinToString(",")}]}"""))

    private fun DiagnosticDictionary.idOf(kind: EntryKind, name: String): String? =
        explain(kind, name, Locale.ENGLISH)?.id

    @Test
    fun everyEntryOfTheRealDictionaryLoads() {
        val entries = DictionaryLoader.parse(realJson)
        assertEquals(72, entries.size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        entries.forEach {
            assertTrue(it.id, it.title.en.isNotBlank() && it.title.tr.isNotBlank())
            assertTrue(it.id, it.body.en.isNotBlank() && it.body.tr.isNotBlank())
            assertTrue(it.id, it.action in setOf(null, "bg_dexopt", "standby_bucket", "appops_wake_lock"))
        }
    }

    @Test
    fun exactBeatsPrefixBeatsContainsBeatsRegex() {
        val dict = dictionaryOf(
            entryJson("regex", "regex", "^abc"),
            entryJson("contains", "contains", "abc"),
            entryJson("prefix", "prefix", "ab"),
            entryJson("exact", "exact", "abcdef"),
        )
        assertEquals("exact", dict.idOf(EntryKind.PARTIAL, "abcdef"))
        assertEquals("prefix", dict.idOf(EntryKind.PARTIAL, "abcdeg"))
        assertEquals("contains", dict.idOf(EntryKind.PARTIAL, "xabc"))
    }

    @Test
    fun regexIsUsedOnlyWhenNothingElseMatches() {
        val dict = dictionaryOf(
            entryJson("regex", "regex", "x+y"),
            entryJson("contains", "contains", "zz"),
        )
        assertEquals("regex", dict.idOf(EntryKind.PARTIAL, "axxy"))
        assertEquals("contains", dict.idOf(EntryKind.PARTIAL, "xxyzz"))
        assertNull(dict.idOf(EntryKind.PARTIAL, "nothing"))
    }

    @Test
    fun longestAffixWinsAndPrefixAndSuffixShareATier() {
        val dict = dictionaryOf(
            entryJson("short-prefix", "prefix", "job"),
            entryJson("long-prefix", "prefix", "job/com.google"),
            entryJson("long-suffix", "suffix", "/com.google.Service"),
        )
        assertEquals("long-prefix", dict.idOf(EntryKind.PARTIAL, "job/com.google.Other"))
        assertEquals("long-suffix", dict.idOf(EntryKind.PARTIAL, "job/com.google.Service"))
        assertEquals("long-suffix", dict.idOf(EntryKind.PARTIAL, "x/com.google.Service"))
        assertEquals("short-prefix", dict.idOf(EntryKind.PARTIAL, "job/other"))
    }

    @Test
    fun longestContainsWins() {
        val dict = dictionaryOf(
            entryJson("short", "contains", "net"),
            entryJson("long", "contains", "net_scheduler"),
        )
        assertEquals("long", dict.idOf(EntryKind.PARTIAL, "*net_scheduler*"))
        assertEquals("short", dict.idOf(EntryKind.PARTIAL, "network"))
    }

    @Test
    fun tiesGoToTheEarlierEntryInFileOrder() {
        val dict = dictionaryOf(
            entryJson("first-contains", "contains", "abc"),
            entryJson("second-contains", "contains", "bcd"),
            entryJson("first-regex", "regex", "^q"),
            entryJson("second-regex", "regex", "q"),
        )
        assertEquals("first-contains", dict.idOf(EntryKind.PARTIAL, "xabcdx"))
        assertEquals("first-regex", dict.idOf(EntryKind.PARTIAL, "qq"))
    }

    @Test
    fun entriesOnlyAnswerTheirOwnKinds() {
        val dict = dictionaryOf(entryJson("alarm-only", "exact", "tick", kinds = "\"alarm\""))
        assertEquals("alarm-only", dict.idOf(EntryKind.ALARM, "tick"))
        assertNull(dict.idOf(EntryKind.PARTIAL, "tick"))
        assertNull(dict.idOf(EntryKind.KERNEL, "tick"))
    }

    @Test
    fun realPrecedenceExamples() {
        assertEquals("job_firebase", real.idOf(EntryKind.PARTIAL, "*job*/com.google.firebase.iid.Service"))
        assertEquals("job_gms", real.idOf(EntryKind.PARTIAL, "*job*/com.google.android.gms/.chimera.Service"))
        assertEquals("job_google", real.idOf(EntryKind.PARTIAL, "*job*/com.google.android.apps.maps/.Job"))
        assertEquals("job_prefix", real.idOf(EntryKind.PARTIAL, "*job*/com.example.app/.SyncJob"))
        assertEquals("wlan_rx_wake", real.idOf(EntryKind.KERNEL, "wlan_rx_wake"))
        assertEquals("wlan_generic", real.idOf(EntryKind.KERNEL, "wlan_something_new"))
        assertEquals("alarm_manager", real.idOf(EntryKind.PARTIAL, "*alarm*"))
        assertEquals("walarm_prefix", real.idOf(EntryKind.PARTIAL, "*walarm*:com.example.ACTION"))
    }

    @Test
    fun specExamplesResolve() {
        assertEquals("pms_wakelocks", real.idOf(EntryKind.KERNEL, "PowerManagerService.WakeLocks"))
        assertEquals("pms_wakelocks", real.idOf(EntryKind.PARTIAL, "PowerManagerService.WakeLocks"))
        assertEquals("dexopt_marker", real.idOf(EntryKind.PARTIAL, "*dexopt*"))
        assertEquals("bg_dexopt", real.idOf(EntryKind.PARTIAL, "BgDexopt"))
        assertEquals("bg_dexopt", real.explain(EntryKind.PARTIAL, "*dexopt*", Locale.ENGLISH)?.action)
        assertEquals("bg_dexopt", real.explain(EntryKind.PARTIAL, "BgDexopt", Locale.ENGLISH)?.action)
        assertEquals("qcom_rx_wakelock", real.idOf(EntryKind.KERNEL, "qcom_rx_wakelock"))
        assertEquals("wlan_rx_wake", real.idOf(EntryKind.KERNEL, "wlan_rx_wake"))
        assertEquals("job_deadline", real.idOf(EntryKind.ALARM, "*job.deadline*"))
        assertEquals("alarm_manager", real.idOf(EntryKind.PARTIAL, "*alarm*"))
        assertEquals("ims_rtp_wakelock", real.idOf(EntryKind.KERNEL, "ims_rtp_wakelock"))
    }

    @Test
    fun f6ConventionNamesResolve() {
        assertEquals("sync_prefix", real.idOf(EntryKind.PARTIAL, "*sync*/com.android.contacts/user@example.com"))
        assertEquals("launch", real.idOf(EntryKind.PARTIAL, "*launch*"))
        assertEquals("network_stats", real.idOf(EntryKind.PARTIAL, "NetworkStats"))
        assertEquals("google_c2dm", real.idOf(EntryKind.PARTIAL, "GOOGLE_C2DM"))
        assertEquals("ipa_ws", real.idOf(EntryKind.KERNEL, "ipa_ws"))
        assertEquals("timerfd", real.idOf(EntryKind.KERNEL, "[timerfd]"))
        assertEquals("alarmtimer", real.idOf(EntryKind.KERNEL, "alarmtimer"))
        assertEquals("eventpoll", real.idOf(EntryKind.KERNEL, "eventpoll"))
        assertEquals("nlp_wakelock", real.idOf(EntryKind.PARTIAL, "NlpWakeLock"))
        assertEquals("wakeful_geofencer", real.idOf(EntryKind.PARTIAL, "Wakeful StateMachine: GeofencerStateMachine"))
        assertEquals("wakeful_statemachine", real.idOf(EntryKind.PARTIAL, "Wakeful StateMachine: Other"))
        assertEquals("audio_mix", real.idOf(EntryKind.PARTIAL, "AudioMix"))
        assertEquals("audio_in", real.idOf(EntryKind.PARTIAL, "AudioIn_15"))
        assertEquals("net_scheduler", real.idOf(EntryKind.PARTIAL, "*net_scheduler*"))
        assertEquals("sync_loop", real.idOf(EntryKind.PARTIAL, "SyncLoopWakeLock"))
        assertEquals("pms_display", real.idOf(EntryKind.PARTIAL, "PowerManagerService.Display"))
        assertEquals("pms_broadcasts", real.idOf(EntryKind.PARTIAL, "PowerManagerService.Broadcasts"))
    }

    @Test
    fun prefixedAlarmSubjectsResolveTheTagBeforeTheFallbackPrefix() {
        assertEquals("job_deadline", real.idOf(EntryKind.ALARM, "*walarm*:*job.deadline*"))
        assertEquals("job_delay", real.idOf(EntryKind.ALARM, "*alarm*:*job.delay*"))
        assertEquals("job_deadline", real.idOf(EntryKind.PARTIAL, "*walarm*:*job.deadline*"))
        assertEquals("gcm_conn_alarm", real.idOf(EntryKind.ALARM, "*walarm*:GCM_CONN_ALARM"))
        assertEquals("gcm_hb_alarm", real.idOf(EntryKind.ALARM, "*alarm*:GCM_HB_ALARM"))
        assertEquals(
            "nlp_locator_alarm",
            real.idOf(EntryKind.ALARM, "*walarm*:com.google.android.gms.nlp.ALARM_WAKEUP_LOCATOR"),
        )
        assertEquals("re_push", real.idOf(EntryKind.PARTIAL, "*walarm*:com.google.android.intent.action.GCM_RECONNECT"))
    }

    @Test
    fun prefixedAlarmSubjectsWithoutASpecificEntryFallBackToThePrefixEntry() {
        assertEquals("walarm_prefix", real.idOf(EntryKind.ALARM, "*walarm*:WriteBufferAlarm"))
        assertEquals("walarm_prefix", real.idOf(EntryKind.PARTIAL, "*walarm*:WriteBufferAlarm"))
        assertEquals("alarm_prefix", real.idOf(EntryKind.ALARM, "*alarm*:TIME_TICK"))
        assertEquals("alarm_prefix", real.idOf(EntryKind.PARTIAL, "*alarm*:TIME_TICK"))
        // The bare marker and other kinds are untouched by the prefix handling.
        assertEquals("alarm_manager", real.idOf(EntryKind.PARTIAL, "*alarm*"))
        assertNull(real.idOf(EntryKind.KERNEL, "*alarm*:TIME_TICK"))
    }

    @Test
    fun prefixIsStrippedOnlyForAlarmAndPartialKinds() {
        val dict = dictionaryOf(
            entryJson("tag", "exact", "tick", kinds = "\"alarm\",\"partial\",\"kernel\""),
            entryJson("fallback", "prefix", "*alarm*:", kinds = "\"alarm\",\"partial\",\"kernel\""),
        )
        assertEquals("tag", dict.idOf(EntryKind.ALARM, "*alarm*:tick"))
        assertEquals("tag", dict.idOf(EntryKind.PARTIAL, "*alarm*:tick"))
        assertEquals("fallback", dict.idOf(EntryKind.KERNEL, "*alarm*:tick"))
    }

    @Test
    fun jobSchedulerVariantLetterIsNormalizedForPartialWakelocks() {
        assertEquals(
            "job_prefix",
            real.idOf(EntryKind.PARTIAL, "*job*r/DownloadManager:com.android.providers.downloads"),
        )
        assertEquals("job_gms", real.idOf(EntryKind.PARTIAL, "*job*e/com.google.android.gms/.x.Y"))
        assertEquals("job_firebase", real.idOf(EntryKind.PARTIAL, "*job*u/com.google.firebase.iid.Service"))
        assertEquals("job_google", real.idOf(EntryKind.PARTIAL, "*job*r/com.google.android.apps.maps/.Job"))
        assertEquals("job_gms", real.idOf(EntryKind.PARTIAL, "*job*/com.google.android.gms/.x.Y"))
        assertEquals(
            "job_prefix",
            real.idOf(EntryKind.PARTIAL, "*job*r/@class com.android.server.healthconnect.migration.MigrationStateChangeJob@android/x"),
        )
    }

    @Test
    fun jobVariantNormalizationIsLimitedToPartialAndOneLowercaseLetter() {
        val dict = dictionaryOf(
            entryJson("job", "prefix", "*job*/", kinds = "\"alarm\",\"partial\""),
        )
        assertEquals("job", dict.idOf(EntryKind.PARTIAL, "*job*r/pkg/.Svc"))
        assertNull(dict.idOf(EntryKind.ALARM, "*job*r/pkg/.Svc"))
        assertNull(dict.idOf(EntryKind.PARTIAL, "*job*R/pkg/.Svc"))
        assertNull(dict.idOf(EntryKind.PARTIAL, "*job*rr/pkg/.Svc"))
    }

    @Test
    fun realWordingFixesFromReview() {
        val qcom = real.explain(EntryKind.KERNEL, "qcom_rx_wakelock", Locale.ENGLISH)
        assertEquals("wifi", qcom?.category)
        assertEquals("pms_display", real.idOf(EntryKind.KERNEL, "PowerManagerService.Display"))
        assertEquals("pms_broadcasts", real.idOf(EntryKind.KERNEL, "PowerManagerService.Broadcasts"))
        assertNull(real.idOf(EntryKind.PARTIAL, "AudioInput"))
    }

    @Test
    fun kernelAndWakeupReasonNamesMatchLowercase() {
        assertEquals("wlan_rx_wake", real.idOf(EntryKind.KERNEL, "WLAN_RX_WAKE"))
        assertEquals("pms_wakelocks", real.idOf(EntryKind.KERNEL, "powermanagerservice.wakelocks"))
        assertEquals("wr_abort", real.idOf(EntryKind.WAKEUP_REASON, "Abort: Pending Wakeup Sources: x"))
    }

    @Test
    fun otherKindsStayCaseSensitive() {
        assertEquals("google_c2dm", real.idOf(EntryKind.PARTIAL, "GOOGLE_C2DM"))
        assertNotEquals("google_c2dm", real.idOf(EntryKind.PARTIAL, "google_c2dm"))
        assertNull(real.idOf(EntryKind.PARTIAL, "nlpwakelock"))
        assertEquals("dexopt_contains", real.idOf(EntryKind.PARTIAL, "bgdexopt"))
        assertNull(real.idOf(EntryKind.ALARM, "DEVICEIDLECONTROLLER.LIGHT"))
    }

    @Test
    fun unknownNameIsNull() {
        assertNull(real.explain(EntryKind.PARTIAL, "completely.unknown.tag", Locale.ENGLISH))
        assertNull(real.explain(EntryKind.PARTIAL, "", Locale.ENGLISH))
    }

    @Test
    fun localeUsesTurkishOnlyForTurkishAndFallsBackToEnglish() {
        val en = real.explain(EntryKind.PARTIAL, "BgDexopt", Locale.ENGLISH)
        val tr = real.explain(EntryKind.PARTIAL, "BgDexopt", Locale.forLanguageTag("tr-TR"))
        val de = real.explain(EntryKind.PARTIAL, "BgDexopt", Locale.GERMAN)
        assertNotNull(en)
        assertNotNull(tr)
        assertNotEquals(en?.title, tr?.title)
        assertNotEquals(en?.body, tr?.body)
        assertEquals(en, de)
        assertEquals(en?.id, tr?.id)
    }

    @Test
    fun malformedJsonIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { DictionaryLoader.parse("not json") }
        assertThrows(IllegalArgumentException::class.java) { DictionaryLoader.parse("{}") }
    }

    @Test
    fun duplicateIdIsRejected() {
        val json = """{"version":1,"entries":[${entryJson("a", "exact", "x")},${entryJson("a", "exact", "y")}]}"""
        val error = assertThrows(IllegalArgumentException::class.java) { DictionaryLoader.parse(json) }
        assertTrue(error.message, error.message.orEmpty().contains("Duplicate"))
    }

    @Test
    fun missingTurkishTextIsRejected() {
        val json = """{"version":1,"entries":[${entryJson("a", "exact", "x", tr = null)}]}"""
        val error = assertThrows(IllegalArgumentException::class.java) { DictionaryLoader.parse(json) }
        assertTrue(error.message, error.message.orEmpty().contains("tr"))
    }

    @Test
    fun missingEnglishBodyIsRejected() {
        val json = """{"version":1,"entries":[{"id":"a","kinds":["partial"],"match":{"type":"exact","value":"x"},
            "category":"test","title":{"en":"t","tr":"t"},"body":{"tr":"b"},"action":null}]}"""
        assertThrows(IllegalArgumentException::class.java) { DictionaryLoader.parse(json) }
    }

    @Test
    fun unknownKindTypeActionAndBadRegexAreRejected() {
        fun parse(entry: String) = DictionaryLoader.parse("""{"version":1,"entries":[$entry]}""")
        assertThrows(IllegalArgumentException::class.java) { parse(entryJson("a", "exact", "x", kinds = "\"bogus\"")) }
        assertThrows(IllegalArgumentException::class.java) { parse(entryJson("a", "fuzzy", "x")) }
        assertThrows(IllegalArgumentException::class.java) { parse(entryJson("a", "regex", "([unclosed")) }
        assertThrows(IllegalArgumentException::class.java) {
            parse(entryJson("a", "exact", "x").replace("\"action\":null", "\"action\":\"format_disk\""))
        }
    }
}
