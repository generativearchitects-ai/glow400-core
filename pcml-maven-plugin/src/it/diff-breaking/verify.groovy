def log = new File(basedir, 'build.log').text
assert log.contains('[ERROR] ') && log.contains('error API002: API ordstatus has breaking changes but its version goes from 1.0.0 to 1.0.0; the major version must increase.')
assert log.contains('Response field status was removed')
assert log =~ /pcml:diff failed: 1 error, 1 warning, [0-9]+ notes?/
assert log.contains('BUILD FAILURE')
assert !new File(basedir, 'target').exists() : 'diff writes nothing'
