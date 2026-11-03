import {ApiError,authenticate,body,json,record,getRecord,requireValue,text,integer,ID,sha,passwordHash,hex,login,validateOperation} from './worker.mjs';
import {sendPush} from './push.mjs';
import {midpoint,midpointKey,validateExtension,transitionExtension} from './deadline.mjs';

const admin = u => u.role === 'assistant' || u.role === 'manager';
const ref = v => v === null || (typeof v === 'string' && ID.test(v));
const day = v => integer(v,-100000,100000);
const same = (a,b) => JSON.stringify(a) === JSON.stringify(b);
function contactKey(name) {return name.normalize('NFKC').replace(/[يى]/g,'ی').replace(/ك/g,'ک').replace(/[\u064b-\u065f\u0670ـ]/g,'').replace(/^(?:(?:آقا|آقای|خانم|جناب|سرکار|دکتر|مهندس)\s+)+/,'').replace(/[\s\u200c\p{P}]/gu,'').toLowerCase();}
export function normalize(kind,value) {
  if(kind==='meeting') return {start:null,end:null,location:'',attendees:[],guests:[],repeat:'NONE',until:null,series:null,reserved:false,...value};
  if(kind==='task') return {assignee:null,submitted:0,approved:0,approvedBy:null,created:0,extension:null,...value};
  return {...value};
}
function validate(op) {
  const p=op.payload, core={...p};
  if(op.kind==='meeting') {
    requireValue(p.reserved===undefined || typeof p.reserved==='boolean');
    delete core.reserved;
    requireValue((op.deleted && p.start===null && p.end===null) || (integer(p.start,0,1438) && integer(p.end,p.start+1,1439)),'ساعت پایان باید بعد از شروع و در همان روز باشد.');
    requireValue(text(p.location,2000) && Array.isArray(p.attendees) && p.attendees.length<=200 && p.attendees.every(x=>typeof x==='string' && ID.test(x)) && new Set(p.attendees).size===p.attendees.length);
    requireValue(p.guests===undefined || Array.isArray(p.guests) && p.guests.length<=200 && p.guests.every(n=>text(n,200,true) && contactKey(n).length>0) && new Set(p.guests.map(contactKey)).size===p.guests.length);
    requireValue(['NONE','DAILY','WEEKLY','MONTHLY'].includes(p.repeat) && ref(p.series) && (p.until===null || day(p.until)));
    requireValue(p.repeat==='NONE' || (p.series!==null && p.until>=p.date && p.until-p.date<=366));
    for(const k of ['start','end','location','attendees','guests','repeat','until','series']) delete core[k];
  } else if(op.kind==='task') {
    requireValue(p.created===undefined || integer(p.created));validateExtension(p.extension);delete core.created;delete core.extension;
    requireValue(ref(p.assignee) && integer(p.submitted) && integer(p.approved) && ref(p.approvedBy));
    for(const k of ['assignee','submitted','approved','approvedBy']) delete core[k];
    if(core.status==='REVIEW') core.status='ACTIVE';
  }
  validateOperation({...op,payload:core});
}
export async function visible(env,user,r) {
  if(admin(user)) return true;
  if(r.kind==='meeting') return normalize(r.kind,r.payload).attendees.includes(user.id);
  if(r.kind==='task') return normalize(r.kind,r.payload).assignee===user.id;
  const parent=await getRecord(env,user.workspace,r.payload.task);
  return parent && !parent.deleted && normalize('task',parent.payload).assignee===user.id;
}
async function projected(env,user,r,visibleMeetings=null) {
  const p=normalize(r.kind,r.payload);
  if(r.kind==='task' && !p.created) p.created=r.changed;
  if(!admin(user) && r.kind==='task' && p.meeting) {
    if(visibleMeetings) {if(!visibleMeetings.has(p.meeting)) p.meeting=null;}
    else {const m=await getRecord(env,user.workspace,p.meeting);if(!m || m.deleted || !await visible(env,user,m)) p.meeting=null;}
  }
  const out={...r,payload:p};out.view=await sha(JSON.stringify(p));return out;
}
function revoked(op) {
  return {id:op.recordId,kind:op.kind,revision:op.baseRevision+1,deleted:true,revoked:true,payload:{},actor:'',changed:Date.now(),view:'revoked'};
}
async function people(env,user) {
  const rows=await env.DB.prepare('SELECT id,name,username,COALESCE(access_role,role) AS role,active FROM users WHERE workspace=? ORDER BY name,id').bind(user.workspace).all();
  return rows.results.map(r=>admin(user)?r:{id:r.id,name:r.name,role:r.role,active:r.active});
}
async function validatePeople(env,user,ids) {
  if(!ids.length) return;
  const rows=await env.DB.prepare(`SELECT id FROM users WHERE workspace=? AND active=1 AND id IN (${ids.map(()=>'?').join(',')})`).bind(user.workspace,...ids).all();
  requireValue(rows.results.length===new Set(ids).size,'یکی از حساب‌های انتخاب‌شده غیرفعال است یا در شرکت شما نیست.');
}
async function mutate(env,user,op) {
  validate(op);
  const previous=await getRecord(env,user.workspace,op.recordId);
  // Check current authorization before receipts, stale-version replies, or parent errors.
  if(!admin(user) && (!previous && op.kind!=='followup' || previous && !await visible(env,user,previous))) return {opId:op.id,ok:false,denied:true,record:revoked(op)};
  const fingerprint=await sha(JSON.stringify(op));
  const receipt=await env.DB.prepare('SELECT fingerprint,response,user_id FROM operations WHERE workspace=? AND id=?').bind(user.workspace,op.id).first();
  if(receipt) {
    requireValue(receipt.user_id===user.id && receipt.fingerprint===fingerprint,'شناسهٔ عملیات متعلق به این درخواست نیست.');
    const result=JSON.parse(receipt.response);
    // A cached receipt must never reveal a task after reassignment or an invitation withdrawal.
    if(!admin(user) && !await visible(env,user,result.record)) return {opId:op.id,ok:false,denied:true,record:revoked(op)};
    return {...result,record:await projected(env,user,result.record)};
  }
  if((previous?.revision||0)!==op.baseRevision || previous && previous.kind!==op.kind) return {opId:op.id,ok:false,record:previous?await projected(env,user,previous):revoked(op)};
  let p={...op.payload};
  if(op.kind==='meeting' && !Object.hasOwn(p,'reserved')) p.reserved=previous?.payload.reserved ?? false;
  if(op.kind==='task') {
    if(!Object.hasOwn(p,'created')) p.created=previous?.payload.created || previous?.changed || 0;
    if(!Object.hasOwn(p,'extension')) p.extension=previous?.payload.extension ?? null;
    if(previous) p.created=previous.payload.created || previous.changed;
  }
  if(op.kind==='meeting' && !Object.hasOwn(p,'guests')) p.guests=previous?.payload.guests || [];
  if(!admin(user)) {
    requireValue(!op.deleted,'معاون اجازهٔ حذف ندارد.');
    if(op.kind==='task') {
      const old=(await projected(env,user,previous)).payload;
      for(const key of Object.keys(old)) if(!['status','reminders','extension'].includes(key)) requireValue(same(old[key],p[key]),'فقط وضعیت و زمان یادآوری مصوبهٔ خودتان قابل تغییر است.');
      requireValue(['WAITING','ACTIVE','REVIEW'].includes(p.status) && old.status!=='DONE','تأیید نهایی فقط با رئیس است.');
      p.meeting=previous.payload.meeting;
    } else if(op.kind==='followup') {
      requireValue(!previous,'گزارش ثبت‌شده قابل بازنویسی نیست.');
      const parent=await getRecord(env,user.workspace,p.task);
      if(!parent || parent.deleted || !await visible(env,user,parent)) return {opId:op.id,ok:false,denied:true,record:revoked(op)};
    } else throw new ApiError(403,'این تغییر فقط در دسترس رئیس و دستیار است.');
  }
  if(!op.deleted) {
    if(op.kind==='meeting' && p.reserved) {
      const linked=await env.DB.prepare("SELECT 1 FROM records WHERE workspace=? AND kind='task' AND deleted=0 AND json_extract(payload,'$.meeting')=? LIMIT 1").bind(user.workspace,op.recordId).first();
      requireValue(!linked,'جلسهٔ دارای مصوبه را نمی‌توان رزرو کرد.');
    }
    const parentId=op.kind==='task'?p.meeting:['followup','history'].includes(op.kind)?p.task:null;
    if(parentId) {
      const parent=await getRecord(env,user.workspace,parentId);
      if(!parent || parent.deleted || parent.kind!==(op.kind==='task'?'meeting':'task')) return {opId:op.id,ok:false,record:{...(previous?await projected(env,user,previous):revoked(op)),reason:'parent_deleted',revoked:false}};
    }
    if(op.kind==='task' && p.meeting) {const parent=await getRecord(env,user.workspace,p.meeting);requireValue(!parent?.payload.reserved,'برای جلسهٔ رزرو نمی‌توان مصوبه ثبت کرد؛ ابتدا رزرو را بردارید.');}
    if(admin(user)) {
      if(op.kind==='meeting') await validatePeople(env,user,p.attendees.filter(x=>!normalize('meeting',previous?.payload||{}).attendees?.includes(x)));
      if(op.kind==='task' && p.assignee!==normalize('task',previous?.payload||{}).assignee) await validatePeople(env,user,p.assignee?[p.assignee]:[]);
    }
  }
  const now=Date.now();
  if(op.kind==='task') {
    const old=normalize('task',previous?.payload||{});
    if(!p.created) p.created=previous?.changed || now;
    requireValue(p.created<=now+60000,'زمان ثبت مصوبه معتبر نیست.');
    if(!op.deleted) transitionExtension(old,p,user,now);
    if(p.assignee!==old.assignee && old.extension?.status==='PENDING') p.extension=null;
    if(p.assignee && p.status==='DONE' && old.status!=='DONE') requireValue(user.role==='manager' && old.status==='REVIEW','انجام مصوبهٔ واگذارشده باید ابتدا گزارش و سپس توسط رئیس تأیید شود.');
    if(user.role!=='manager' && p.assignee) requireValue(p.status!=='DONE' || old.status==='DONE','تأیید نهایی فقط با رئیس است.');
    p.submitted=p.status==='REVIEW'?(old.status==='REVIEW'?old.submitted:now):old.submitted||0;
    p.approved=p.status==='DONE'?(old.status==='DONE'?old.approved:now):0;
    p.approvedBy=p.status==='DONE'?(old.status==='DONE'?old.approvedBy:user.id):null;
    if(p.assignee!==old.assignee) {requireValue(['WAITING','ACTIVE'].includes(p.status),'برای تغییر مسئول، وضعیت را در انتظار یا در حال انجام بگذارید.');p.submitted=0;p.approved=0;p.approvedBy=null;}
  }
  const next=op.baseRevision+1;
  const result={opId:op.id,ok:true,record:{id:op.recordId,kind:op.kind,revision:next,payload:p,deleted:op.deleted,actor:user.name,changed:now}};
  const guard=admin(user)?'1':op.kind==='task'?"EXISTS(SELECT 1 FROM records g WHERE g.workspace=? AND g.id=? AND g.deleted=0 AND json_extract(g.payload,'$.assignee')=?)":"EXISTS(SELECT 1 FROM records g WHERE g.workspace=? AND g.id=? AND g.kind='task' AND g.deleted=0 AND json_extract(g.payload,'$.assignee')=?)";
  const guardArgs=admin(user)?[]:[user.workspace,op.kind==='task'?op.recordId:p.task,user.id];
  const reservationGuard=op.deleted?'1':op.kind==='task' && p.meeting?"NOT EXISTS(SELECT 1 FROM records m WHERE m.workspace=? AND m.id=? AND m.deleted=0 AND json_extract(m.payload,'$.reserved')=1)":op.kind==='meeting' && p.reserved?"NOT EXISTS(SELECT 1 FROM records t WHERE t.workspace=? AND t.kind='task' AND t.deleted=0 AND json_extract(t.payload,'$.meeting')=?)":'1';
  const reservationArgs=reservationGuard==='1'?[]:[user.workspace,op.kind==='task'?p.meeting:op.recordId];
  const statements=[
    env.DB.prepare(`INSERT INTO records(workspace,id,kind,revision,payload,deleted,actor,changed,operation)
      SELECT ?,?,?,?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM operations WHERE workspace=? AND id=?) AND ${guard} AND ${reservationGuard}
      ON CONFLICT(workspace,id) DO UPDATE SET revision=excluded.revision,payload=excluded.payload,deleted=excluded.deleted,actor=excluded.actor,changed=excluded.changed,operation=excluded.operation
      WHERE records.revision=? AND records.kind=? AND ?>0`).bind(user.workspace,op.recordId,op.kind,next,JSON.stringify(p),op.deleted?1:0,user.name,now,op.id,user.workspace,op.id,...guardArgs,...reservationArgs,op.baseRevision,op.kind,op.baseRevision),
    env.DB.prepare('INSERT OR IGNORE INTO operations(workspace,id,fingerprint,response,user_id) SELECT ?,?,?,?,? WHERE EXISTS(SELECT 1 FROM records WHERE workspace=? AND id=? AND operation=?)').bind(user.workspace,op.id,fingerprint,JSON.stringify(result),user.id,user.workspace,op.recordId,op.id),
  ];
  const committed="EXISTS(SELECT 1 FROM records WHERE workspace=? AND id=? AND operation=?)";
  if(op.deleted && op.kind==='task') statements.push(env.DB.prepare(`UPDATE records SET revision=revision+1,deleted=1,actor=?,changed=?,operation=? WHERE workspace=? AND kind IN ('followup','history') AND deleted=0 AND json_extract(payload,'$.task')=? AND ${committed}`).bind(user.name,now,op.id+'-cascade',user.workspace,op.recordId,user.workspace,op.recordId,op.id));
  if(op.deleted && op.kind==='meeting') statements.push(env.DB.prepare(`UPDATE records SET revision=revision+1,payload=json_set(payload,'$.meeting',NULL),actor=?,changed=?,operation=? WHERE workspace=? AND kind='task' AND deleted=0 AND json_extract(payload,'$.meeting')=? AND ${committed}`).bind(user.name,now,op.id+'-detach',user.workspace,op.recordId,user.workspace,op.recordId,op.id));
  if(env.CONTACTS_ENABLED && admin(user) && !op.deleted) {
    const names=op.kind==='meeting'?p.guests:op.kind==='task' && !p.assignee?[p.owner]:[];
    for(const name of names.filter(n=>text(n,200,true) && contactKey(n))) statements.push(env.DB.prepare(`INSERT OR IGNORE INTO contacts(workspace,key,name) SELECT ?,?,? WHERE ${committed}`).bind(user.workspace,contactKey(name),name.trim(),user.workspace,op.recordId,op.id));
  }
  // Durable, transactional outbox: a crash after committing cannot lose the push event.
  let taskRecord=op.kind==='task'?result.record:['followup','history'].includes(op.kind)?await getRecord(env,user.workspace,p.task):null;
  const recipients=new Set();
  const chiefs=await env.DB.prepare("SELECT id FROM users WHERE workspace=? AND active=1 AND COALESCE(access_role,role)='manager'").bind(user.workspace).all();
  chiefs.results.forEach(x=>recipients.add(x.id));
  if(op.kind==='meeting') p.attendees.forEach(x=>recipients.add(x));
  if(taskRecord?.payload.assignee) recipients.add(taskRecord.payload.assignee);
  recipients.delete(user.id);
  for(const recipient of recipients) {
    const extensionChanged=op.kind==='task' && !same(previous?.payload.extension ?? null,p.extension ?? null);
    const kind=extensionChanged && p.extension?.status==='PENDING'?'extensionRequest':extensionChanged && ['APPROVED','REJECTED'].includes(p.extension?.status)?'extensionDecision':!op.deleted && op.kind==='meeting' && p.attendees.includes(recipient) && !normalize('meeting',previous?.payload||{}).attendees.includes(recipient)?'invitation':!op.deleted && op.kind==='task' && p.assignee===recipient && p.assignee!==normalize('task',previous?.payload||{}).assignee?'assignment':op.kind==='meeting'?'meeting':p.status==='REVIEW'?'review':'task';
    statements.push(env.DB.prepare(`INSERT OR IGNORE INTO push_outbox(id,workspace,user_id,record_id,kind,created) SELECT ?,?,?,?,?,? WHERE ${committed}`).bind(op.id+'|'+recipient,user.workspace,recipient,op.kind==='followup'||op.kind==='history'?p.task:op.recordId,kind,now,user.workspace,op.recordId,op.id));
    if(env.WEB_PUSH_ENABLED) statements.push(env.DB.prepare(`INSERT OR IGNORE INTO web_push_outbox(id,workspace,user_id,record_id,kind,created) SELECT ?,?,?,?,?,? WHERE ${committed}`).bind(op.id+'|'+recipient,user.workspace,recipient,op.kind==='followup'||op.kind==='history'?p.task:op.recordId,kind,now,user.workspace,op.recordId,op.id));
  }
  await env.DB.batch(statements);
  const saved=await env.DB.prepare('SELECT response,user_id,fingerprint FROM operations WHERE workspace=? AND id=?').bind(user.workspace,op.id).first();
  if(saved) {requireValue(saved.user_id===user.id && saved.fingerprint===fingerprint);return {...JSON.parse(saved.response),record:await projected(env,user,JSON.parse(saved.response).record)};}
  const latest=await getRecord(env,user.workspace,op.recordId);
  return {opId:op.id,ok:false,record:latest && await visible(env,user,latest)?await projected(env,user,latest):revoked(op)};
}
async function sync(request,env,user,ctx) {
  const b=await body(request,4*1024*1024);
  requireValue(b.protocol===2 && Array.isArray(b.operations) && b.operations.length<=20 && Array.isArray(b.inventory) && b.inventory.length<=10000);
  const inventory=new Map();
  for(const i of b.inventory) {requireValue(ID.test(i.id) && ['task','meeting','history','followup'].includes(i.kind) && integer(i.revision) && text(i.view,64));inventory.set(i.id,i);}
  await env.DB.prepare('INSERT INTO organization_settings(workspace,enabled) VALUES(?,1) ON CONFLICT(workspace) DO UPDATE SET enabled=1').bind(user.workspace).run();
  const results=[];for(const op of b.operations) results.push(await mutate(env,user,op));
  const rows=await env.DB.prepare(`SELECT * FROM records r WHERE workspace=? ${admin(user)?'':`AND (
    (r.kind='task' AND json_extract(r.payload,'$.assignee')=?) OR
    (r.kind='meeting' AND EXISTS(SELECT 1 FROM json_each(json_extract(r.payload,'$.attendees')) a WHERE a.value=?)) OR
    (r.kind IN ('history','followup') AND EXISTS(SELECT 1 FROM records t WHERE t.workspace=r.workspace AND t.id=json_extract(r.payload,'$.task') AND t.kind='task' AND t.deleted=0 AND json_extract(t.payload,'$.assignee')=?)))`}
    ORDER BY CASE r.kind WHEN 'meeting' THEN 0 WHEN 'task' THEN 1 ELSE 2 END,r.changed,r.id`).bind(user.workspace,...(admin(user)?[]:[user.id,user.id,user.id])).all();
  const changes=[],allowed=new Set();
  const visibleMeetings=new Set(rows.results.filter(r=>r.kind==='meeting' && !r.deleted).map(r=>r.id));
  for(const row of rows.results) {
    allowed.add(row.id);const known=inventory.get(row.id);
    if(row.deleted && !known) continue;
    const r=await projected(env,user,record(row),visibleMeetings);
    if(!known || known.revision!==r.revision || known.view!==r.view) changes.push(r);
  }
  // Return only generic revocations for IDs the caller already knows. Never historical private payloads.
  for(const [id,i] of inventory) if(!allowed.has(id)) changes.unshift(revoked({recordId:id,kind:i.kind,baseRevision:i.revision}));
  ctx?.waitUntil(flushPush(env));
  return json({protocol:2,results,changes:changes.slice(0,200),cursor:0,hasMore:changes.length>200,users:await people(env,user),contacts:env.CONTACTS_ENABLED && admin(user)?(await env.DB.prepare('SELECT name FROM contacts WHERE workspace=? ORDER BY name LIMIT 10000').bind(user.workspace).all()).results.map(r=>r.name):[],userId:user.id,role:user.role,name:user.name,workspaceName:user.workspace_name,pushConfigured:!!env.FCM_SERVICE_ACCOUNT && !!env.FCM_PUBLIC_JSON,accountRouting:1});
}
/** Account mutations use one transactional authorization decision, including the last-manager check. */
async function manageAccounts(request,env,user) {
  if(!admin(user)) throw new ApiError(403,'مدیریت حساب‌ها فقط برای مدیر و دستیار است.');
  const b=await body(request);
  if(b.action==='list') return json({users:await people(env,user)});
  requireValue(['create','update','reset','delete'].includes(b.action) && ID.test(b.id));
  const previous=await env.DB.prepare('SELECT * FROM users WHERE workspace=? AND id=?').bind(user.workspace,b.id).first();
  const oldRole=previous && (previous.access_role || previous.role);
  if(b.action!=='create') {
    requireValue(previous,'حساب پیدا نشد.');
    if(user.role==='assistant' && oldRole!=='deputy') throw new ApiError(403,'دستیار فقط حساب کاربران را مدیریت می‌کند.');
    if(b.expected && (b.expected.name!==previous.name || b.expected.username!==previous.username || b.expected.role!==oldRole || b.expected.active!==!!previous.active)) throw new ApiError(409,'اطلاعات حساب تغییر کرده است؛ فهرست را تازه کنید.');
  }
  let name,loginName,role,active,salt,hash;
  if(b.action==='create' || b.action==='update') {
    name=b.name;loginName=b.username===undefined && previous?previous.username:b.username;role=b.role===undefined && previous?oldRole:b.role;
    requireValue(text(name,200,true) && typeof loginName==='string' && /^[a-z0-9][a-z0-9_.-]{2,63}$/.test(loginName) && ['manager','assistant','deputy'].includes(role),'نام، نام کاربری انگلیسی معتبر و نقش را مشخص کنید.');
    if(user.role!=='manager' && role!=='deputy') throw new ApiError(403,'تعیین نقش مدیر یا دستیار فقط با مدیر است.');
    active=b.action==='create'?true:b.active;requireValue(typeof active==='boolean');
    if(b.action==='update' && b.id===user.id && !active) throw new ApiError(400,'نمی‌توانید حساب خودتان را غیرفعال کنید.');
    if(b.action==='create') requireValue(!previous,'این شناسهٔ حساب قبلاً استفاده شده است.');
  }
  if(b.action==='delete' && b.id===user.id) throw new ApiError(400,'برای حذف حساب خودتان، از مدیر دیگری بخواهید این کار را انجام دهد.');
  if(b.action==='create' || b.action==='reset') {
    requireValue(text(b.password,256,true) && b.password.length>=12,'رمز باید حداقل ۱۲ نویسه باشد.');salt=hex(crypto.getRandomValues(new Uint8Array(16)));hash=await passwordHash(b.password,salt);
  }
  const audit=crypto.randomUUID(),now=Date.now();
  let guard=`EXISTS(SELECT 1 FROM users a JOIN sessions s ON s.user_id=a.id WHERE a.workspace=? AND a.id=? AND a.active=1 AND COALESCE(a.access_role,a.role)=? AND s.token_hash=? AND s.expires>?)`;
  const args=[user.workspace,user.id,user.role,user.tokenHash,now];
  if(b.action==='create') {
    guard+=' AND NOT EXISTS(SELECT 1 FROM users WHERE id=? OR username=?) AND NOT EXISTS(SELECT 1 FROM account_audit WHERE workspace=? AND target=? AND action=\'delete\')';args.push(b.id,loginName,user.workspace,b.id);
  } else {
    guard+=' AND EXISTS(SELECT 1 FROM users WHERE workspace=? AND id=? AND name=? AND username=? AND active=? AND COALESCE(access_role,role)=? AND salt=? AND password_hash=?)';args.push(user.workspace,b.id,previous.name,previous.username,previous.active,oldRole,previous.salt,previous.password_hash);
    if(b.action==='update') {guard+=' AND NOT EXISTS(SELECT 1 FROM users WHERE username=? AND id<>?)';args.push(loginName,b.id);}
    if(previous.active && oldRole==='manager' && (b.action==='delete' || b.action==='update' && (!active || role!=='manager'))) {
      guard+=' AND EXISTS(SELECT 1 FROM users WHERE workspace=? AND id<>? AND active=1 AND COALESCE(access_role,role)=\'manager\')';args.push(user.workspace,b.id);
    }
  }
  const decided='EXISTS(SELECT 1 FROM account_audit WHERE id=?)';
  const statements=[env.DB.prepare(`INSERT INTO account_audit SELECT ?,?,?,?,?,? WHERE ${guard}`).bind(audit,user.workspace,user.id,b.id,b.action,now,...args)];
  if(b.action==='create') statements.push(env.DB.prepare(`INSERT INTO users(id,workspace,username,name,role,access_role,salt,password_hash,iterations) SELECT ?,?,?,?,?,?,?,?,100000 WHERE ${decided}`).bind(b.id,user.workspace,loginName,name.trim(),role==='manager'?'manager':'assistant',role,salt,hash,audit));
  if(b.action==='update') statements.push(env.DB.prepare(`UPDATE users SET name=?,username=?,role=?,access_role=?,active=? WHERE workspace=? AND id=? AND ${decided}`).bind(name.trim(),loginName,role==='manager'?'manager':'assistant',role,active?1:0,user.workspace,b.id,audit));
  if(b.action==='reset') statements.push(env.DB.prepare(`UPDATE users SET salt=?,password_hash=?,iterations=100000 WHERE workspace=? AND id=? AND ${decided}`).bind(salt,hash,user.workspace,b.id,audit));
  const revoke=['delete','reset'].includes(b.action) || b.action==='update' && (loginName!==previous.username || role!==oldRole || !active);
  if(revoke) {
    statements.push(env.DB.prepare(`DELETE FROM push_devices WHERE user_id=? AND ${decided}`).bind(b.id,audit),env.DB.prepare(`DELETE FROM sessions WHERE user_id=? AND ${decided}`).bind(b.id,audit),env.DB.prepare(`DELETE FROM push_outbox WHERE user_id=? AND ${decided}`).bind(b.id,audit));
  }
  if(b.action==='delete') statements.push(env.DB.prepare(`DELETE FROM users WHERE workspace=? AND id=? AND ${decided}`).bind(user.workspace,b.id,audit));
  const result=await env.DB.batch(statements);
  if(!result[0].meta?.changes) throw new ApiError(409,'حساب تغییر کرده، نام کاربری تکراری است یا آخرین مدیر فعال قابل حذف و تغییر نقش نیست؛ فهرست را تازه کنید.');
  return json({ok:true,reauthenticate:revoke && b.id===user.id,users:await people(env,user)});
}
async function accounts(request,env,user) {
  if(env.ACCOUNT_MANAGEMENT_V2) return manageAccounts(request,env,user);
  if(!admin(user)) throw new ApiError(403,'مدیریت حساب‌ها فقط برای رئیس و دستیار است.');
  const b=await body(request);
  if(b.action==='list') return json({users:await people(env,user)});
  requireValue(['create','update','reset'].includes(b.action) && ID.test(b.id));
  const previous=await env.DB.prepare('SELECT * FROM users WHERE workspace=? AND id=?').bind(user.workspace,b.id).first();
  const statements=[];let password;
  if(b.action==='create') {
    const allowedRole=b.role==='deputy' || (env.ALLOW_ASSISTANT_ACCOUNTS && user.role==='manager' && b.role==='assistant');
    requireValue(!previous && text(b.name,200,true) && /^[a-z0-9][a-z0-9_.-]{2,63}$/.test(b.username) && allowedRole,'نام، نام کاربری یکتا و نقش مجاز را مشخص کنید.');
    requireValue(text(b.password,256,true) && b.password.length>=12,'رمز باید حداقل ۱۲ نویسه باشد.');
    const salt=hex(crypto.getRandomValues(new Uint8Array(16)));password=b.password;
    const existing=await env.DB.prepare('SELECT 1 FROM users WHERE username=?').bind(b.username).first();requireValue(!existing,'این نام کاربری قبلاً استفاده شده است.');
    statements.push(env.DB.prepare("INSERT INTO users(id,workspace,username,name,role,access_role,salt,password_hash,iterations) VALUES(?,?,?,?,'assistant',?,?,?,100000)").bind(b.id,user.workspace,b.username,b.name.trim(),b.role,salt,await passwordHash(password,salt)));
    statements.push(env.DB.prepare('INSERT INTO organization_settings(workspace,enabled) VALUES(?,1) ON CONFLICT(workspace) DO UPDATE SET enabled=1').bind(user.workspace));
  } else {
    requireValue(previous,'حساب پیدا نشد.');
    if(env.ALLOW_ASSISTANT_ACCOUNTS && user.role==='assistant' && (previous.access_role || previous.role)!=='deputy') throw new ApiError(403,'دستیار فقط حساب معاون‌ها را مدیریت می‌کند.');
    if(b.action==='update') {
      requireValue(text(b.name,200,true) && typeof b.active==='boolean');
      requireValue(previous.id!==user.id || b.active,'نمی‌توانید حساب خودتان را غیرفعال کنید.');
      if((previous.access_role||previous.role)==='manager' && !b.active) {
        const count=await env.DB.prepare("SELECT count(*) AS n FROM users WHERE workspace=? AND active=1 AND COALESCE(access_role,role)='manager'").bind(user.workspace).first();requireValue(count.n>1,'حداقل یک حساب رئیس باید فعال بماند.');
      }
      statements.push(env.DB.prepare('UPDATE users SET name=?,active=? WHERE workspace=? AND id=?').bind(b.name.trim(),b.active?1:0,user.workspace,b.id));
      if(!b.active) statements.push(env.DB.prepare('DELETE FROM sessions WHERE user_id=?').bind(b.id),env.DB.prepare('DELETE FROM push_devices WHERE user_id=?').bind(b.id));
    } else {
      requireValue(text(b.password,256,true) && b.password.length>=12,'رمز باید حداقل ۱۲ نویسه باشد.');password=b.password;
      const salt=hex(crypto.getRandomValues(new Uint8Array(16)));
      statements.push(env.DB.prepare('UPDATE users SET salt=?,password_hash=?,iterations=100000 WHERE workspace=? AND id=?').bind(salt,await passwordHash(password,salt),user.workspace,b.id),env.DB.prepare('DELETE FROM sessions WHERE user_id=?').bind(b.id),env.DB.prepare('DELETE FROM push_devices WHERE user_id=?').bind(b.id));
    }
  }
  statements.push(env.DB.prepare('INSERT INTO account_audit VALUES(?,?,?,?,?,?)').bind(crypto.randomUUID(),user.workspace,user.id,b.id,b.action,Date.now()));
  await env.DB.batch(statements);return json({ok:true,users:await people(env,user)});
}
export async function enqueueMidpoints(env,now=Date.now()) {
  const rows=await env.DB.prepare("SELECT * FROM records WHERE kind='task' AND deleted=0 AND json_extract(payload,'$.assignee') IS NOT NULL").all();
  for(const r of rows.results) {
    const p=normalize('task',JSON.parse(r.payload));if(!p.created) p.created=r.changed;
    const at=midpoint(p);if(at===null || at>now) continue;
    const key=r.workspace+':'+midpointKey(r.id,p);
    if(await env.DB.prepare('SELECT 1 FROM midpoint_events WHERE key=?').bind(key).first()) continue;
    await env.DB.batch([
      env.DB.prepare("INSERT OR IGNORE INTO push_outbox(id,workspace,user_id,record_id,kind,created) SELECT ?,?,?,?,'midpoint',? WHERE NOT EXISTS(SELECT 1 FROM midpoint_events WHERE key=?)").bind(key,r.workspace,p.assignee,r.id,now,key),
      env.DB.prepare('INSERT OR IGNORE INTO midpoint_events(key,created) VALUES(?,?)').bind(key,now),
    ]);
  }
}
export async function flushPush(env,deliver=sendPush) {
  if(!env.FCM_SERVICE_ACCOUNT) return;
  await enqueueMidpoints(env);
  const rows=await env.DB.prepare('SELECT * FROM push_outbox WHERE next_attempt<=? ORDER BY created LIMIT 30').bind(Date.now()).all();
  for(const item of rows.results) {
    const user=await env.DB.prepare('SELECT id,workspace,COALESCE(access_role,role) AS role,active FROM users WHERE id=?').bind(item.user_id).first();
    const r=await getRecord(env,item.workspace,item.record_id);
    if(!user?.active || !r || r.deleted || !await visible(env,user,r) || item.kind==='midpoint' && (midpoint({...r.payload,created:r.payload.created || r.changed})===null || item.id!==r.workspace+':'+midpointKey(r.id,{...r.payload,created:r.payload.created || r.changed}) || r.payload.assignee!==user.id)) {await env.DB.prepare('DELETE FROM push_outbox WHERE id=?').bind(item.id).run();continue;}
    const devices=await env.DB.prepare("SELECT d.token,'' AS route FROM push_devices d JOIN sessions s ON s.token_hash=d.session_hash WHERE d.user_id=? AND d.enabled=1 AND s.expires>? UNION ALL SELECT d.token,d.route FROM push_accounts d JOIN sessions s ON s.token_hash=d.session_hash WHERE d.user_id=? AND d.enabled=1 AND s.expires>?").bind(user.id,Date.now(),user.id,Date.now()).all();
    let retry=false;
    for(const device of devices.results) {
      try {const status=await deliver(env,device.token,{userId:user.id,eventId:item.id,recordId:item.record_id,kind:item.kind,...(device.route?{accountRoute:device.route}:{})});if(status==='invalid') {await env.DB.prepare('DELETE FROM push_devices WHERE token=?').bind(device.token).run();await env.DB.prepare('DELETE FROM push_accounts WHERE token=?').bind(device.token).run();}else if(status!=='ok') retry=true;}
      catch {retry=true;}
    }
    if(retry && item.attempts<8) await env.DB.prepare('UPDATE push_outbox SET attempts=attempts+1,next_attempt=? WHERE id=?').bind(Date.now()+Math.min(3600000,30000*2**item.attempts),item.id).run();
    else await env.DB.prepare('DELETE FROM push_outbox WHERE id=?').bind(item.id).run();
  }
}
export async function organizationRoute(request,env,ctx) {
  const path=new URL(request.url).pathname;
  requireValue(request.method==='POST');
  if(path==='/v2/login') return login(request,env);
  const user=await authenticate(request,env);
  if(path==='/v2/sync') return sync(request,env,user,ctx);
  if(path==='/v2/users') return accounts(request,env,user);
  if(path==='/v2/config') return json({firebase:env.FCM_PUBLIC_JSON?JSON.parse(env.FCM_PUBLIC_JSON):null,pushConfigured:!!env.FCM_SERVICE_ACCOUNT && !!env.FCM_PUBLIC_JSON,accountRouting:1});
  if(path==='/v2/device') {
    const b=await body(request);requireValue(text(b.token,4096,true) && typeof b.enabled==='boolean');
    if(b.accountRoute!==undefined) {
      requireValue(typeof b.accountRoute==='string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(b.accountRoute));
      const owner=await env.DB.prepare('SELECT user_id FROM push_accounts WHERE route=?').bind(b.accountRoute).first();
      requireValue(!owner || owner.user_id===user.id,'این شناسهٔ دستگاه متعلق به حساب دیگری است.');
      await env.DB.prepare('INSERT INTO push_accounts(route,token,user_id,session_hash,enabled,updated) VALUES(?,?,?,?,?,?) ON CONFLICT(route) DO UPDATE SET token=excluded.token,session_hash=excluded.session_hash,enabled=excluded.enabled,updated=excluded.updated WHERE push_accounts.user_id=excluded.user_id').bind(b.accountRoute,b.token,user.id,user.tokenHash,b.enabled?1:0,Date.now()).run();
      await env.DB.prepare('DELETE FROM push_devices WHERE token=? AND user_id=?').bind(b.token,user.id).run();
      return json({ok:true,accountRoute:b.accountRoute});
    }
    await env.DB.prepare('INSERT INTO push_devices(token,user_id,session_hash,enabled,updated) VALUES(?,?,?,?,?) ON CONFLICT(token) DO UPDATE SET user_id=excluded.user_id,session_hash=excluded.session_hash,enabled=excluded.enabled,updated=excluded.updated').bind(b.token,user.id,user.tokenHash,b.enabled?1:0,Date.now()).run();return json({ok:true});
  }
  throw new ApiError(404,'مسیر پیدا نشد.');
}
