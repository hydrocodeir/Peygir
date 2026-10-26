// Generates private initial credentials and SQL. No password is written to stdout or committed source.
import {mkdir,writeFile,access} from 'node:fs/promises';
import {resolve} from 'node:path';
import {passwordHash,hex} from '../src/worker.mjs';
const dir=resolve(import.meta.dirname,'../../.device-private/cloudflare');await mkdir(dir,{recursive:true});
const credentialsFile=resolve(dir,'credentials.json');
try {await access(credentialsFile);throw new Error('Credentials already exist. Use the existing private files; provisioning was not repeated.');} catch(e) {if(e.code!=='ENOENT') throw e;}
const workspace=crypto.randomUUID(),users=[];
for(const [username,name,role] of [['assistant','دستیار','assistant'],['manager','مدیر','manager']]) {
  const password=hex(crypto.getRandomValues(new Uint8Array(16))),salt=hex(crypto.getRandomValues(new Uint8Array(16)));
  users.push({id:crypto.randomUUID(),username,name,role,password,salt,hash:await passwordHash(password,salt)});
}
const quote=v=>"'"+String(v).replaceAll("'","''")+"'";
let sql=`INSERT INTO workspaces(id,name) VALUES(${quote(workspace)},${quote('دفتر مدیریت')});\n`;
for(const u of users) sql+=`INSERT INTO users(id,workspace,username,name,role,salt,password_hash,iterations) VALUES(${[u.id,workspace,u.username,u.name,u.role,u.salt,u.hash].map(quote).join(',')},100000);\n`;
await writeFile(resolve(dir,'provision.sql'),sql,{mode:0o600});
await writeFile(credentialsFile,JSON.stringify({workspace,users:users.map(({username,name,role,password})=>({username,name,role,password}))},null,2),{mode:0o600});
console.log('Private SQL and credentials created under .device-private/cloudflare. No real meeting data was uploaded.');
