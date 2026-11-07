package ir.peygir.app

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class LegacyStorageTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var store: Store
    @Before fun setup() {context.deleteDatabase("legacy-storage.db");store=Store(context,"legacy-storage.db")}
    @After fun close() {store.close()}
    @Test fun schemaThreeUpgradePreservesTasksAndFollowUps() {
        val id=store.saveTask(Resolution(title="مصوبهٔ قبلی",owner="رضایی"),emptyList())
        store.addFollowUp(id,"منتظر پاسخ")
        val before=store.snapshot()
        store.writableDatabase.execSQL("DROP TABLE backlog");store.writableDatabase.version=3;store.close()
        store=Store(context,"legacy-storage.db")
        assertEquals(before,store.snapshot());assertEquals(10,store.readableDatabase.version)
    }
    @Test fun openingVersionFourDataDoesNotDeleteOrScheduleLegacyEntries() {
        store.writableDatabase.execSQL("INSERT INTO backlog(title,description,owner,created) VALUES(?,?,?,?)",arrayOf<Any>("legacy entry","note","",1L))
        val before=store.snapshot();store.close();store=Store(context,"legacy-storage.db")
        assertEquals(before,store.snapshot());assertTrue(before.tasks.isEmpty());assertTrue(before.reminders.isEmpty())
        assertEquals("legacy entry",before.backlog.single().title)
    }
}
