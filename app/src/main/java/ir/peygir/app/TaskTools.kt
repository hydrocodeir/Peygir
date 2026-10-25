package ir.peygir.app

import android.content.Intent
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

object Owners {
    private val titles=setOf("آقا","آقای","خانم","جناب","سرکار","دکتر","مهندس")
    private fun words(value: String): List<String> = Normalizer.normalize(value,Normalizer.Form.NFKC)
        .replace('ي','ی').replace('ى','ی').replace('ك','ک').replace("ـ","")
        .replace(Regex("[\\u064B-\\u065F\\u0670]"),"").replace('\u200c',' ')
        .lowercase(Locale.ROOT).split(Regex("[\\s\\p{Punct}،؛]+"))
        .filter{it.isNotBlank()}.dropWhile{it in titles}
    fun key(value: String)=words(value).joinToString("")
    fun same(first: String, second: String)=key(first).isNotEmpty() && key(first)==key(second)
    fun suggestions(tasks: List<Resolution>, query: String): List<String> {
        val needles=words(query)
        return tasks.filter{key(it.owner).isNotEmpty()}.groupBy {key(it.owner)}.values
            .filter {group->needles.all{key(group.first().owner).contains(it)}}
            .sortedWith(compareByDescending<List<Resolution>>{it.size}.thenByDescending{it.maxOf{t->t.id}})
            .map{it.maxBy{t->t.id}.owner.trim()}.filter{it!=query.trim()}.take(5)
    }
    fun openTasks(tasks: List<Resolution>, owner: String)=tasks.filter{it.status!=Status.DONE && same(it.owner,owner)}
        .sortedWith(compareBy<Resolution>{it.due}.thenByDescending{it.priority.ordinal}.thenBy{it.id})
}

object TaskShare {
    private fun line(value: String)=value.trim().replace(Regex("\\s+")," ")
    fun text(task: Resolution)="مصوبه: ${line(task.title)}\nمسئول: ${line(task.owner)}\nسررسید: ${Solar.numeric(LocalDate.ofEpochDay(task.due)).fa()}"
    fun chooser(task: Resolution): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT,text(task)),"اشتراک‌گذاری مصوبه")
}
