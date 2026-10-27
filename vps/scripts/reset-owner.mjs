import {writeFileSync,mkdirSync} from 'node:fs';
import {join} from 'node:path';
import {Registry,secret} from '../src/registry.mjs';
import {instanceLock} from '../src/database.mjs';
import {passwordHash} from '../../cloudflare/src/worker.mjs';
process.umask(0o077);
const data=process.env.DATA_DIR || '/data',unlock=instanceLock(data);let registry;
try {
  registry=new Registry(data);const admin=registry.db.prepare('SELECT username FROM platform_admin WHERE id=1').get();if(!admin)throw new Error('Initialize the API first.');
  const password=secret(),salt=secret(),hash=await passwordHash(password,salt),dir=process.env.BACKUP_DIR || '/backups';mkdirSync(dir,{recursive:true,mode:0o700});
  const path=join(dir,'owner-login-'+Date.now()+'.txt');writeFileSync(path,`Username: ${admin.username}\nPassword: ${password}\n`,{mode:0o600,flag:'wx'});
  registry.db.exec('BEGIN IMMEDIATE');try{registry.db.prepare('UPDATE platform_admin SET salt=?,password_hash=? WHERE id=1').run(salt,hash);registry.db.exec('DELETE FROM admin_sessions');registry.audit(null,'reset-owner');registry.db.exec('COMMIT');}catch(e){registry.db.exec('ROLLBACK');throw e;}
  console.log('Owner password reset. Read the private file '+path+' locally; no credentials were printed.');
}finally{registry?.close();unlock();}
