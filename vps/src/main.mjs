import {createServer} from 'node:http';
import {readFileSync} from 'node:fs';
import {Readable} from 'node:stream';
import {createApp} from './app.mjs';

process.umask(0o077);
function file(name,required=false) {const p=process.env[name];if(!p){if(required)throw new Error(name+' is required');return null;}return JSON.parse(readFileSync(p,'utf8'));}
const publicOrigin=process.env.PUBLIC_ORIGIN;if(!publicOrigin)throw new Error('PUBLIC_ORIGIN is required');
const app=await createApp({dataDir:process.env.DATA_DIR || '/data',publicOrigin,bootstrap:file('ADMIN_BOOTSTRAP_FILE'),fcmService:file('FCM_SERVICE_ACCOUNT_FILE'),fcmPublic:file('FCM_PUBLIC_JSON_FILE')});
const server=createServer(async(req,res)=>{
  try {
    const chunks=[];let bytes=0;
    for await(const chunk of req){bytes+=chunk.length;if(bytes>4*1024*1024){res.writeHead(413,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify({error:'حجم درخواست زیاد است.'}));return;}chunks.push(chunk);}
    const ip=process.env.TRUST_PROXY==='1' ? String(req.headers['x-forwarded-for'] || req.socket.remoteAddress).split(',').at(-1).trim() : req.socket.remoteAddress;
    const request=new Request(new URL(req.url,publicOrigin),{method:req.method,headers:req.headers,...(!['GET','HEAD'].includes(req.method)?{body:Buffer.concat(chunks)}:{})});
    const response=await app.fetch(request,{ip});res.writeHead(response.status,Object.fromEntries(response.headers));
    if(response.body) Readable.fromWeb(response.body).pipe(res);else res.end();
  } catch {if(!res.headersSent)res.writeHead(500,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify({error:'ارتباط ناموفق بود.'}));}
});
server.requestTimeout=30000;server.headersTimeout=15000;server.maxHeadersCount=64;
server.listen(Number(process.env.PORT || 3000),process.env.HOST || '0.0.0.0',()=>console.log('Peygir VPS API ready'));
const timer=setInterval(()=>{void app.sweep().catch(()=>console.error('Push retry failed'));},60000);timer.unref();
let closing=false;async function shutdown(){if(closing)return;closing=true;clearInterval(timer);await new Promise(r=>server.close(r));await app.close();}
process.on('SIGTERM',()=>void shutdown());process.on('SIGINT',()=>void shutdown());
