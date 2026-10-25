package ir.peygir.app

import org.junit.Test
import org.junit.Assert.*

class VoiceCommandsTest {
    @Test fun voiceMarkersHandleSpellingUnicodeAndSegmentBoundariesWithoutSelectingDates() {
        listOf(
            "عنوان تهیه گزارش فروش مسئول آقای احمدی",
            "عنوان تهیه گزارش فروش\nمسؤول آقای احمدی",
            "عنوانش تهیه گزارش فروش مسئولش آقای احمدی",
            "عنوان: تهیه گزارش فروش؛ مسوول: آقای احمدی؛ تاریخ ۱۴۰۵/۰۸/۱۵",
            "خب عنوان تهیه گزارش فروش مسول آقای احمدی",
            "عُنوان، تهيه گزارش فروش؛ مَسئول، آقاي احمدي"
        ).forEach {text->
            val p=VoiceCommands.extract(text)
            assertNotNull(text,p);assertEquals(text,"تهیه گزارش فروش",p!!.title);assertEquals(text,"آقای احمدی",p.owner);assertNull(p.due)
        }
        assertNull(VoiceCommands.extract("عنوان گزارش"));assertNull(VoiceCommands.extract("گزارش مسئول احمدی"))
        assertEquals("پیگیری ضروری است",VoiceCommands.extract("عنوان پیگیری ضروری است مسئول آقای احمدی")!!.title)
    }
}
