package ir.peygir.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class VoicePeopleTest {
    @get:Rule val compose=createComposeRule()
    private val person=CompanyUser(UUID.randomUUID().toString(),"علی احمدی","deputy",username="ahmadi")
    @Before fun setup() {Organization.members.value=listOf(person);Organization.contacts.value=emptyList()}
    @After fun clear() {Organization.members.value=emptyList();Organization.contacts.value=emptyList()}
    @Test fun dictatedOwnerOffersTheAccountAndSelectingItAssignsTheTask() {
        var saved: Resolution?=null
        compose.setContent{CompositionLocalProvider(LocalDictationHandler provides {label,done->done(if(label=="عنوان مصوبه") "گزارش فروش" else "احمدی")}) {
            PeygirTheme("light"){TaskEditor(Resolution(),emptyList(),emptyList(),false,{},{t,_->saved=t},null)}
        }}
        compose.onNodeWithTag("input_write").assertDoesNotExist();compose.onNodeWithTag("input_voice").assertDoesNotExist()
        compose.onNodeWithContentDescription("ورود صوتی - عنوان مصوبه").performClick()
        compose.onNodeWithContentDescription("ورود صوتی - مسئول انجام").performClick()
        compose.onNodeWithTag("person_suggestion_${person.id}").performClick()
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        assertEquals(person.id,saved!!.assignee);assertEquals(person.name,saved!!.owner);assertEquals("گزارش فروش",saved!!.title)
    }
    @Test fun editingASelectedOwnerClearsAssignmentAndFreeTextStaysPossible() {
        var saved: Resolution?=null
        compose.setContent{PeygirTheme("light"){TaskEditor(Resolution(title="گزارش",owner=person.name,assignee=person.id),emptyList(),emptyList(),false,{},{t,_->saved=t},null)}}
        compose.onNodeWithText("مسئول انجام *").performTextReplacement("رضا رضایی")
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        assertNull(saved!!.assignee);assertEquals("رضا رضایی",saved!!.owner)
    }
    @Test fun participantsSupportDictationAccountsAndGuestsWithoutCreatingAccounts() {
        var saved: Meeting?=null;var words="احمدی"
        compose.setContent{CompositionLocalProvider(LocalDictationHandler provides {_,done->done(words)}){PeygirTheme("light"){MeetingEditor(Meeting(id=1,title="جلسه"),false,{},{saved=it})}}}
        compose.onNodeWithContentDescription("ورود صوتی - نام شرکت‌کننده").performClick()
        compose.onNodeWithTag("person_suggestion_${person.id}").performClick()
        words="سارا کاظمی"
        compose.onNodeWithContentDescription("ورود صوتی - نام شرکت‌کننده").performClick()
        compose.onNodeWithTag("add_guest").performScrollTo().performClick()
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        assertEquals(listOf(person.id),saved!!.attendees);assertEquals(listOf(words),saved!!.guests)
        assertEquals(listOf(person),Organization.members.value)
    }
    @Test fun dictationReplacesSelectionAndPersianSearchMatchesFirstAndLastNames() {
        val value=insertDictation(TextFieldValue("گزارش قدیمی فروش",TextRange(6,11)),"تازه")
        assertEquals("گزارش تازه فروش",value.text)
        assertEquals(person.id,PeopleSuggestions.choices(listOf(person),emptyList(),"علي").single().user!!.id)
        assertEquals(person.id,PeopleSuggestions.choices(listOf(person),emptyList(),"احمدی").single().user!!.id)
        assertTrue(PeopleSuggestions.choices(listOf(person.copy(active=false)),emptyList(),"").isEmpty())
    }
    @Test fun contactsAndGuestsSurviveBackupAndDisappearWhenCompanyDataIsCleared() {
        val context=RuntimeEnvironment.getApplication()
        val store=Store(context,"contacts-${UUID.randomUUID()}.db")
        try {
            store.saveTask(Resolution(title="کار",owner="رضا رضایی"),emptyList())
            store.saveMeeting(Meeting(title="مهمان",guests=listOf("سارا کاظمی")))
            val bytes=ByteArrayOutputStream().also{Backup.write(context,it,store)}.toByteArray()
            Backup.read(context,bytes.inputStream()).use {prepared->
                assertEquals(setOf("رضا رضایی","سارا کاظمی"),prepared.data.contacts.toSet())
                assertEquals(listOf("سارا کاظمی"),prepared.data.meetings.single().guests)
                assertEquals(listOf("سارا کاظمی"),MeetingCopies.draft(prepared.data.meetings.single()).guests)
            }
            store.deleteMeeting(store.snapshot().meetings.single().id)
            assertTrue(Organization.contactNames(store).contains("سارا کاظمی"))
            SharedStore(store).clearScopedData();assertTrue(store.snapshot().contacts.isEmpty())
        } finally {val name=store.databaseName;store.close();context.deleteDatabase(name)}
    }
    @Test fun dailyCalendarIsTheDefaultAndGuestNamesAreSearchable() {
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(),{},{})}}
        compose.onNodeWithTag("meeting_mode_DAY").assertIsSelected()
        assertTrue(meetingSearch(Meeting(title="بودجه",guests=listOf("سارا کاظمی")),emptyList(),"کاظمی"))
    }
    @Test fun voiceFiltersFixedRolesAndRequiresAnExplicitSelection() {
        var role="deputy"
        compose.setContent{CompositionLocalProvider(LocalDictationHandler provides {_,done->done("مدیر")}){PeygirTheme("light"){AccountRolePicker(role,listOf("manager","assistant","deputy")){role=it}}}}
        compose.onNodeWithContentDescription("ورود صوتی - نقش حساب").performClick()
        assertEquals("deputy",role)
        compose.onNodeWithTag("account_role_manager").performClick()
        assertEquals("manager",role)
    }
    @Test fun versionSixMigrationPreservesExistingDataAndPendingMeeting() {
        val c=RuntimeEnvironment.getApplication();val name="contacts-v6-test.db";c.deleteDatabase(name)
        val old=Store(c,name)
        // Recreate a real v6 schema; the test SQLite engine predates DROP COLUMN.
        val schema=old.readableDatabase.rawQuery("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT IN ('contacts','android_metadata') ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 ELSE 2 END",null).use{cur->buildList{while(cur.moveToNext()) add(cur.getString(0).replace(", guests TEXT NOT NULL DEFAULT '[]'",""))}}
        old.close();c.deleteDatabase(name)
        val db=c.openOrCreateDatabase(name,0,null)
        schema.forEach(db::execSQL)
        db.execSQL("INSERT INTO meetings(id,title,date,notes,start,end,location,attendees,repeat_rule) VALUES(1,'جلسهٔ قبلی',20000,'',540,600,'',?,'NONE')",arrayOf(org.json.JSONArray(listOf(person.id)).toString()))
        db.version=6;db.close()
        val upgraded=Store(c,name)
        try {
            assertEquals(8,upgraded.readableDatabase.version)
            assertEquals(listOf(person.id),upgraded.snapshot().meetings.single().attendees)
            assertTrue(upgraded.snapshot().contacts.isEmpty())
            val pending=SharedStore(upgraded).prepare().single().operation.getJSONObject("payload")
            assertEquals(0,pending.getJSONArray("guests").length())
        } finally {upgraded.close();c.deleteDatabase(name)}
    }

}
