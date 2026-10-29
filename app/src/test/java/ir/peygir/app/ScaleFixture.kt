package ir.peygir.app

import java.time.LocalDate

/** Synthetic company only; never imported into the user's app or production service. */
internal object ScaleFixture {
    val day=Solar.toDate(1405,8,7)!!.toEpochDay()
    val users=(0 until 50).map{i->CompanyUser("00000000-0000-4000-8000-${(i+1).toString().padStart(12,'0')}",
        listOf("علی احمدی","سارا کاظمی","رضا حسنی","سمیه رضایی","مهدی کریمی")[i%5]+" ${(i+1).fa()}",if(i==0) "manager" else if(i==1) "assistant" else "deputy",username="person${i+1}")}
    val meetings=(0 until 200).map{i->
        val date=when {i<20->day;i<110->day+1+(i-20)/3;else->day-1-(i-110)/3}
        val start=if(i<6) 540 else if(i<20) 600+(i-6)*45 else 480+i%8*60
        Meeting(id=(i+1).toLong(),title="بررسی پروژهٔ ${(i+1).fa()}",date=date,start=start,end=start+45,location="سالن ${(i%4+1).fa()}",
            attendees=(0 until 8).map{users[(i+it)%users.size].id},guests=listOf("مهمان ${(i%12).fa()}"),notes="دستور جلسهٔ آزمایشی و پیگیری برنامهٔ شرکت")
    }
    val tasks=(0 until 500).map{i->Resolution(id=(i+1).toLong(),meetingId=(i%200+1).toLong(),title="پیگیری گزارش ${(i+1).fa()}",description="تهیه و بررسی مستندات پروژه",
        owner=users[i%50].name,assignee=users[i%50].id,due=if(i<80) day else day+(i%25)-10,priority=Priority.entries[i%4],status=Status.entries[(i/5)%4])}
    val reminders=tasks.filter{it.status!=Status.DONE}.map{Reminder(it.id,it.id,LocalDate.ofEpochDay(it.due).atTime(8,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),false)}
    val data=Snapshot(meetings=meetings,tasks=tasks,reminders=reminders)
}
