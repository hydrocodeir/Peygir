import {existsSync} from 'node:fs';
import {join} from 'node:path';
import {Registry} from '../src/registry.mjs';
import {instanceLock} from '../src/database.mjs';

/** Offline preflight: migrate every tenant before the new API accepts writes. */
export function verifyUpgrade(dataDir) {
  if(!existsSync(join(dataDir,'registry.sqlite'))) throw new Error('Existing registry is required; automatic deployment cannot initialize a server');
  const unlock=instanceLock(dataDir);
  let registry;
  try {
    registry=new Registry(dataDir);
    const check=db=>{
      if(Object.values(db.prepare('PRAGMA quick_check').get())[0]!=='ok' || db.prepare('PRAGMA foreign_key_check').all().length) throw new Error('Database integrity check failed');
    };
    check(registry.db);
    const companies=registry.list();
    for(const company of companies) {
      const lease=registry.lease(company);
      try {check(lease.db);} finally {lease.release();}
    }
    return companies.length;
  } finally {registry?.close();unlock();}
}
if(process.argv[1]?.endsWith('verify-upgrade.mjs')) {
  process.umask(0o077);
  console.log(`Upgrade preflight passed for ${verifyUpgrade(process.env.DATA_DIR || '/data')} companies`);
}
