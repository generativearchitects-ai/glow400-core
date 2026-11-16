def log = new File(basedir, 'build.log').text
assert log.contains("[ERROR] apis/ORDSTATUS/api.yaml:3:8: error OVL012: 'route' must start with '/'") : 'the error is an error line of the text report'
assert log =~ /pcml:validate failed: 1 error, 0 warnings, 0 notes/ : 'the build fails with the count on one line'
assert log.contains('BUILD FAILURE')
assert !new File(basedir, 'target').exists() : 'validate writes nothing'
