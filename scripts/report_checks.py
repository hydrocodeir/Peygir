from pathlib import Path
from datetime import datetime, timezone, timedelta
import xml.etree.ElementTree as ET
import json

root=Path(__file__).resolve().parents[1]
suites=[ET.parse(p).getroot() for p in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
assert suites, 'Missing JUnit reports'
tests=sum(int(s.attrib['tests']) for s in suites)
failures=sum(int(s.attrib.get('failures','0'))+int(s.attrib.get('errors','0')) for s in suites)
skipped=sum(int(s.attrib.get('skipped','0')) for s in suites)
assert failures==0 and skipped==0, 'Tests must all pass'
lint=ET.parse(root/'app/build/reports/lint-results-debug.xml').getroot()
errors=[i for i in lint.findall('issue') if i.attrib['severity'] in ['Error','Fatal']]
warnings=[i for i in lint.findall('issue') if i.attrib['severity']=='Warning']
assert not errors, 'Lint must not have errors'
result=dict(checkedAtTehran=datetime.now(timezone(timedelta(hours=3,minutes=30))).isoformat(),tests=tests,failures=failures,skipped=skipped,lintErrors=len(errors),lintWarnings=len(warnings),lintWarningIds=[i.attrib['id'] for i in warnings],suites=[dict(name=s.attrib['name'],tests=int(s.attrib['tests'])) for s in suites])
(root/'verification/check-summary.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result,indent=2))
