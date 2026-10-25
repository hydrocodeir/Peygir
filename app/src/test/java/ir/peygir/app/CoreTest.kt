package ir.peygir.app

import android.app.Application
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class CoreTest {
    private lateinit var context: Context
    private lateinit var store: Store
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db")
        context.getSharedPreferences("settings",0).edit().clear().putBoolean("daily",false).commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        store=Store.get(context)
    }
    @After fun close() {store.close()}
    @Test fun calendarNowruzLeapAndInvalidDates() {
        assertEquals(LocalDate.of(2025,3,21),Solar.toDate(1404,1,1))
        assertEquals(LocalDate.of(2026,3,21),Solar.toDate(1405,1,1))
        assertEquals(LocalDate.of(2026,11,6),Solar.parse("مهلت ۱۴۰۵/۰۸/۱۵"))
        assertEquals(LocalDate.of(2026,11,6),Solar.parse("١٤٠٥/٠٨/١٥"))
        assertNotNull(Solar.toDate(1403,12,30))
        assertNull(Solar.toDate(1404,12,30))
        assertNull(Solar.toDate(1405,7,31))
        assertNull(Solar.parse("۱۴۰۵/۰۸/۱۵۰"))
        assertNull(Solar.parse("۱۴۰۵/۱۳/۱۵"))
    }
    @Test fun calendarRoundTripsAcrossYearAndMonthEdges() {
        val start=LocalDate.of(2023,3,1)
        repeat(1500) { i->val d=start.plusDays(i.toLong());val p=Solar.parts(d);assertEquals(d,Solar.toDate(p.year,p.month,p.day)) }
        assertEquals(Solar.toDate(1405,1,1),Solar.nextMonth(Solar.toDate(1404,12,12)!!))
        assertEquals(Solar.toDate(1404,12,1),Solar.previousMonth(Solar.toDate(1405,1,3)!!))
    }
    @Test fun extractsPersianOwnerDateMultipleTasksAndContinuationLines() {
        val p=Extractor.extract("تهیه گزارش فروش\nمسئول: آقای احمدی\nمهلت: ۱۴۰۵/۰۸/۱۵\nارسال قرارداد؛ مسئول: خانم رضایی؛ سررسید: ۱۴۰۵/۰۸/۲۰")
        assertEquals(2,p.size)
        assertEquals("تهیه گزارش فروش",p[0].title)
        assertEquals("آقای احمدی",p[0].owner)
        assertEquals(Solar.toDate(1405,8,15),p[0].due)
        assertEquals("خانم رضایی",p[1].owner)
        assertEquals(Solar.toDate(1405,8,20),p[1].due)
    }
    @Test fun extractionNeverInventsOwnerOrInvalidDate() {
        val p=Extractor.extract("پیگیری درخواست مشتری تا ۱۴۰۵/۱۳/۱۰").single()
        assertEquals("",p.owner);assertNull(p.due)
        assertTrue(Extractor.extract("  \n ").isEmpty())
        val today=LocalDate.of(2026,10,23)
        assertEquals(today.plusDays(2),Extractor.extract("ارسال گزارش پس فردا",today).single().due)
    }
    @Test fun persistsMeetingsStatusesAttachmentsAndMultipleRemindersAfterReopen() {
        val meeting=store.saveMeeting(Meeting(title="شورای مدیران",notes="برنامه پاییز"))
        val at=System.currentTimeMillis()+3_600_000
        val task=store.saveTask(Resolution(title="ارسال گزارش",owner="آقای احمدی",meetingId=meeting),listOf(at,at+10000))
        store.status(task,Status.ACTIVE)
        store.saveRecording(Recording(meetingId=meeting,path="/private/voice.wav",created=at,seconds=3))
        store.close()
        store=Store(context)
        val data=store.snapshot()
        assertEquals(1,data.meetings.size);assertEquals(2,data.reminders.size)
        assertEquals(Status.ACTIVE,data.tasks.single().status)
        assertEquals(meeting,data.recordings.single().meetingId)
    }
    @Test fun editsPreserveDeliveredFlagsAndDeleteCascadesReminders() {
        val at=System.currentTimeMillis()+3600000
        val id=store.saveTask(Resolution(title="قرارداد",owner="رضایی"),listOf(at,at+1000))
        store.delivered(listOf(store.snapshot().reminders.first().id))
        store.saveTask(store.snapshot().tasks.single().copy(title="قرارداد جدید"),listOf(at,at+2000))
        val data=store.snapshot()
        assertTrue(data.reminders.first{it.at==at}.delivered)
        assertEquals(listOf(at,at+2000),data.reminders.map{it.at})
        store.deleteTask(id)
        assertTrue(store.snapshot().reminders.isEmpty())
    }
    @Test fun failedEditRollsBackTitleAndReminderChanges() {
        val at=System.currentTimeMillis()+3600000
        store.saveTask(Resolution(title="اصل مصوبه",owner="احمدی"),listOf(at))
        val t=store.snapshot().tasks.single()
        assertThrows(IllegalArgumentException::class.java) {store.saveTask(t.copy(title="تغییر نام"),listOf(1L))}
        assertEquals(t,store.snapshot().tasks.single())
        assertEquals(at,store.snapshot().reminders.single().at)
    }
    @Test fun deletingMeetingPreservesTasksRemindersHistoryAndAudioAfterReopen() {
        val first=store.saveMeeting(Meeting(title="جلسهٔ حذف‌شونده"))
        val other=store.saveMeeting(Meeting(title="جلسهٔ دیگر"))
        val today=LocalDate.now().toEpochDay()
        store.saveTask(Resolution(title="مصوبهٔ وابسته",owner="احمدی",meetingId=first,due=today),listOf(System.currentTimeMillis()+3600000))
        store.saveTask(store.snapshot().tasks.single().copy(due=today+2),store.snapshot().reminders.map{it.at},DueChangeKind.EXTEND,"دلیل آزمایشی")
        store.saveTask(Resolution(title="مصوبهٔ دیگر",owner="رضایی",meetingId=other),emptyList())
        val audio=voice("meeting-delete.wav").copy(meetingId=first)
        store.saveRecording(audio)
        val before=store.snapshot()
        store.deleteMeeting(first);store.close();store=Store(context)
        val after=store.snapshot()
        assertEquals(before.meetings.filter{it.id!=first},after.meetings)
        assertEquals(before.tasks.map{if(it.meetingId==first) it.copy(meetingId=null) else it},after.tasks)
        assertEquals(before.reminders,after.reminders);assertEquals(before.history,after.history)
        assertEquals(before.recordings.map{it.copy(meetingId=null)},after.recordings)
        assertTrue(java.io.File(audio.path).isFile)
    }
    private fun voice(name: String): Recording {
        val file=java.io.File(java.io.File(context.filesDir,"recordings").apply{mkdirs()},name).apply{writeBytes(ByteArray(100))}
        return Recording(path=file.absolutePath,created=System.currentTimeMillis(),seconds=1)
    }
    @Test fun discardingDraftDeletesOnlyItsAudioAndRejectsLateRecordingCallbacks() {
        val archived=voice("old.wav");store.saveRecording(archived)
        val fresh=voice("fresh.wav");store.stageRecording(fresh,"session-one")
        val other=voice("other.wav");store.stageRecording(other,"session-two")
        assertEquals(1,store.snapshot().recordings.size)
        assertEquals(2,store.snapshot().draftRecordings.size)
        store.discardDraftRecordings("session-one")
        assertFalse(java.io.File(fresh.path).exists())
        assertTrue(java.io.File(archived.path).exists());assertTrue(java.io.File(other.path).exists())
        val late=voice("late.wav")
        assertNull(store.stageRecording(late,"session-one"));assertFalse(java.io.File(late.path).exists())
        assertEquals("session-two",store.snapshot().draftRecordings.single().draftSession)
    }
    @Test fun savingDeletesDraftAudioOnlyAfterTaskTransactionSucceeds() {
        val fresh=voice("to-save.wav");store.stageRecording(fresh,"form")
        assertThrows(IllegalArgumentException::class.java) {store.saveTask(Resolution(title="آزمون",owner="احمدی"),listOf(1L),draftSession="form")}
        assertTrue(store.snapshot().recordings.isEmpty());assertEquals(1,store.snapshot().draftRecordings.size)
        assertTrue(java.io.File(fresh.path).exists())
        store.saveTask(Resolution(title="آزمون",owner="احمدی"),emptyList(),draftSession="form")
        store.discardDraftRecordings("form")
        assertTrue(store.snapshot().draftRecordings.isEmpty());assertTrue(store.snapshot().recordings.isEmpty())
        assertFalse(java.io.File(fresh.path).exists());assertEquals(1,store.snapshot().tasks.size)
    }
    @Test fun savingVoiceTaskConsumesOnlyTheSelectedOldRecordingAndPreservesItsText() {
        val used=voice("used-archive.wav").copy(transcript="عنوان گزارش مسئول احمدی")
        val usedId=store.saveRecording(used)
        val untouched=voice("untouched-archive.wav");store.saveRecording(untouched)
        assertThrows(IllegalArgumentException::class.java) {store.saveTask(Resolution(title="گزارش",owner="احمدی"),listOf(1L),consumedRecording=usedId)}
        assertTrue(java.io.File(used.path).exists());assertEquals(2,store.snapshot().recordings.size)
        store.saveTask(Resolution(title="گزارش",owner="احمدی",description=used.transcript),emptyList(),consumedRecording=usedId)
        assertFalse(java.io.File(used.path).exists());assertTrue(java.io.File(untouched.path).exists())
        assertEquals(untouched.path,store.snapshot().recordings.single().path)
        assertEquals(used.transcript,store.snapshot().tasks.single().description)
    }
    @Test fun voiceCommandsRequireMarkersAndNeverSelectDueDate() {
        val p=VoiceCommands.extract("عنوان، تهیه گزارش فروش؛ مسئول، آقای احمدی؛ مهلت ۱۴۰۵/۰۸/۱۵")!!
        assertEquals("تهیه گزارش فروش",p.title);assertEquals("آقای احمدی",p.owner);assertNull(p.due)
        assertEquals("خانم رضایی",VoiceCommands.extract("عنوان ارسال قرارداد مسوول خانم رضایی")!!.owner)
        assertNull(VoiceCommands.extract("تهیه گزارش مسئول احمدی"))
        assertNull(VoiceCommands.extract("عنوان گزارش مسئول"))
        assertNull(VoiceCommands.extract("عنوان مسئول احمدی"))
    }
    @Test fun defaultFollowUpUsesPreviousCalendarDayAndNeverCreatesPastAlarms() {
        val zone=ZoneId.of("Asia/Tehran")
        val morning=LocalDate.of(2026,10,23).atTime(8,0).atZone(zone)
        val tomorrow=morning.toLocalDate().plusDays(1).toEpochDay()
        assertEquals(morning.withHour(9).toInstant().toEpochMilli(),DefaultFollowUp.forDue(tomorrow,morning))
        val afternoon=morning.withHour(15)
        assertEquals(afternoon.plusMinutes(5).toInstant().toEpochMilli(),DefaultFollowUp.forDue(tomorrow,afternoon))
        assertNull(DefaultFollowUp.forDue(morning.toLocalDate().toEpochDay(),afternoon))
        assertNull(DefaultFollowUp.forDue(tomorrow,morning.withHour(23).withMinute(58)))
        val future=DefaultFollowUp.forDue(tomorrow+10,afternoon)!!
        assertEquals(morning.toLocalDate().plusDays(10),Instant.ofEpochMilli(future).atZone(zone).toLocalDate())
        assertEquals(LocalTime.of(9,0),Instant.ofEpochMilli(future).atZone(zone).toLocalTime())
    }
    @Test fun restartCleansAbandonedDraftsButRetainsRestoredFormAndArchives() {
        val old=voice("old-archive.wav");store.saveRecording(old)
        val abandoned=voice("abandoned.wav");store.stageRecording(abandoned,"abandoned")
        val restored=voice("restored.wav");store.stageRecording(restored,"restored")
        store.close();store=Store(context)
        store.discardDraftRecordings(except="restored")
        assertFalse(java.io.File(abandoned.path).exists());assertTrue(java.io.File(old.path).exists())
        assertEquals("restored",store.snapshot().draftRecordings.single().draftSession)
        store.discardDraftRecordings()
        assertFalse(java.io.File(restored.path).exists());assertEquals(old.path,store.snapshot().recordings.single().path)
    }
    @Test fun postponementsRetainEveryDueDateReasonAndReminderState() {
        val today=LocalDate.now().toEpochDay()
        val old=System.currentTimeMillis()+3600000
        val first=store.saveTask(Resolution(title="گزارش",owner="احمدی",due=today-2,status=Status.ACTIVE),listOf(old))
        store.delivered(listOf(store.snapshot().reminders.single().id))
        val next=old+86400000
        store.saveTask(store.snapshot().tasks.single().copy(due=today+2),listOf(old,next),DueChangeKind.EXTEND,"نیاز به بررسی")
        store.saveTask(store.snapshot().tasks.single().copy(due=today+5),listOf(old,next+86400000),DueChangeKind.POSTPONE,"منتظر پاسخ")
        store.close();store=Store(context)
        val data=store.snapshot()
        assertEquals(first,data.tasks.single().id);assertEquals(Status.ACTIVE,data.tasks.single().status)
        assertEquals(2,data.tasks.single().delayCount)
        assertEquals(listOf(today+2,today-2),data.history.map{it.oldDue})
        assertEquals(listOf(today+5,today+2),data.history.map{it.newDue})
        assertEquals(listOf("منتظر پاسخ","نیاز به بررسی"),data.history.map{it.reason})
        assertEquals(DueChangeKind.POSTPONE,data.history.first().kind)
        assertTrue(data.reminders.single{it.at==old}.delivered)
        assertFalse(data.reminders.single{it.at!=old}.delivered)
        assertFalse(data.reminders.any{it.at==next})
        store.deleteTask(first);assertTrue(store.snapshot().history.isEmpty())
    }
    @Test fun invalidRescheduleRollsBackHistoryAndDateTogether() {
        val today=LocalDate.now().toEpochDay()
        store.saveTask(Resolution(title="اصل",owner="احمدی",due=today),emptyList())
        val original=store.snapshot().tasks.single()
        assertThrows(IllegalArgumentException::class.java) {store.saveTask(original.copy(due=today+3),listOf(1L),DueChangeKind.EXTEND,"دلیل")}
        assertEquals(original,store.snapshot().tasks.single());assertTrue(store.snapshot().history.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {store.saveTask(original.copy(due=today-1),emptyList(),DueChangeKind.POSTPONE)}
        assertTrue(store.snapshot().history.isEmpty())
    }
    @Test fun ordinaryDueEditsAreAlsoAuditedWithoutCountingEarlierDatesAsDelay() {
        val today=LocalDate.now().toEpochDay()
        store.saveTask(Resolution(title="اصل",owner="احمدی",due=today+5),emptyList())
        store.saveTask(store.snapshot().tasks.single().copy(due=today+2),emptyList())
        assertEquals(DueChangeKind.EDIT,store.snapshot().history.single().kind)
        assertEquals(0,store.snapshot().tasks.single().delayCount)
        store.saveTask(store.snapshot().tasks.single().copy(title="عنوان جدید"),emptyList())
        assertEquals(1,store.snapshot().history.size)
    }
    @Test fun upgradesVersionOneDatabaseWithoutDeletingExistingRows() {
        val legacy=Store(context,"legacy-test.db")
        val mid=legacy.saveMeeting(Meeting(title="جلسهٔ قبلی"))
        legacy.saveTask(Resolution(title="مصوبهٔ قبلی",owner="احمدی",meetingId=mid),listOf(System.currentTimeMillis()+3600000))
        legacy.saveRecording(Recording(meetingId=mid,path="/private/old.wav",created=1,seconds=2))
        val before=legacy.snapshot()
        legacy.writableDatabase.execSQL("DROP TABLE due_changes")
        legacy.writableDatabase.execSQL("DROP TABLE follow_ups")
        legacy.writableDatabase.execSQL("DROP TABLE notification_tokens");legacy.writableDatabase.execSQL("DROP TABLE backlog")
        legacy.writableDatabase.execSQL("ALTER TABLE recordings RENAME TO newer_recordings")
        legacy.writableDatabase.execSQL("CREATE TABLE recordings(id INTEGER PRIMARY KEY AUTOINCREMENT,meeting_id INTEGER REFERENCES meetings(id) ON DELETE SET NULL,path TEXT NOT NULL,created INTEGER NOT NULL,seconds INTEGER NOT NULL,transcript TEXT NOT NULL)")
        legacy.writableDatabase.execSQL("INSERT INTO recordings SELECT id,meeting_id,path,created,seconds,transcript FROM newer_recordings")
        legacy.writableDatabase.execSQL("DROP TABLE newer_recordings")
        legacy.writableDatabase.version=1;legacy.close()
        val upgraded=Store(context,"legacy-test.db")
        try {assertEquals(before,upgraded.snapshot());assertEquals(4,upgraded.readableDatabase.version)} finally {upgraded.close();context.deleteDatabase("legacy-test.db")}
    }
    @Test fun dispatchOnlyNotifiesDueUnfinishedTasksAndDoesNotRepeat() {
        val now=System.currentTimeMillis()
        val active=store.saveTask(Resolution(title="تهیه گزارش",owner="احمدی"),listOf(now+10000))
        val done=store.saveTask(Resolution(title="قرارداد",owner="رضایی",status=Status.DONE),listOf(now+20000))
        store.writableDatabase.execSQL("UPDATE reminders SET at=?",arrayOf(now-100))
        Reminders.process(context)
        assertEquals(1,shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        assertTrue(store.snapshot().reminders.all{it.delivered})
        Reminders.process(context)
        assertEquals(1,shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        store.status(active,Status.DONE);Reminders.process(context)
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
    @Test fun deniedNotificationsRemainUndeliveredForLaterRetry() {
        Reminders.channels(context)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val now=System.currentTimeMillis()
        store.saveTask(Resolution(title="گزارش",owner="احمدی"),listOf(now+10000))
        store.writableDatabase.execSQL("UPDATE reminders SET at=?",arrayOf(now-100))
        Reminders.process(context)
        assertFalse(store.snapshot().reminders.single().delivered)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        Reminders.process(context)
        assertTrue(store.snapshot().reminders.single().delivered)
    }
    @Test fun schedulesEarliestTaskAndDailyReviewWithoutLosingNextReminder() {
        val now=System.currentTimeMillis()
        val id=store.saveTask(Resolution(title="گزارش",owner="احمدی"),listOf(now+60000,now+120000))
        Reminders.schedule(context)
        val alarms=shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(now+60000,alarms.nextScheduledAlarm!!.triggerAtTime)
        store.writableDatabase.execSQL("UPDATE reminders SET at=? WHERE at=?",arrayOf(now-100,now+60000))
        Reminders.process(context)
        assertEquals(now+120000,alarms.nextScheduledAlarm!!.triggerAtTime)
    }
    @Test fun dailyReviewCrossesMidnightAndUsesDeviceTimezone() {
        val now=ZonedDateTime.of(2026,10,23,23,30,0,0,ZoneId.of("Asia/Tehran"))
        assertEquals(now.toLocalDate().plusDays(1).atTime(9,0).atZone(now.zone).toInstant().toEpochMilli(),Reminders.nextDaily(now,listOf(LocalTime.of(9,0),LocalTime.of(16,0))))
        assertNull(Reminders.nextDaily(now,emptyList()))
    }
    @Test fun recordedWavHeaderMatchesOfflineRecognitionFormat() {
        val b=ByteBuffer.wrap(AudioCapture.wavHeader(32000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32036,b.getInt(4));assertEquals(16000,b.getInt(24));assertEquals(32000,b.getInt(28));assertEquals(32000,b.getInt(40));assertEquals(1,b.getShort(22).toInt());assertEquals(16,b.getShort(34).toInt())
    }
}
