const encoder = new TextEncoder();
export const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const kinds = new Set(['meeting', 'task', 'followup', 'history']);
class ApiError extends Error { constructor(status, message) { super(message); this.status = status; } }
function requireValue(ok, message = 'درخواست معتبر نیست.') { if (!ok) throw new ApiError(400, message); }
export function hex(bytes) { return [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, '0')).join(''); }
export async function sha(value) { return hex(await crypto.subtle.digest('SHA-256', encoder.encode(value))); }
export async function passwordHash(password, salt, iterations = 100000) {
  const key = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, ['deriveBits']);
  return hex(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: encoder.encode(salt), iterations }, key, 256));
}
function constantEqual(a, b) { let diff = a.length ^ b.length; for (let i = 0; i < Math.max(a.length,b.length); i++) diff |= (a.charCodeAt(i)||0) ^ (b.charCodeAt(i)||0); return diff === 0; }
function json(value, status = 200) { return Response.json(value, {status, headers: {'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}}); }
async function body(request) {
  requireValue((Number(request.headers.get('content-length')) || 0) <= 1024 * 1024);
  const bytes = await request.arrayBuffer(); requireValue(bytes.byteLength <= 1024 * 1024);
  try { const value = JSON.parse(new TextDecoder().decode(bytes)); requireValue(value && typeof value === 'object' && !Array.isArray(value)); return value; }
  catch (e) { if (e instanceof ApiError) throw e; throw new ApiError(400, 'ساختار درخواست معتبر نیست.'); }
}
function record(row) { return row ? {id:row.id,kind:row.kind,revision:row.revision,payload:JSON.parse(row.payload),deleted:!!row.deleted,actor:row.actor,changed:row.changed} : null; }
function text(value, max, nonempty = false) { return typeof value === 'string' && value.length <= max && (!nonempty || value.trim().length > 0); }
function integer(value, min = 0, max = Number.MAX_SAFE_INTEGER) { return Number.isSafeInteger(value) && value >= min && value <= max; }
export function validateOperation(op) {
  requireValue(op && ID.test(op.id) && ID.test(op.recordId) && kinds.has(op.kind) && integer(op.baseRevision) && typeof op.deleted === 'boolean');
  const p = op.payload; requireValue(p && typeof p === 'object' && !Array.isArray(p));
  const day = d => integer(d, -100000, 100000);
  const ref = d => d === null || (typeof d === 'string' && ID.test(d));
  let fields;
  if (op.kind === 'meeting') {
    fields = ['title','date','notes']; requireValue(text(p.title,1000,true) && day(p.date) && text(p.notes,50000));
  } else if (op.kind === 'task') {
    fields = ['title','description','owner','due','priority','status','meeting','reminders'];
    requireValue(text(p.title,1000,true) && text(p.description,50000) && text(p.owner,1000,true) && day(p.due) && ['LOW','NORMAL','HIGH','URGENT'].includes(p.priority) && ['WAITING','ACTIVE','DONE'].includes(p.status) && ref(p.meeting));
    requireValue(Array.isArray(p.reminders) && p.reminders.length <= 64 && p.reminders.every(v => integer(v,1)) && new Set(p.reminders).size === p.reminders.length);
  } else if (op.kind === 'followup') {
    fields = ['task','text','created']; requireValue(ref(p.task) && p.task !== null && text(p.text,1000,true) && integer(p.created,1));
  } else {
    fields = ['task','old','new','changed','kind','reason']; requireValue(ref(p.task) && p.task !== null && day(p.old) && day(p.new) && integer(p.changed,1) && ['EXTEND','POSTPONE','EDIT'].includes(p.kind) && text(p.reason,50000));
  }
  requireValue(Object.keys(p).length === fields.length && fields.every(k => Object.hasOwn(p,k)));
}
async function authenticate(request, env) {
  const token = request.headers.get('authorization')?.match(/^Bearer ([a-f0-9]{64})$/)?.[1];
  if (!token) throw new ApiError(401,'ابتدا وارد حساب شوید.');
  const user = await env.DB.prepare('SELECT u.id,u.workspace,u.name,u.role,w.name AS workspace_name FROM sessions s JOIN users u ON u.id=s.user_id JOIN workspaces w ON w.id=u.workspace WHERE s.token_hash=? AND s.expires>? AND u.active=1').bind(await sha(token), Date.now()).first();
  if (!user) throw new ApiError(401,'نشست ورود پایان یافته است؛ دوباره وارد شوید.');
  return {...user, tokenHash:await sha(token)};
}
async function login(request, env) {
  const b = await body(request); requireValue(text(b.username,100,true) && text(b.password,256,true));
  const username = b.username.trim().toLowerCase();
  const key = await sha('login|' + (request.headers.get('cf-connecting-ip') || 'local'));
  const now = Date.now();
  await env.DB.prepare('INSERT INTO login_limits(key,count,expires) VALUES(?,1,?) ON CONFLICT(key) DO UPDATE SET count=CASE WHEN expires<? THEN 1 ELSE count+1 END,expires=CASE WHEN expires<? THEN excluded.expires ELSE expires END').bind(key,now+900000,now,now).run();
  const limit = await env.DB.prepare('SELECT count FROM login_limits WHERE key=?').bind(key).first();
  if (limit.count > 10) throw new ApiError(429,'تلاش‌های ورود زیاد است؛ پانزده دقیقه بعد امتحان کنید.');
  const user = await env.DB.prepare('SELECT u.*,w.name AS workspace_name FROM users u JOIN workspaces w ON w.id=u.workspace WHERE u.username=? AND u.active=1').bind(username).first();
  // Keep the expensive path for unknown accounts too; credentials are provisioned privately, never by this API.
  const computed = await passwordHash(b.password,user?.salt || 'unknown-account-salt',user?.iterations || 100000);
  if (!user || !constantEqual(computed,user.password_hash)) throw new ApiError(401,'نام کاربری یا رمز صحیح نیست.');
  const token = hex(crypto.getRandomValues(new Uint8Array(32)));
  const statements = [
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires) VALUES(?,?,?)').bind(await sha(token),user.id,now+90*86400000),
    env.DB.prepare('DELETE FROM sessions WHERE expires<?').bind(now),
    env.DB.prepare('DELETE FROM login_limits WHERE expires<? OR key=?').bind(now,key),
  ];
  await env.DB.batch(statements);
  return json({token,workspace:user.workspace,workspaceName:user.workspace_name,name:user.name,role:user.role,userId:user.id});
}
async function getRecord(env, workspace, id) { return record(await env.DB.prepare('SELECT * FROM records WHERE workspace=? AND id=?').bind(workspace,id).first()); }
async function mutate(env, user, op) {
  validateOperation(op);
  const fingerprint = await sha(JSON.stringify(op));
  const existing = await env.DB.prepare('SELECT fingerprint,response FROM operations WHERE workspace=? AND id=?').bind(user.workspace,op.id).first();
  if (existing) { requireValue(existing.fingerprint === fingerprint,'شناسهٔ عملیات تکراری با محتوای متفاوت است.'); return JSON.parse(existing.response); }
  const previous = await getRecord(env,user.workspace,op.recordId);
  if ((previous?.revision || 0) !== op.baseRevision || (previous && previous.kind !== op.kind)) return {opId:op.id,ok:false,record:previous};
  if (!op.deleted) {
    const parent = op.kind === 'task' ? op.payload.meeting : ['followup','history'].includes(op.kind) ? op.payload.task : null;
    if (parent) {
      const row = await getRecord(env,user.workspace,parent);
      if (!row || row.deleted || row.kind !== (op.kind==='task'?'meeting':'task')) return {opId:op.id,ok:false,record:{...(previous || {id:op.recordId,kind:op.kind,revision:0,payload:op.payload,deleted:true,actor:user.name,changed:Date.now()}),reason:'parent_deleted'}};
    }
  }
  const now = Date.now(), next = op.baseRevision+1, payload=JSON.stringify(op.payload);
  const result = {opId:op.id,ok:true,record:{id:op.recordId,kind:op.kind,revision:next,payload:op.payload,deleted:op.deleted,actor:user.name,changed:now}};
  // D1 batch is transactional. CAS and the operation receipt commit together; retries never create another revision.
  const statements = [
    env.DB.prepare(`INSERT INTO records(workspace,id,kind,revision,payload,deleted,actor,changed,operation)
      SELECT ?,?,?,?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM operations WHERE workspace=? AND id=?)
      ON CONFLICT(workspace,id) DO UPDATE SET revision=excluded.revision,payload=excluded.payload,deleted=excluded.deleted,actor=excluded.actor,changed=excluded.changed,operation=excluded.operation
      WHERE records.revision=? AND records.kind=? AND ?>0`).bind(user.workspace,op.recordId,op.kind,next,payload,op.deleted?1:0,user.name,now,op.id,user.workspace,op.id,op.baseRevision,op.kind,op.baseRevision),
    env.DB.prepare(`INSERT OR IGNORE INTO operations(workspace,id,fingerprint,response) SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM records WHERE workspace=? AND id=? AND operation=?)`).bind(user.workspace,op.id,fingerprint,JSON.stringify(result),user.workspace,op.recordId,op.id),
  ];
  if (op.deleted && op.kind==='task') statements.push(env.DB.prepare(`UPDATE records SET revision=revision+1,deleted=1,actor=?,changed=?,operation=? WHERE workspace=? AND kind IN ('followup','history') AND deleted=0 AND json_extract(payload,'$.task')=? AND EXISTS(SELECT 1 FROM records WHERE workspace=? AND id=? AND operation=? AND deleted=1)`).bind(user.name,now,op.id+'-cascade',user.workspace,op.recordId,user.workspace,op.recordId,op.id));
  if (op.deleted && op.kind==='meeting') statements.push(env.DB.prepare(`UPDATE records SET revision=revision+1,payload=json_set(payload,'$.meeting',NULL),actor=?,changed=?,operation=? WHERE workspace=? AND kind='task' AND deleted=0 AND json_extract(payload,'$.meeting')=? AND EXISTS(SELECT 1 FROM records WHERE workspace=? AND id=? AND operation=? AND deleted=1)`).bind(user.name,now,op.id+'-detach',user.workspace,op.recordId,user.workspace,op.recordId,op.id));
  await env.DB.batch(statements);
  const receipt = await env.DB.prepare('SELECT fingerprint,response FROM operations WHERE workspace=? AND id=?').bind(user.workspace,op.id).first();
  if (receipt) { requireValue(receipt.fingerprint===fingerprint); return JSON.parse(receipt.response); }
  return {opId:op.id,ok:false,record:await getRecord(env,user.workspace,op.recordId)};
}
async function sync(request,env,user) {
  const b=await body(request); requireValue(integer(b.cursor) && Array.isArray(b.operations) && b.operations.length<=20);
  const results=[]; for (const op of b.operations) results.push(await mutate(env,user,op));
  const rows=await env.DB.prepare('SELECT seq,id,kind,revision,payload,deleted,actor,changed FROM changes WHERE workspace=? AND seq>? ORDER BY seq LIMIT 200').bind(user.workspace,b.cursor).all();
  return json({results,changes:rows.results.map(r=>({...record(r),seq:r.seq})),cursor:rows.results.at(-1)?.seq ?? b.cursor,hasMore:rows.results.length===200});
}
export default {
  async fetch(request,env) {
    try {
      const url=new URL(request.url);
      if (url.pathname==='/health' && request.method==='GET') return json({service:'peygir-sync',protocol:1});
      if (url.pathname==='/v1/login' && request.method==='POST') return await login(request,env);
      const user=await authenticate(request,env);
      if (url.pathname==='/v1/logout' && request.method==='POST') {await env.DB.prepare('DELETE FROM sessions WHERE token_hash=?').bind(user.tokenHash).run();return json({ok:true});}
      if (url.pathname==='/v1/sync' && request.method==='POST') return await sync(request,env,user);
      return json({error:'مسیر پیدا نشد.'},404);
    } catch (e) { return json({error:e instanceof ApiError?e.message:'ارتباط با فضای مشترک ناموفق بود؛ دوباره امتحان کنید.'},e instanceof ApiError?e.status:500); }
  }
};
