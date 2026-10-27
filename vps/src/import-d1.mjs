import {DatabaseSync,constants} from 'node:sqlite';
import {readFileSync,renameSync,existsSync,unlinkSync} from 'node:fs';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {Registry,companyCode} from './registry.mjs';
import {instanceLock,openDatabase} from './database.mjs';

/** Offline import of an administrator-owned D1 SQL export. Never exposed over HTTP. */
export function importD1(dataDir,{sqlFile,code,name}) {
  if(!companyCode(code) || typeof name!=='string' || !name.trim() || name.length>200)throw new Error('A valid company code and name are required.');
  const unlock=instanceLock(dataDir);let registry,db,target,committed=false,moved=false;
  const temporary=join(dataDir,'tenants','import-'+randomUUID()+'.sqlite');
  try {
    registry=new Registry(dataDir);
    if(!registry.db.prepare('SELECT 1 FROM platform_admin').get())throw new Error('Start the API once to initialize the owner account, then stop it before importing.');
    if(registry.find(code))throw new Error('Company code already exists; existing companies are never replaced.');
    db=openDatabase(temporary);
    db.setAuthorizer((action,one,two)=>{
      if(action===constants.SQLITE_ATTACH || action===constants.SQLITE_DETACH || action===constants.SQLITE_CREATE_VTABLE)return constants.SQLITE_DENY;
      if(action===constants.SQLITE_PRAGMA && !['foreign_keys','defer_foreign_keys','user_version','quick_check','foreign_key_check'].includes(String(one).toLowerCase()))return constants.SQLITE_DENY;
      if(action===constants.SQLITE_FUNCTION && String(two).toLowerCase()==='load_extension')return constants.SQLITE_DENY;
      return constants.SQLITE_OK;
    });
    db.exec(readFileSync(sqlFile,'utf8'));db.setAuthorizer(null);
    db.exec('PRAGMA foreign_keys=ON');
    if(Object.values(db.prepare('PRAGMA quick_check').get())[0]!=='ok' || db.prepare('PRAGMA foreign_key_check').all().length)throw new Error('The imported database failed integrity checks.');
    const workspaces=db.prepare('SELECT id FROM workspaces').all();
    if(workspaces.length!==1 || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(workspaces[0].id))throw new Error('Import requires exactly one company. Export each company separately for a multi-company source.');
    const id=workspaces[0].id;
    if(registry.byId(id))throw new Error('This company identity has already been imported.');
    const manager=db.prepare("SELECT id FROM users WHERE workspace=? AND active=1 AND COALESCE(access_role,role)='manager' ORDER BY id LIMIT 1").get(id);if(!manager)throw new Error('An active chief account is required.');
    // Check organization schema before moving any data into the active company directory.
    db.prepare('SELECT user_id FROM operations LIMIT 1').all();db.prepare('SELECT token FROM push_devices LIMIT 1').all();db.prepare('SELECT enabled FROM organization_settings LIMIT 1').all();
    db.exec('BEGIN IMMEDIATE');try{db.exec('DELETE FROM sessions; DELETE FROM push_devices; DELETE FROM push_outbox; DELETE FROM login_limits;');db.prepare('UPDATE workspaces SET name=? WHERE id=?').run(name.trim(),id);db.exec('COMMIT');}catch(e){db.exec('ROLLBACK');throw e;}
    db.close();db=null;target=join(registry.tenantDir,id+'.sqlite');if(existsSync(target))throw new Error('A database file with this identity already exists.');
    renameSync(temporary,target);moved=true;
    registry.db.exec('BEGIN IMMEDIATE');try{registry.db.prepare('INSERT INTO companies VALUES(?,?,?,1,?,?)').run(id,code,name.trim(),manager.id,Date.now());registry.audit(id,'import-d1');registry.db.exec('COMMIT');committed=true;}catch(e){registry.db.exec('ROLLBACK');throw e;}
    return {id,code};
  }finally{
    db?.close();registry?.close();
    for(const p of [temporary,temporary+'-wal',temporary+'-shm',...(!committed && moved && target?[target]:[])])if(existsSync(p))unlinkSync(p);
    unlock();
  }
}
