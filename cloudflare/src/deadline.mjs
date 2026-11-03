import {ID,integer,text,requireValue} from './worker.mjs';

export function midpoint(payload) {
  if(!integer(payload.created,1) || ['DONE','REVIEW'].includes(payload.status)) return null;
  // Tehran has a fixed UTC+03:30 offset for the supported current deadlines.
  const end=(payload.due+1)*86400000-210*60000;
  return end>payload.created?Math.floor(payload.created+(end-payload.created)/2):null;
}
export const midpointKey=(id,p)=>`midpoint:${id}:${p.created}:${p.due}:${p.assignee}`;
export function validateExtension(e) {
  if(e===null || e===undefined) return;
  requireValue(e && typeof e==='object' && !Array.isArray(e) && Object.keys(e).sort().join(',')===['id','status','progress','reason','due','baseDue','requestedBy','requestedAt','decidedBy','decidedAt'].sort().join(','));
  requireValue(ID.test(e.id) && ['PENDING','APPROVED','REJECTED'].includes(e.status) && text(e.progress,1000,true) && text(e.reason,2000,true) && integer(e.due,-100000,100000) && integer(e.baseDue,-100000,100000) && e.due>e.baseDue && ID.test(e.requestedBy) && integer(e.requestedAt,1) && (e.decidedBy===null || ID.test(e.decidedBy)) && integer(e.decidedAt));
  requireValue(e.status==='PENDING'?e.decidedAt===0 && e.decidedBy===null:e.decidedAt>0 && e.decidedBy!==null);
}
export function transitionExtension(old,p,user,now) {
  const before=old.extension ?? null,e=p.extension ?? null;
  if(before?.status==='PENDING' && p.due!==old.due) requireValue(user.role==='manager' && e?.status==='APPROVED','تا بررسی درخواست تمدید توسط رئیس، مهلت فعلی باید حفظ شود.');
  if(before===e || before && e && Object.keys(before).length===Object.keys(e).length && Object.keys(before).every(k=>before[k]===e[k])) return;
  requireValue(e!==null,'درخواست تمدید قابل حذف نیست.');
  requireValue(!['DONE','REVIEW'].includes(old.status),'مصوبهٔ انجام‌شده یا منتظر تأیید قابل تمدید نیست.');
  if(e.status==='PENDING') {
    requireValue(before?.status!=='PENDING' && e.id!==before?.id && user.id===old.assignee && p.assignee===old.assignee && e.requestedBy===user.id && e.baseDue===old.due && p.due===old.due,'فقط مسئول مصوبه می‌تواند درخواست تازه ثبت کند؛ مهلت تا تأیید رئیس ثابت است.');
    requireValue(e.due>=Math.floor((now+210*60000)/86400000),'مهلت پیشنهادی نباید گذشته باشد.');
    p.extension={...e,requestedAt:now};
  } else {
    requireValue(user.role==='manager' && before?.status==='PENDING' && before.id===e.id && (e.status==='REJECTED' || before.baseDue===old.due),'بررسی درخواست جاری فقط با رئیس است؛ درخواست با مهلت قدیمی باید رد شود.');
    for(const key of ['progress','reason','due','baseDue','requestedBy','requestedAt']) requireValue(e[key]===before[key],'اطلاعات درخواست هنگام تصمیم‌گیری قابل تغییر نیست.');
    requireValue(e.status==='APPROVED'?p.due===e.due && e.due>=Math.floor((now+210*60000)/86400000):p.due===old.due,'رد درخواست نباید مهلت را تغییر دهد و تأیید باید همان تاریخ پیشنهادی را اعمال کند.');
    p.extension={...e,decidedBy:user.id,decidedAt:now};
  }
}
