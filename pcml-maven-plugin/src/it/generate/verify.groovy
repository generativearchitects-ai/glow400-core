def log = new File(basedir, 'build.log').text
assert log.contains('BUILD SUCCESS')
assert new File(basedir, 'target/pcml/descriptor.json').isFile()
assert new File(basedir, 'target/pcml/openapi/ordstatus.yaml').isFile()
assert !new File(basedir, 'target/pcml/mock').exists() : 'the repository has no mock files'
assert new File(basedir, 'target').list().toList() == ['pcml'] : 'nothing is written outside target/pcml'
assert log.contains('[INFO] pcml:generate: 0 errors, 1 warning, 0 notes')
