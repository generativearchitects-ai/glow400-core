def log = new File(basedir, 'build.log').text
assert log.contains('[WARNING] apis/ORDSTATUS/api.yaml:1:1: warning OVL006: Operation ordstatus has no error mapping') : 'the warning is a warning line of the text report'
assert log.contains('[INFO] pcml:validate: 0 errors, 1 warning, 0 notes') : 'the summary'
assert log.contains('BUILD SUCCESS')
assert !new File(basedir, 'target').exists() : 'validate writes nothing'
