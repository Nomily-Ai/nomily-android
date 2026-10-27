package com.nomily.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class RecordingNameTest {
    private fun stamp(s: String) = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(s)

    @Test fun exactStem() = assertEquals(stamp("20260415214207"), RecordingName.date("20260415214207.opus"))

    @Test fun mergedTakesOlderHalf() =
        assertEquals(stamp("20260415214207"), RecordingName.date("20260415214207+20260415215000.m4a"))

    @Test fun chainedMerge() =
        assertEquals(stamp("20260415214207"), RecordingName.date("20260415214207+20260415215000+20260415220000.m4a"))

    @Test fun dashedTimestamp() =
        assertEquals(stamp("20261009110934"), RecordingName.date("clip_20261009-110934_6AC74377.m4a"))

    @Test fun importedNameHasNoDate() = assertNull(RecordingName.date("Meeting notes.m4a"))

    @Test fun invalidDigitsRejected() = assertNull(RecordingName.date("20261399999999.opus"))
}
