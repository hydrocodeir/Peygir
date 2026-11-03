import {randomBytes,randomUUID,timingSafeEqual} from 'node:crypto';
import {mkdirSync,readFileSync,existsSync,unlinkSync} from 'node:fs';
import {join} from 'node:path';
import {openDatabase,SqliteD1} from './database.mjs';
import {passwordHash,sha,ApiError,requireValue,text} from '../../cloudflare/src/worker.mjs';

export const companyCode=value=>typeof value==='string' && /^[a-z0-9][a-z0-9-]{2,31}$/.test(value);
export const username=value=>typeof value==='string' && /^[a-z0-9][a-z0-9_.-]{2,63}$/.test(value);
export const secret=()=>randomBytes(24).toString('base64url');
export function equal(a,b) {const x=Buffer.from(a),y=Buffer.from(b);return x.length===y.length && timingSafeEqual(x,y);}
const migrations=['0001_shared.sql','0002_organization.sql','0003_contacts.sql','0004_push_accounts.sql','0005_web_push.sql','0006_midpoint.sql'].map(n=>readFileSync(new URL('../../cloudflare/migrations/'+n,import.meta.url),'utf8'));
export class Registry {
  constructor(dataDir) {
    this.dataDir=dataDir;this.tenantDir=join(dataDir,'tenants');mkdirSync(this.tenantDir,{recursive:true,mode:0o700});
    this.db=openDatabase(join(dataDir,'registry.sqlite'));this.pool=new Map();
    this.db.exec(`CREATE TABLE IF NOT EXISTS platform_admin(id INTEGER PRIMARY KEY CHECK(id=1),username TEXT UNIQUE NOT NULL,salt TEXT NOT NULL,password_hash TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS admin_sessions(hash TEXT PRIMARY KEY,csrf TEXT NOT NULL,expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS companies(id TEXT PRIMARY KEY,code TEXT UNIQUE NOT NULL,name TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,manager_id TEXT NOT NULL,created INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS platform_audit(id TEXT PRIMARY KEY,company_id TEXT,action TEXT NOT NULL,created INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS request_limits(key TEXT PRIMARY KEY,count INTEGER NOT NULL,expires INTEGER NOT NULL);`);
  }
  async bootstrap(value) {
    if(this.db.prepare('SELECT 1 FROM platform_admin').get()) return;
    if(!value || !username(value.username) || !text(value.password,256,true) || value.password.length<16) throw new Error('A bootstrap administrator file with a username and password (16+ characters) is required.');
    const salt=secret();this.db.prepare('INSERT INTO platform_admin VALUES(1,?,?,?)').run(value.username,salt,await passwordHash(value.password,salt));
  }
  audit(company,action) {this.db.prepare('INSERT INTO platform_audit VALUES(?,?,?,?)').run(randomUUID(),company,action,Date.now());}
  rate(key,max=10) {
    const now=Date.now();
    this.db.prepare(`INSERT INTO request_limits VALUES(?,1,?) ON CONFLICT(key) DO UPDATE SET count=CASE WHEN expires<? THEN 1 ELSE count+1 END,expires=CASE WHEN expires<? THEN excluded.expires ELSE expires END`).run(key,now+900000,now,now);
    if(this.db.prepare('SELECT count FROM request_limits WHERE key=?').get(key).count>max) throw new ApiError(429,'تلاش‌های زیاد؛ پانزده دقیقه بعد دوباره امتحان کنید.');
    this.db.prepare('DELETE FROM request_limits WHERE expires<?').run(now);
  }
  async adminLogin(name,password,ip) {
    this.rate('admin:'+ip);
    const a=this.db.prepare('SELECT * FROM platform_admin WHERE username=?').get(String(name));
    const hash=await passwordHash(String(password).slice(0,256),a?.salt || 'unknown-platform-admin');
    if(!a || !equal(hash,a.password_hash)) throw new ApiError(401,'نام کاربری یا رمز صحیح نیست.');
    const token=secret();const csrf=secret();const expires=Date.now()+8*3600000;
    this.db.prepare('DELETE FROM admin_sessions WHERE expires<?').run(Date.now());
    this.db.prepare('INSERT INTO admin_sessions VALUES(?,?,?)').run(await sha(token),csrf,expires);
    return {token,csrf,expires};
  }
  async adminSession(token) {if(!token || !/^[\w-]{32}$/.test(token)) return null;return this.db.prepare('SELECT * FROM admin_sessions WHERE hash=? AND expires>?').get(await sha(token),Date.now()) || null;}
  list() {return this.db.prepare('SELECT id,code,name,active,created FROM companies ORDER BY created DESC,id').all().map(r=>({...r,active:!!r.active}));}
  find(code) {if(!companyCode(code)) return null;return this.db.prepare('SELECT * FROM companies WHERE code=?').get(code) || null;}
  byId(id) {return this.db.prepare('SELECT * FROM companies WHERE id=?').get(id) || null;}
  lease(company) {
    let entry=this.pool.get(company.id);
    if(!entry) {
      if(!/^[a-f0-9-]{36}$/.test(company.id) || !existsSync(join(this.tenantDir,company.id+'.sqlite'))) throw new ApiError(503,'پایگاه شرکت آماده نیست.');
      entry={db:openDatabase(join(this.tenantDir,company.id+'.sqlite')),refs:0,last:Date.now()};for(const sql of migrations.slice(2)) entry.db.exec(sql);entry.d1=new SqliteD1(entry.db);this.pool.set(company.id,entry);
    }
    entry.refs++;entry.last=Date.now();
    let released=false;
    return {db:entry.db,d1:entry.d1,release:()=>{if(released)return;released=true;entry.refs--;entry.last=Date.now();const idle=[...this.pool].filter(([,e])=>!e.refs).sort((a,b)=>a[1].last-b[1].last);while(this.pool.size>16 && idle.length){const [id,e]=idle.shift();e.db.close();this.pool.delete(id);}}};
  }
  async create(input) {
    requireValue(companyCode(input.code) && text(input.name,200,true) && username(input.managerUsername || 'manager') && text(input.managerName,200,true),'نام شرکت، کد انگلیسی ۳ تا ۳۲ نویسه و مشخصات مدیر را وارد کنید.');
    requireValue(!this.find(input.code),'کد شرکت قبلاً استفاده شده است.');
    const password=input.managerPassword || secret();requireValue(text(password,256,true) && password.length>=12,'رمز مدیر باید حداقل ۱۲ نویسه باشد.');
    const id=randomUUID(),managerId=randomUUID(),salt=secret(),path=join(this.tenantDir,id+'.sqlite');
    // Hash before opening a file: no asynchronous operation can interleave with seeding.
    const hash=await passwordHash(password,salt);let db;
    try {
      db=openDatabase(path);db.exec('BEGIN IMMEDIATE');for(const sql of migrations) db.exec(sql);
      db.prepare('INSERT INTO workspaces VALUES(?,?)').run(id,input.name.trim());
      db.prepare("INSERT INTO users(id,workspace,username,name,role,access_role,salt,password_hash,iterations) VALUES(?,?,?,?,'manager','manager',?,?,100000)").run(managerId,id,input.managerUsername || 'manager',input.managerName.trim(),salt,hash);
      db.prepare('INSERT INTO organization_settings VALUES(?,1)').run(id);db.exec('COMMIT');db.close();db=null;
      this.db.exec('BEGIN IMMEDIATE');
      try {this.db.prepare('INSERT INTO companies VALUES(?,?,?,1,?,?)').run(id,input.code,input.name.trim(),managerId,Date.now());this.audit(id,'create');this.db.exec('COMMIT');} catch(e){this.db.exec('ROLLBACK');throw e;}
    } catch(e) {if(db) db.close();for(const p of [path,path+'-wal',path+'-shm']) if(existsSync(p)) unlinkSync(p);if(this.find(input.code)) throw new ApiError(409,'کد شرکت قبلاً استفاده شده است.');throw e;}
    return {company:this.byId(id),credentials:{company:input.code,username:input.managerUsername || 'manager',password}};
  }
  update(id,input) {
    const company=this.byId(id);requireValue(company,'شرکت پیدا نشد.');
    requireValue(text(input.name,200,true) && typeof input.active==='boolean');
    this.db.exec('BEGIN IMMEDIATE');try {this.db.prepare('UPDATE companies SET name=?,active=? WHERE id=?').run(input.name.trim(),input.active?1:0,id);this.audit(id,input.active?'activate':'suspend');this.db.exec('COMMIT');}catch(e){this.db.exec('ROLLBACK');throw e;}
    const lease=this.lease(company);try {lease.db.prepare('UPDATE workspaces SET name=? WHERE id=?').run(input.name.trim(),id);if(!input.active) lease.db.exec('DELETE FROM sessions; DELETE FROM push_devices; DELETE FROM push_outbox;');} finally {lease.release();}
  }
  async resetManager(id) {
    const c=this.byId(id);requireValue(c,'شرکت پیدا نشد.');const password=secret(),salt=secret(),hash=await passwordHash(password,salt);const lease=this.lease(c);
    try {const u=lease.db.prepare("SELECT id,username FROM users WHERE active=1 AND COALESCE(access_role,role)='manager' ORDER BY CASE WHEN id=? THEN 0 ELSE 1 END,id LIMIT 1").get(c.manager_id);requireValue(u,'مدیر فعال پیدا نشد.');await lease.d1.batch([
      lease.d1.prepare('UPDATE users SET salt=?,password_hash=?,active=1 WHERE id=?').bind(salt,hash,u.id),lease.d1.prepare('DELETE FROM sessions WHERE user_id=?').bind(u.id),lease.d1.prepare('DELETE FROM push_devices WHERE user_id=?').bind(u.id)
    ]);this.db.prepare('UPDATE companies SET manager_id=? WHERE id=?').run(u.id,id);this.audit(id,'reset-manager');return {company:c.code,username:u.username,password};}finally{lease.release();}
  }
  close() {for(const e of this.pool.values()) e.db.close();this.pool.clear();this.db.close();}
}
