import {DatabaseSync,backup} from 'node:sqlite';
import {mkdirSync,readdirSync,readFileSync,writeFileSync,copyFileSync,constants,lstatSync,existsSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {instanceLock} from './database.mjs';
const hash=path=>createHash('sha256').update(readFileSync(path)).digest('hex');
const uuid=s=>typeof s==='string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(s);
export async function makeBackup(dataDir,backupRoot) {
  const unlock=instanceLock(dataDir);let registry;
  try {
    if(!existsSync(join(dataDir,'registry.sqlite'))) throw new Error('No registry database found.');
    const dest=join(backupRoot,new Date().toISOString().replace(/[:.]/g,'-')+'-'+randomUUID().slice(0,8));mkdirSync(dest,{recursive:true,mode:0o700});mkdirSync(join(dest,'tenants'),{mode:0o700});
    registry=new DatabaseSync(join(dataDir,'registry.sqlite'),{readOnly:true});const companies=registry.prepare('SELECT id FROM companies ORDER BY id').all();
    const files=[];await backup(registry,join(dest,'registry.sqlite'));files.push('registry.sqlite');
    for(const {id} of companies){if(!uuid(id))throw new Error('Invalid company identity');const name='tenants/'+id+'.sqlite';const db=new DatabaseSync(join(dataDir,name),{readOnly:true});try{await backup(db,join(dest,name));}finally{db.close();}files.push(name);}
    writeFileSync(join(dest,'manifest.json'),JSON.stringify({format:1,version:'4.7.0',created:Date.now(),files:files.map(name=>({name,sha256:hash(join(dest,name))}))},null,2)+'\n',{mode:0o600,flag:'wx'});return dest;
  }finally{registry?.close();unlock();}
}
export function restoreBackup(dataDir,source) {
  const unlock=instanceLock(dataDir);
  try {
    // Restore only into a fresh data volume. Existing company information is never overwritten.
    if(readdirSync(dataDir).some(n=>!n.startsWith('instance-lock.sqlite'))) throw new Error('Restore requires a fresh empty data volume.');
    const manifest=JSON.parse(readFileSync(join(source,'manifest.json'),'utf8'));
    if(manifest.format!==1 || !Array.isArray(manifest.files) || !manifest.files.length)throw new Error('Unsupported backup manifest');
    const names=new Set();
    for(const f of manifest.files){
      if(typeof f.name!=='string' || !(f.name==='registry.sqlite' || /^tenants\/[a-f0-9-]{36}\.sqlite$/.test(f.name)) || names.has(f.name) || !/^[a-f0-9]{64}$/.test(f.sha256))throw new Error('Invalid backup entry');
      names.add(f.name);const path=resolve(source,f.name);if(!lstatSync(path).isFile() || lstatSync(path).isSymbolicLink() || hash(path)!==f.sha256)throw new Error('Backup checksum failed');
      const db=new DatabaseSync(path,{readOnly:true});try{if(Object.values(db.prepare('PRAGMA quick_check').get())[0]!=='ok' || db.prepare('PRAGMA foreign_key_check').all().length)throw new Error('Invalid SQLite backup');}finally{db.close();}
    }
    if(!names.has('registry.sqlite'))throw new Error('Registry is missing');
    const registry=new DatabaseSync(join(source,'registry.sqlite'),{readOnly:true});
    try {const ids=registry.prepare('SELECT id FROM companies').all().map(r=>r.id);if(ids.some(id=>!uuid(id) || !names.has('tenants/'+id+'.sqlite')) || names.size!==ids.length+1)throw new Error('Incomplete company backup');}finally{registry.close();}
    mkdirSync(join(dataDir,'tenants'),{mode:0o700});
    for(const f of manifest.files)copyFileSync(join(source,f.name),join(dataDir,f.name),constants.COPYFILE_EXCL);
    const db=new DatabaseSync(join(dataDir,'registry.sqlite'));try{db.exec('DELETE FROM admin_sessions');if(db.prepare("SELECT 1 FROM sqlite_master WHERE name='web_profiles'").get())db.exec('PRAGMA foreign_keys=ON; DELETE FROM web_profiles');}finally{db.close();}
    // A restored server can have older revisions than phones. Force a clean, authorized
    // download instead of reviving revoked sessions or retaining stale sync inventories.
    for(const name of names) if(name.startsWith('tenants/')) {
      const db=new DatabaseSync(join(dataDir,name));try{db.exec('BEGIN IMMEDIATE; DELETE FROM sessions; DELETE FROM push_devices; DELETE FROM push_outbox; COMMIT;');if(db.prepare("SELECT 1 FROM sqlite_master WHERE name='web_push_outbox'").get())db.exec('DELETE FROM web_push_outbox; DELETE FROM web_scheduled;');}finally{db.close();}
    }
  }finally{unlock();}
}
