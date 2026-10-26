package ir.peygir.app

import android.app.Application
import android.content.Context
import android.content.Intent
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class TaskToolsTest {
    private lateinit var context: Context
    private lateinit var store: Store
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("tools.db");store=Store(context,"tools.db")
    }
    @After fun close() {store.close()}
    @Test fun suggestionsMatchFirstLastPartialAndReorderedNamesWithPersianVariants() {
        val tasks=listOf(Resolution(id=1,owner="آقای علی احمدی"),Resolution(id=2,owner="خانم سارا کریمی"),Resolution(id=3,owner="رضا احمدی"))
        listOf("علی","علي","احمدی علی","علی احم").forEach {assertEquals(listOf("آقای علی احمدی"),Owners.suggestions(tasks,it))}
        assertEquals(setOf("رضا احمدی","آقای علی احمدی"),Owners.suggestions(tasks,"احمدی").toSet())
        assertEquals(listOf("خانم سارا کریمی"),Owners.suggestions(tasks,"كريمي"))
    }
    @Test fun normalizedDuplicateOwnersAreSuggestedOnceAndCommonSurnamesStaySeparate() {
        val tasks=listOf(Resolution(id=1,owner="آقای علي احمدي"),Resolution(id=2,owner="علی احمدی"),Resolution(id=3,owner="رضا احمدی"))
        assertEquals(listOf("علی احمدی","رضا احمدی"),Owners.suggestions(tasks,"احمد"))
        assertEquals(listOf(1L,2L),Owners.openTasks(tasks,"جناب آقای علی احمدی").map{it.id})
        assertFalse(Owners.same("احمدی","علی احمدی"));assertTrue(Owners.same("محمد‌رضا كريمي","محمدرضا کریمی"))
    }
    @Test fun ownerReviewOnlyIncludesThatPersonsUnfinishedWorkAndSortsDueDates() {
        val day=LocalDate.now().toEpochDay()
        val tasks=listOf(Resolution(id=1,owner="علی احمدی",due=day+1),Resolution(id=2,owner="آقای علی احمدی",due=day-1),Resolution(id=3,owner="رضا احمدی",due=day-2),Resolution(id=4,owner="علی احمدی",due=day,status=Status.DONE))
        assertEquals(listOf(2L,1L),Owners.openTasks(tasks,"علی احمدی").map{it.id})
    }
    @Test fun followUpsHaveAutomaticTimestampsPersistInOrderAndCascadeWithTaskDeletion() {
        val meeting=store.saveMeeting(Meeting(title="مدیریت"))
        val task=store.saveTask(Resolution(title="گزارش",owner="احمدی",meetingId=meeting),emptyList())
        val before=System.currentTimeMillis();store.addFollowUp(task,"  با احمدی صحبت شد؛\nمنتظر تأیید مالی است.  ")
        val second=store.addFollowUp(task,"گزارش دریافت شد")
        val data=store.snapshot();assertEquals(second,data.followUps.first().id)
        assertEquals("با احمدی صحبت شد؛ منتظر تأیید مالی است.",data.followUps.last().text)
        assertTrue(data.followUps.all{it.created in before..System.currentTimeMillis()})
        store.deleteMeeting(meeting);assertEquals(data.followUps,store.snapshot().followUps)
        store.close();store=Store(context,"tools.db");assertEquals(data.followUps,store.snapshot().followUps)
        store.deleteTask(task);assertTrue(store.snapshot().followUps.isEmpty())
    }
    @Test fun invalidFollowUpsDoNotInsertRows() {
        val task=store.saveTask(Resolution(title="اصل",owner="احمدی"),emptyList())
        assertThrows(Exception::class.java) {store.addFollowUp(task,"  ")}
        assertThrows(Exception::class.java) {store.addFollowUp(task,"x".repeat(1001))}
        assertThrows(Exception::class.java) {store.addFollowUp(999,"نامعتبر")}
        assertTrue(store.snapshot().followUps.isEmpty())
    }
    @Test fun schemaTwoUpgradeKeepsEveryExistingRecord() {
        val task=store.saveTask(Resolution(title="قدیمی",owner="احمدی"),listOf(System.currentTimeMillis()+60000))
        val before=store.snapshot()
        store.writableDatabase.execSQL("DROP TABLE follow_ups");store.writableDatabase.execSQL("DROP TABLE notification_tokens");store.writableDatabase.execSQL("DROP TABLE backlog")
        store.writableDatabase.version=2;store.close();store=Store(context,"tools.db")
        assertEquals(before,store.snapshot());assertEquals(5,store.readableDatabase.version)
        store.addFollowUp(task,"پس از مهاجرت");assertEquals(1,store.snapshot().followUps.size)
    }
    @Test fun sharingUsesTheSystemChooserAndOnlyTheRequestedPlainTextFields() {
        val task=Resolution(title=" گزارش\nفروش ",owner="علی احمدی",description="یادداشت خصوصی",due=Solar.toDate(1405,8,15)!!.toEpochDay())
        val chooser=TaskShare.chooser(task)
        assertEquals(Intent.ACTION_CHOOSER,chooser.action)
        @Suppress("DEPRECATION") val intent=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND,intent.action);assertEquals("text/plain",intent.type)
        assertEquals("مصوبه: گزارش فروش\nمسئول: علی احمدی\nسررسید: ۱۴۰۵/۰۸/۱۵",intent.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM));assertFalse(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains(task.description))
    }
}
