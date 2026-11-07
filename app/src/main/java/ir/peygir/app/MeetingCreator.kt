package ir.peygir.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Author of the initial registration, independent of subsequent edits or account deletion. */
internal object MeetingCreator {
    fun create(db: SQLiteDatabase) {
        val exists=db.rawQuery("PRAGMA table_info(meetings)",null).use{c->var found=false;while(c.moveToNext()) if(c.getString(1)=="creator") found=true;found}
        if(!exists) db.execSQL("ALTER TABLE meetings ADD COLUMN creator TEXT")
    }
    fun current(context: Context)=runCatching{SharedCredentials(context).read()?.name?.takeIf{it.isNotBlank()}}.getOrNull() ?: "ثبت محلی در این گوشی"
    fun read(p: JSONObject): String? {
        if(!p.has("creator") || p.isNull("creator")) return null
        val value=p.get("creator")
        require(value is String && value.isNotBlank() && value.length<=200) {"نام ثبت‌کنندهٔ جلسه معتبر نیست."}
        return value
    }
}
