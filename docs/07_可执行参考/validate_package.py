"""Static document/SQL/ref schema checks; NOT a substitute for MySQL or Docker runtime tests."""
import json
import re
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
ART=ROOT/'07_可执行参考'
sql=(ART/'schema.mysql8.4.reference.sql').read_text(encoding='utf-8')
tables=re.findall(r'CREATE TABLE IF NOT EXISTS `([a-z_]+)` \(',sql)
assert len(tables)>=65, f'Expected >= 65 tables; got {len(tables)}'
assert len(set(tables))==len(tables), 'Duplicate table name'
for child,col,parent,ref in re.findall(r'ALTER TABLE `([^`]+)` ADD CONSTRAINT `[^`]+` FOREIGN KEY \(`([^`]+)`\) REFERENCES `([^`]+)` \(`([^`]+)`\)',sql):
 assert child in tables and parent in tables, (child,parent)
 assert ref=='id'
# Each CREATE has an explicit PK and namespace.
chunks=re.findall(r'CREATE TABLE IF NOT EXISTS `[^`]+` \((.*?)\) ENGINE=InnoDB',sql,flags=re.S)
assert len(chunks)==len(tables), (len(chunks),len(tables))
for chunk in chunks:
 assert 'PRIMARY KEY (`id`)' in chunk
 assert '`tenant_id`' in chunk
 colnames=re.findall(r'^  `([^`]+)` (?:BIGINT|INT|VARCHAR|DATE|DATETIME|TEXT|JSON|DECIMAL|TINYINT)',chunk,flags=re.M)
 assert len(colnames)==len(set(colnames)), f'duplicate columns: {colnames}'
assert (ART/'seed.dictionaries.dev.sql').exists()
json.loads((ART/'event-envelope.example.json').read_text(encoding='utf-8'))
json.loads((ART/'event-envelope.schema.json').read_text(encoding='utf-8'))
try:
 import yaml
 for fn in ['docker-compose.dev.yml','openapi.v1.skeleton.yaml']:
  assert isinstance(yaml.safe_load((ART/fn).read_text(encoding='utf-8')),dict)
except ImportError:
 print('WARNING: PyYAML not installed, YAML validation skipped')
try:
 import jsonschema
 schema=json.loads((ART/'event-envelope.schema.json').read_text(encoding='utf-8'))
 event=json.loads((ART/'event-envelope.example.json').read_text(encoding='utf-8'))
 jsonschema.validate(event,schema)
except ImportError:
 print('WARNING: jsonschema not installed, schema check skipped')
required=['README.md','00_项目管理/02_待确认事项与决策登记.md','01_需求与产品/11_业务流程与状态机.md','02_架构与技术/22_Kafka事件与可靠投递.md','03_数据模型/31_数据库字段字典.md','04_接口与集成/40_REST_API规范.md','06_大模型开发指令/60_AGENTS.md','08_原型与来源/V2.0_交互原型.html']
for f in required: assert (ROOT/f).exists(), f
print(f'STATIC VALIDATION PASS: {len(tables)} SQL tables; FK references found; examples parse; critical docs present.')
print('NOTE: MySQL/Kafka/Redis/Docker integration tests are NOT run by this script.')
