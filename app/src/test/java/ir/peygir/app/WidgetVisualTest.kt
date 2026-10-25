package ir.peygir.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Native rendering of the actual RemoteViews layout and row layout, without a launcher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetVisualTest {
    @Test fun rendersWidgetLightDarkAndCompact() {
        val base=RuntimeEnvironment.getApplication() as Context
        base.getSharedPreferences("review_widgets",0).edit().clear().commit()
        val today=LocalDate.now().toEpochDay()
        val tasks=listOf(Resolution(id=1,title="آماده‌سازی گزارش عملکرد پاییز",owner="خانم رضایی",due=today,priority=Priority.HIGH),Resolution(id=2,title="پیگیری قرارداد تأمین‌کننده",owner="آقای احمدی",due=today,status=Status.ACTIVE),Resolution(id=3,title="ارسال صورت‌جلسه",owner="خانم محمدی",due=today,status=Status.DONE))
        val light=base.createConfigurationContext(Configuration(base.resources.configuration).apply{uiMode=Configuration.UI_MODE_NIGHT_NO})
        val dark=base.createConfigurationContext(Configuration(base.resources.configuration).apply{uiMode=Configuration.UI_MODE_NIGHT_YES})
        render(light,tasks,360,440,"widget-light")
        render(dark,tasks,360,440,"widget-dark")
        render(light,tasks,300,300,"widget-compact")
        render(light,emptyList(),360,320,"widget-empty")
    }
    private fun render(context: Context, tasks: List<Resolution>, width: Int, height: Int, name: String) {
        val root=ReviewWidget.build(context,1,Snapshot(tasks=tasks)).apply(context,FrameLayout(context))
        // A launcher normally binds this adapter. Supply the same real row RemoteViews for rendering.
        if(tasks.isNotEmpty()) root.findViewById<ListView>(R.id.widget_list).adapter=object: BaseAdapter() {
            override fun getCount()=tasks.size
            override fun getItem(position: Int)=tasks[position]
            override fun getItemId(position: Int)=tasks[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup)=ReviewWidget.row(context,tasks[position]).apply(context,parent)
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        root.layout(0,0,width,height)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file=File("../verification/screenshots/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
