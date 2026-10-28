import {readFileSync} from 'node:fs';
import {Registry,equal} from './registry.mjs';
import {instanceLock} from './database.mjs';
import worker,{ApiError,body,json} from '../../cloudflare/src/worker.mjs';
import {flushPush} from '../../cloudflare/src/organization.mjs';

const assets=new Map([
  ['/admin/',['index.html','text/html; charset=utf-8']],['/admin/app.js',['app.js','text/javascript; charset=utf-8']],['/admin/style.css',['style.css','text/css; charset=utf-8']]
]);
const cookieName='peygir_owner';
export async function createApp({dataDir,publicOrigin,bootstrap,fcmService=null,fcmPublic=null}) {
  const origin=new URL(publicOrigin).origin;
  const secure=origin.startsWith('https://');
  if(!secure && !['127.0.0.1','localhost','[::1]'].includes(new URL(origin).hostname)) throw new Error('Public origin must use HTTPS.');
  const unlock=instanceLock(dataDir);let registry;
  try {registry=new Registry(dataDir);await registry.bootstrap(bootstrap);}catch(e){registry?.close();unlock();throw e;}
  let stopping=false;const pending=new Set();let sweepRunning=false;
  const fcm=!!(fcmService?.private_key && fcmService?.client_email && fcmService?.project_id && fcmPublic?.projectId && fcmPublic?.senderId && fcmPublic?.apiKey && fcmPublic?.applicationId);
  if(fcm && fcmService.project_id!==fcmPublic.projectId) {registry.close();unlock();throw new Error('FCM project IDs must match.');}
  const environment=(lease,c)=>({DB:lease.d1,COMPANY_CODE:c.code,ALLOW_ASSISTANT_ACCOUNTS:true,ACCOUNT_MANAGEMENT_V2:true,CONTACTS_ENABLED:true,...(fcm?{FCM_SERVICE_ACCOUNT:JSON.stringify(fcmService),FCM_PUBLIC_JSON:JSON.stringify(fcmPublic)}:{})});
  function track(promise) {pending.add(promise);void promise.finally(()=>pending.delete(promise)).catch(()=>{});}
  const cookie=token=>`${cookieName}=${token}; Path=/admin; HttpOnly; SameSite=Strict; Max-Age=${token?28800:0}${secure?'; Secure':''}`;
  async function admin(request,path,ip) {
    if(request.method!=='GET' && request.headers.get('origin')!==origin) throw new ApiError(403,'درخواست از نشانی مجاز ارسال نشده است.');
    if(path==='/admin/api/login' && request.method==='POST') {
      const b=await body(request,8192);const login=await registry.adminLogin(b.username,b.password,ip);
      const response=json({ok:true,csrf:login.csrf});response.headers.set('Set-Cookie',cookie(login.token));return response;
    }
    const token=request.headers.get('cookie')?.split(';').map(s=>s.trim()).find(s=>s.startsWith(cookieName+'='))?.slice(cookieName.length+1);
    const session=await registry.adminSession(token);if(!session) throw new ApiError(401,'ابتدا وارد مدیریت سامانه شوید.');
    if(request.method!=='GET' && !equal(request.headers.get('x-csrf-token') || '',session.csrf)) throw new ApiError(403,'درخواست معتبر نیست؛ صفحه را دوباره باز کنید.');
    if(path==='/admin/api/session' && request.method==='GET') return json({csrf:session.csrf,pushConfigured:fcm});
    if(path==='/admin/api/logout' && request.method==='POST') {registry.db.prepare('DELETE FROM admin_sessions WHERE hash=?').run(session.hash);const r=json({ok:true});r.headers.set('Set-Cookie',cookie(''));return r;}
    if(path==='/admin/api/companies' && request.method==='GET') return json({companies:registry.list()});
    if(path==='/admin/api/companies' && request.method==='POST') {
      const result=await registry.create(await body(request,8192));const {password,...rest}=result.credentials;
      return json({company:{id:result.company.id,code:result.company.code,name:result.company.name,active:true},credentials:{...rest,password,endpoint:origin}},201);
    }
    const match=path.match(/^\/admin\/api\/companies\/([a-f0-9-]{36})\/(update|reset-manager)$/);
    if(match && request.method==='POST') {
      if(match[2]==='update') {registry.update(match[1],await body(request,8192));return json({ok:true});}
      const credentials=await registry.resetManager(match[1]);return json({credentials:{...credentials,endpoint:origin}});
    }
    throw new ApiError(404,'مسیر پیدا نشد.');
  }
  async function dispatch(request,ip) {
    const url=new URL(request.url),path=url.pathname;
    if(stopping) throw new ApiError(503,'سرویس در حال راه‌اندازی مجدد است.');
    if(path==='/health' && request.method==='GET') return json({service:'peygir-vps',version:'4.2.0',organizationProtocol:2,multiTenant:true,companyCodeRequired:true,accountManagement:2});
    if((path==='/' || path==='/admin') && request.method==='GET') return new Response(null,{status:302,headers:{Location:'/admin/'}});
    if(assets.has(path) && request.method==='GET') {const [name,type]=assets.get(path);return new Response(readFileSync(new URL('../public/'+name,import.meta.url)),{headers:{'Content-Type':type}});}
    if(path==='/admin/font.ttf' && request.method==='GET') return new Response(readFileSync(new URL('../../app/src/main/res/font/vazirmatn_regular.ttf',import.meta.url)),{headers:{'Content-Type':'font/ttf'}});
    if(path.startsWith('/admin/api/')) return admin(request,path,ip);
    if(!path.startsWith('/v2/') && path!=='/v1/logout') throw new ApiError(404,'مسیر پیدا نشد.');
    if(request.method!=='POST') throw new ApiError(405,'روش درخواست مجاز نیست.');
    if(path==='/v2/login') registry.rate('company-login:'+ip,100);
    const company=registry.find(request.headers.get('x-peygir-company'));
    if(!company?.active) throw new ApiError(401,'کد شرکت یا اطلاعات ورود معتبر نیست، یا شرکت غیرفعال است.');
    const lease=registry.lease(company);const tasks=[];
    try {
      // Never trust a client-supplied Cloudflare header for rate limiting on the VPS.
      const headers=new Headers(request.headers);headers.set('cf-connecting-ip',ip);
      let response=await worker.fetch(new Request(request,{headers}),environment(lease,company),{waitUntil(p){tasks.push(Promise.resolve(p));}});
      if(path==='/v2/login' && response.ok) {const value=await response.json();response=json({...value,company:company.code});}
      return response;
    } finally {
      // Keep the database leased until immediate push work has finished, without delaying HTTP.
      if(tasks.length) track(Promise.allSettled(tasks).finally(()=>lease.release()));else lease.release();
    }
  }
  return {
    registry,
    async fetch(request,{ip='127.0.0.1'}={}) {
      let response;
      try {response=await dispatch(request,ip);}catch(e){response=json({error:e instanceof ApiError?e.message:'عملیات ناموفق بود؛ دوباره امتحان کنید.'},e instanceof ApiError?e.status:500);}
      response.headers.set('Cache-Control','no-store');response.headers.set('X-Content-Type-Options','nosniff');response.headers.set('Referrer-Policy','no-referrer');
      response.headers.set('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
      return response;
    },
    async sweep() {
      if(stopping || sweepRunning || !fcm)return;sweepRunning=true;
      try {for(const c of registry.list().filter(c=>c.active)){const lease=registry.lease(c);try{await flushPush(environment(lease,c));}finally{lease.release();}}}finally{sweepRunning=false;}
    },
    async close() {stopping=true;await Promise.allSettled([...pending]);while(sweepRunning)await new Promise(r=>setTimeout(r,10));registry.close();unlock();}
  };
}
